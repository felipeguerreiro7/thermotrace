import copy
from uuid import uuid4, UUID
import pytest
from sqlalchemy import select, text, func
from sqlalchemy.exc import DBAPIError

from tests.test_acesso_perfis import ambiente, headers, criar_perfil, aprovar, PERFIL
from app.models import Remessa, Documento, Volume, ProdutoConfiguracao, ChaveIdempotencia, LogAuditoria


def post(c, path, h, body, key=None):
    return c.post('/api/v1'+path, headers={**h, 'Idempotency-Key': key or str(uuid4())}, json=body)


def preparar(c, h, codigo='SKU-TESTE'):
    perfil = criar_perfil(c, h)
    assert aprovar(c, h, perfil['id']).status_code == 200
    r = post(c, '/produtos', h, {'codigo': codigo, 'perfil_termico_id': perfil['id']})
    assert r.status_code == 201, r.text
    return perfil, r.json()


def payload(produto, codigo='CARGA-001'):
    return {'codigo': codigo, 'produto_configuracao_id': produto['id'], 'destinatario_nome': 'Hospital sintético',
            'quantidade_volumes': 2, 'documentos': [{'tipo': 'pedido', 'numero': 'PED-123'}]}


def remessa(c, h, produto, codigo='CARGA-001', key=None):
    r = post(c, '/remessas', h, payload(produto, codigo), key)
    assert r.status_code == 201, r.text
    return r.json()


def test_caminho_produto_carga_documento_e_volumes(ambiente):
    c, db, users, entrar = ambiente
    h = headers(entrar()); perfil, produto = preparar(c, h)
    r = remessa(c, h, produto)
    assert r['status'] == 'preparacao'
    assert r['criterio']['perfil']['min_c'] == '2.00'
    assert r['criterio']['perfil']['id'] == perfil['id']
    assert len(r['documentos']) == 1 and len(r['volumes']) == 2
    assert all(not v['monitorado'] and v['identidade'].startswith(r['id']) for v in r['volumes'])
    assert c.get('/api/v1/produtos/localizar', headers=h, params={'codigo':'sku-teste'}).json()['id'] == produto['id']
    assert c.get('/api/v1/remessas/localizar', headers=h, params={'valor':'ped-123'}).json()['candidatas'][0]['id'] == r['id']
    assert c.get(f"/api/v1/remessas/{r['id']}", headers=h).json() == r


def test_mesmo_documento_em_entregas_parciais_e_multiplos_documentos(ambiente):
    c, db, users, entrar = ambiente
    h = headers(entrar()); _, p = preparar(c, h)
    a = remessa(c, h, p, 'PARCIAL-1'); b = remessa(c, h, p, 'PARCIAL-2')
    doc = {'tipo':'nfe', 'numero':'NF-456', 'conteudo_bruto':'  evidencia original\n '}
    assert post(c, f"/remessas/{a['id']}/documentos", h, doc).status_code == 201
    detalhe = c.get(f"/api/v1/remessas/{a['id']}", headers=h).json()
    assert len(detalhe['documentos']) == 2
    novo = next(d for d in detalhe['documentos'] if d['numero']=='NF-456')
    assert novo['conteudo_bruto'] == doc['conteudo_bruto']
    assert novo['validado'] is False
    found = c.get('/api/v1/remessas/localizar', headers=h, params={'valor':'PED-123'}).json()
    assert {r['id'] for r in found['candidatas']} == {a['id'],b['id']}
    assert not found['ha_mais']


def test_reenvio_exato_nao_duplica_carga_documento_ou_auditoria(ambiente):
    c, db, users, entrar = ambiente
    h = headers(entrar()); _, p = preparar(c, h)
    key = str(uuid4()); body = payload(p)
    a = post(c, '/remessas', h, body, key); b = post(c, '/remessas', h, body, key)
    assert a.status_code == b.status_code == 201
    assert a.json() == b.json()
    assert b.headers['Idempotency-Replayed'] == 'true'
    assert db.scalar(select(func.count()).select_from(Remessa).where(Remessa.empresa_embarcador_id==users['a'].empresa_id)) == 1
    assert db.scalar(select(func.count()).select_from(LogAuditoria).where(LogAuditoria.entidade_id==UUID(a.json()['id']),LogAuditoria.acao=='remessa_criada')) == 1
    body['destinatario_nome'] = 'Outro hospital'
    assert post(c, '/remessas', h, body, key).status_code == 409


def test_idempotencia_isola_empresa_usuario_e_rota(ambiente):
    c, db, users, entrar = ambiente
    a, b, op = headers(entrar()), headers(entrar('b')), headers(entrar('op'))
    _, pa = preparar(c, a); _, pb = preparar(c, b)
    key = str(uuid4())
    ra = remessa(c, a, pa, 'MESMO-CODIGO', key)
    rb = remessa(c, b, pb, 'MESMO-CODIGO', key)
    rop = remessa(c, op, pa, 'OUTRA-CARGA', key)
    assert len({ra['id'], rb['id'], rop['id']}) == 3
    d = post(c, f"/remessas/{ra['id']}/documentos", a, {'tipo':'pedido','numero':'OUTRO'}, key)
    assert d.status_code == 201


def test_erro_nao_deixa_recibo_e_pode_repetir_apos_corrigir(ambiente):
    c, db, users, entrar = ambiente
    h = headers(entrar()); _, p = preparar(c, h)
    key = str(uuid4()); body=payload(p); body['documentos'] *= 2
    assert post(c, '/remessas', h, body, key).status_code == 422
    assert db.scalar(select(ChaveIdempotencia.id).where(ChaveIdempotencia.chave==key)) is None
    assert post(c, '/remessas', h, payload(p), key).status_code == 201


def test_dois_clientes_nao_acessam_carga_documento_produto(ambiente):
    c, db, users, entrar = ambiente
    a, b, staff = headers(entrar()), headers(entrar('b')), headers(entrar('staff'))
    _, p = preparar(c, a); r = remessa(c, a, p)
    assert c.get('/api/v1/remessas', headers=b).json() == []
    assert c.get('/api/v1/produtos', headers=b).json() == []
    for path in [f"/remessas/{r['id']}", f"/produtos/{p['id']}"]:
        assert c.get('/api/v1'+path, headers=b).status_code == 404
        assert c.get('/api/v1'+path, headers=staff).status_code == 403
    assert c.get('/api/v1/remessas/localizar', headers=b, params={'valor':'PED-123'}).json()['candidatas'] == []
    assert post(c, f"/remessas/{r['id']}/documentos", b, {'tipo':'pedido','numero':'X'}).status_code == 404
    assert post(c, f"/remessas/{r['id']}/cancelar", b, {'motivo':'Tentativa de outro cliente.'}).status_code == 404
    assert post(c, '/remessas', b, payload(p)).status_code == 404


def test_operador_cria_carga_mas_nao_configura_produto_nem_cancela(ambiente):
    c, db, users, entrar = ambiente
    a, op = headers(entrar()), headers(entrar('op'))
    perfil,p=preparar(c,a)
    assert post(c,'/produtos',op,{'codigo':'SKU-2','perfil_termico_id':perfil['id']}).status_code == 403
    r=remessa(c,op,p)
    assert post(c,f"/remessas/{r['id']}/cancelar",op,{'motivo':'Cancelamento sintético.'}).status_code == 403


def test_cancelamento_preserva_documentos_e_nao_simula_stop(ambiente):
    c, db, users, entrar=ambiente
    h=headers(entrar()); _,p=preparar(c,h); r=remessa(c,h,p)
    key=str(uuid4()); body={'motivo':'Identificação incorreta na preparação.'}
    assert post(c,f"/remessas/{r['id']}/cancelar",h,body,key).json()['status']=='cancelada'
    assert post(c,f"/remessas/{r['id']}/cancelar",h,body,key).headers['Idempotency-Replayed']=='true'
    atual=c.get(f"/api/v1/remessas/{r['id']}",headers=h).json()
    assert len(atual['documentos'])==1 and all(v['status']=='sem_etiqueta' for v in atual['volumes'])
    assert c.get('/api/v1/remessas/localizar',headers=h,params={'valor':'PED-123'}).json()['candidatas']==[]
    assert len(c.get('/api/v1/remessas/localizar',headers=h,params={'valor':'PED-123','incluir_canceladas':True}).json()['candidatas'])==1
    assert post(c,f"/remessas/{r['id']}/documentos",h,{'tipo':'pedido','numero':'OUTRO'}).status_code==422


def test_mudar_produto_preserva_criterio_da_carga_existente(ambiente):
    c,db,users,entrar=ambiente
    h=headers(entrar()); perfil,p=preparar(c,h); r=remessa(c,h,p)
    body={'codigo':p['codigo'],'perfil_termico_id':perfil['id'],'intervalo_segundos':300,'versao_anterior':1}
    newer=post(c,'/produtos',h,body)
    assert newer.status_code==201, newer.text
    assert newer.json()['versao']==2
    assert post(c,'/produtos',h,body).status_code==409
    assert post(c,'/remessas',h,payload(p,'OUTRA')).status_code==422
    atual=c.get(f"/api/v1/remessas/{r['id']}",headers=h).json()
    assert atual['criterio']==r['criterio'] and atual['intervalo_segundos']==600
    assert remessa(c,h,newer.json(),'NOVA')['intervalo_segundos']==300


def test_perfil_rascunho_ou_retirado_nao_entra_em_nova_carga(ambiente):
    c,db,users,entrar=ambiente
    h=headers(entrar()); perfil=criar_perfil(c,h)
    assert post(c,'/produtos',h,{'codigo':'X','perfil_termico_id':perfil['id']}).status_code==422
    assert aprovar(c,h,perfil['id']).status_code==200
    p=post(c,'/produtos',h,{'codigo':'X','perfil_termico_id':perfil['id']}).json()
    key=str(uuid4()); r=remessa(c,h,p,key=key)
    assert c.post(f"/api/v1/perfis/{perfil['id']}/retirar",headers=h).status_code==200
    assert c.get('/api/v1/produtos/localizar',headers=h,params={'codigo':'X'}).json()['disponivel_para_nova_carga'] is False
    assert post(c,'/remessas',h,payload(p,'OUTRA')).status_code==422
    # Reenviar a criação já confirmada retorna o recibo original, sem criar carga nova.
    assert remessa(c,h,p,key=key)['id']==r['id']


@pytest.mark.parametrize('alteracao',[{'empresa_id':str(uuid4())},{'min_c':0},{'intervalo_segundos':1},
    {'quantidade_volumes':0},{'quantidade_volumes':True},{'quantidade_volumes':201},
    {'codigo':None,'documentos':[]}])
def test_recusa_injecao_e_entrada_invalida(ambiente,alteracao):
    c,db,users,entrar=ambiente
    h=headers(entrar()); _,p=preparar(c,h); body=payload(p);body.update(alteracao)
    assert post(c,'/remessas',h,body).status_code==422


def test_codigo_gerado_quando_documento_identifica_carga(ambiente):
    c,db,users,entrar=ambiente
    h=headers(entrar());_,p=preparar(c,h)
    r=post(c,'/remessas',h,payload(p,None));assert r.status_code==201,r.text
    assert r.json()['codigo'].startswith('REM-')
    assert post(c,'/remessas',h,payload(p,'lower-case')).status_code==201
    assert post(c,'/remessas',h,payload(p,'LOWER-CASE')).status_code==409


def test_documento_duplicado_recusado_na_mesma_carga(ambiente):
    c,db,users,entrar=ambiente
    h=headers(entrar());_,p=preparar(c,h);r=remessa(c,h,p)
    assert post(c,f"/remessas/{r['id']}/documentos",h,{'tipo':'pedido','numero':'ped-123'}).status_code==409


def test_sql_nao_altera_historico_snapshot_documentos_e_recibos(ambiente):
    c,db,users,entrar=ambiente
    h=headers(entrar());_,p=preparar(c,h);key=str(uuid4());r=remessa(c,h,p,key=key)
    queries=[("UPDATE remessa SET intervalo_segundos=1 WHERE id=:id",r['id']),
             ("UPDATE remessa SET criterio_snapshot='{}'::jsonb WHERE id=:id",r['id']),
             ("DELETE FROM remessa WHERE id=:id",r['id']),
             ("UPDATE documento SET numero='ADULTERADO' WHERE id=:id",r['documentos'][0]['id']),
             ("UPDATE produto_configuracao SET intervalo_segundos=1 WHERE id=:id",p['id'])]
    for query,id_ in queries:
        with pytest.raises(DBAPIError):
            with db.begin_nested():db.execute(text(query),{'id':UUID(id_)})
    with pytest.raises(DBAPIError):
        with db.begin_nested(): db.execute(text("UPDATE chave_idempotencia SET resposta_corpo='{}' WHERE chave=:chave"),{'chave':key})


@pytest.mark.parametrize('path',['/remessas','/produtos','/remessas/localizar?valor=X','/produtos/localizar?codigo=X'])
def test_novas_rotas_exigem_login(ambiente,path):
    assert ambiente[0].get('/api/v1'+path).status_code==401


def test_mutacao_exige_chave_de_reenvio(ambiente):
    c,db,users,entrar=ambiente
    h=headers(entrar());_,p=preparar(c,h)
    assert c.post('/api/v1/remessas',headers=h,json=payload(p)).status_code==422


def test_documento_legado_sem_hash_nao_e_duplicado(ambiente):
    c,db,users,entrar=ambiente
    h=headers(entrar());_,p=preparar(c,h);r=remessa(c,h,p)
    db.execute(text("INSERT INTO documento (remessa_id,tipo,numero) VALUES (:id,'pedido',' legado-001 ')"),{'id':r['id']})
    db.commit()
    assert post(c,f"/remessas/{r['id']}/documentos",h,{'tipo':'pedido','numero':'LEGADO-001'}).status_code==409
