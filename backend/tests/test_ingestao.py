import copy
from datetime import datetime, timezone, timedelta
from uuid import uuid4, UUID
import pytest
from sqlalchemy import select, text, func
from sqlalchemy.exc import DBAPIError
from tests.test_acesso_perfis import ambiente, headers
from tests.test_cargas import preparar, remessa, post
from app.models import (LoteEtiqueta,Etiqueta,Dispositivo,SessaoMonitoramento,LeituraEtiqueta,
                        VinculoEtiqueta,Volume,LogAuditoria,ChaveIdempotencia)
from app.models.enums import EstadoEtiqueta


def catalogo(db, empresa):
    # Cadastro sintético explícito pelo dono do banco; não sugere calibração nem inventário físico aprovado.
    lote=LoteEtiqueta(fornecedor='Fornecedor sintético',modelo_hardware='SIMULACAO',quantidade=1)
    db.add(lote);db.flush()
    tag=Etiqueta(lote_id=lote.id,empresa_id=empresa,serial='SIM-'+uuid4().hex,
                 qr_payload='TESTE:'+uuid4().hex,nfc_uid=uuid4().hex[:16].upper())
    db.add(tag);db.flush();db.commit();return tag


def preparar_ingestao(ambiente, nome='a', codigo='CARGA-TESTE'):
    c,db,users,entrar=ambiente;h=headers(entrar(nome))
    _,p=preparar(c,h);r=remessa(c,h,p,codigo)
    tag=catalogo(db,users[nome].empresa_id)
    d=post(c,'/dispositivos',h,{'install_id':str(uuid4()),'modelo':'Aparelho sintético','versao_so':'teste','versao_app':'0.8-teste'})
    assert d.status_code==201,d.text
    t=datetime(2026,9,12,12,tzinfo=timezone.utc)
    body={'sessao_id':str(uuid4()),'etiqueta_id':str(tag.id),'epoch_inicio_etiqueta':int(t.timestamp()),
          'intervalo_segundos':600,'quantidade_planejada':100,'min_configurado_c':'2.00','max_configurado_c':'8.00',
          'ativacao_confirmada':True,'evidencia':{'evento_id':str(uuid4()),'dispositivo_id':d.json()['id'],
          'lida_em':t.isoformat(),'uid_canonico':tag.nfc_uid,'versao_sdk':'sintetico-1',
          'versao_decodificador':'teste-1','resposta_bruta':['  raw\n','00FF'],'origem':'simulacao'}}
    return c,db,users,h,r,tag,body


def iniciar(d, key=None):
    c,db,users,h,r,tag,body=d
    return post(c,f"/volumes/{r['volumes'][0]['id']}/sessoes",h,body,key)


def leitura(body, tipo='checkpoint', minutos=10):
    b=copy.deepcopy(body['evidencia']);b['evento_id']=str(uuid4());b['tipo']=tipo
    b['lida_em']=(datetime.fromisoformat(b['lida_em'])+timedelta(minutes=minutos)).isoformat()
    b['resposta_bruta']=['EVIDENCIA SIMULADA '+str(minutos)]
    b['epoch_inicio_etiqueta']=body['epoch_inicio_etiqueta'];b['intervalo_relatado_s']=600
    return b


def enviar(d,b,key=None,h=None):
    return post(d[0],f"/sessoes/{d[-1]['sessao_id']}/leituras",h or d[3],b,key)


def test_fluxo_inicio_checkpoint_final_cadeia_e_sem_stop(ambiente):
    d=preparar_ingestao(ambiente);c,db,users,h,r,tag,body=d
    a=iniciar(d);assert a.status_code==201,a.text
    b=enviar(d,leitura(body));assert b.status_code==201,b.text
    f=enviar(d,leitura(body,'final',20));assert f.status_code==201,f.text
    recibos=[x.json() for x in [a,b,f]]
    assert [x['ordem_recebimento'] for x in recibos]==[1,2,3]
    assert recibos[0]['hash_anterior'] is None
    assert recibos[1]['hash_anterior']==recibos[0]['hash_encadeado']
    assert recibos[2]['hash_anterior']==recibos[1]['hash_encadeado']
    assert f.json()['stop_fisico']=='nao_confirmado_pelo_servidor'
    assert all(x['origem']=='simulacao' for x in recibos)
    assert db.get(Etiqueta,tag.id).estado==EstadoEtiqueta.MONITORANDO
    assert db.scalar(select(VinculoEtiqueta).where(VinculoEtiqueta.etiqueta_id==tag.id)).liberada_em is None
    ss=db.get(SessaoMonitoramento,UUID(body['sessao_id']));assert ss.encerrada_em is not None
    q=c.get(f"/api/v1/sessoes/{body['sessao_id']}/integridade",headers=h)
    assert q.status_code==200,q.text
    assert q.json()['integra'] and q.json()['quantidade']==3
    detalhe=c.get(f"/api/v1/sessoes/{body['sessao_id']}/leituras/{a.json()['leitura_id']}",headers=h).json()
    assert detalhe['resposta_bruta']==body['evidencia']['resposta_bruta']
    assert detalhe['envelope_integridade']['usuario_id']==str(users['a'].id)


def test_reenvio_inicio_http_e_evento_com_chave_nova(ambiente):
    d=preparar_ingestao(ambiente);key=str(uuid4())
    a=iniciar(d,key);b=iniciar(d,key);c=iniciar(d)
    assert a.status_code==b.status_code==c.status_code==201
    assert a.json()==b.json()==c.json()
    assert b.headers['Idempotency-Replayed']=='true'
    assert d[1].scalar(select(func.count()).select_from(LeituraEtiqueta).where(LeituraEtiqueta.sessao_id==UUID(d[-1]['sessao_id'])))==1
    assert d[1].scalar(select(func.count()).select_from(LogAuditoria).where(LogAuditoria.acao=='evidencia_recebida',LogAuditoria.empresa_id==d[2]['a'].empresa_id))==1


def test_reenvio_depois_do_final_preserva_recibo(ambiente):
    d=preparar_ingestao(ambiente);iniciar(d);b=leitura(d[-1]);key=str(uuid4())
    a=enviar(d,b,key);enviar(d,leitura(d[-1],'final',20))
    assert enviar(d,b,key).json()==a.json()
    assert enviar(d,b).json()==a.json()
    assert d[1].scalar(select(func.count()).select_from(LeituraEtiqueta).where(LeituraEtiqueta.sessao_id==UUID(d[-1]['sessao_id'])))==3


@pytest.mark.parametrize('campo',['resposta_bruta','versao_decodificador','lida_em'])
def test_mesmo_evento_conteudo_diferente_eh_conflito(ambiente,campo):
    d=preparar_ingestao(ambiente);iniciar(d);b=leitura(d[-1]);assert enviar(d,b).status_code==201
    b[campo]={'resposta_bruta':['ALTERADA'],'versao_decodificador':'outra','lida_em':'2026-09-12T13:00:00Z'}[campo]
    assert enviar(d,b).status_code==409
    assert d[1].scalar(select(func.count()).select_from(LeituraEtiqueta).where(LeituraEtiqueta.sessao_id==UUID(d[-1]['sessao_id'])))==2


def test_checkpoint_atrasado_preservado_apos_final(ambiente):
    d=preparar_ingestao(ambiente);iniciar(d)
    assert enviar(d,leitura(d[-1],'final',20)).status_code==201
    r=enviar(d,leitura(d[-1],minutos=10));assert r.status_code==201,r.text
    assert r.json()['ordem_recebimento']==3
    assert enviar(d,leitura(d[-1],minutos=30)).status_code==409
    assert enviar(d,leitura(d[-1],'final',20)).status_code==409


def test_final_anterior_a_checkpoint_e_recusado_sem_recibo(ambiente):
    d=preparar_ingestao(ambiente);iniciar(d);enviar(d,leitura(d[-1],minutos=20))
    key=str(uuid4());r=enviar(d,leitura(d[-1],'final',10),key)
    assert r.status_code==409
    assert d[1].scalar(select(ChaveIdempotencia.id).where(ChaveIdempotencia.chave==key)) is None


@pytest.mark.parametrize('alteracao',['uid','epoch','intervalo','origem','aparelho','antes'])
def test_leitura_incompativel_nao_e_atribuida_a_sessao(ambiente,alteracao):
    d=preparar_ingestao(ambiente);iniciar(d);b=leitura(d[-1])
    campo,valor={'uid':('uid_canonico','FFFFFFFFFFFFFFFF'),'epoch':('epoch_inicio_etiqueta',1800000000),
        'intervalo':('intervalo_relatado_s',300),'origem':('origem','declaracao_android'),
        'aparelho':('dispositivo_id',str(uuid4())),'antes':('lida_em','2026-09-11T00:00:00Z')}[alteracao]
    b[campo]=valor;assert enviar(d,b).status_code in (404,409)
    assert d[1].scalar(select(func.count()).select_from(LeituraEtiqueta).where(LeituraEtiqueta.sessao_id==UUID(d[-1]['sessao_id'])))==1


def test_dois_clientes_e_plataforma_nao_leem_nem_enviam(ambiente):
    d=preparar_ingestao(ambiente);a=iniciar(d);c=d[0];sid=d[-1]['sessao_id']
    other=headers(ambiente[3]('b'));staff=headers(ambiente[3]('staff'))
    for h,status in [(other,404),(staff,403)]:
        for path in [f'/sessoes/{sid}/leituras',f'/sessoes/{sid}/integridade',f"/sessoes/{sid}/leituras/{a.json()['leitura_id']}"]:
            assert c.get('/api/v1'+path,headers=h).status_code==status
        assert enviar(d,leitura(d[-1]),h=h).status_code==status
    assert c.get('/api/v1/etiquetas/localizar',params={'uid':d[5].nfc_uid},headers=other).status_code==404


def test_operador_da_mesma_empresa_registra_novo_evento_com_autoria_propria(ambiente):
    d=preparar_ingestao(ambiente);iniciar(d);h=headers(ambiente[3]('op'));b=leitura(d[-1])
    a=enviar(d,b,h=h);assert a.status_code==201,a.text
    assert enviar(d,b).status_code==409
    assert d[1].get(LeituraEtiqueta,UUID(a.json()['leitura_id'])).lida_por==d[2]['op'].id


@pytest.mark.parametrize('campo,valor',[('intervalo_segundos',300),('min_configurado_c','1.00'),('max_configurado_c','9.00')])
def test_inicio_usa_criterio_preservado(ambiente,campo,valor):
    d=preparar_ingestao(ambiente);d[-1][campo]=valor
    assert iniciar(d).status_code==409
    assert d[1].scalar(select(func.count()).select_from(SessaoMonitoramento).where(SessaoMonitoramento.id==UUID(d[-1]['sessao_id'])))==0


def test_nao_reutiliza_volume_ou_etiqueta_nem_cancela_carga_ativa(ambiente):
    d=preparar_ingestao(ambiente);assert iniciar(d).status_code==201
    d[-1]['sessao_id']=str(uuid4());d[-1]['evidencia']['evento_id']=str(uuid4())
    assert iniciar(d).status_code==409
    c,db,users,h,r,tag,body=d
    assert post(c,f"/remessas/{r['id']}/cancelar",h,{'motivo':'Teste de cancelamento inválido.'}).status_code==422


@pytest.mark.parametrize('mutacao',['sem_fuso','nan','confirmacao_inteiro','extra_usuario','raw_grande','intervalo_fracionado'])
def test_payload_invalido_recusado(ambiente,mutacao):
    d=preparar_ingestao(ambiente);b=d[-1]
    if mutacao=='sem_fuso':b['evidencia']['lida_em']='2026-09-12T12:00:00'
    elif mutacao=='nan':b['min_configurado_c']='NaN'
    elif mutacao=='confirmacao_inteiro':b['ativacao_confirmada']=1
    elif mutacao=='extra_usuario':b['usuario_id']=str(d[2]['b'].id)
    elif mutacao=='raw_grande':b['evidencia']['resposta_bruta']=['x'*131072]*3
    else:b['intervalo_segundos']=600.5
    assert iniciar(d).status_code==422


def test_paginacao_por_ordem_nao_repete_leituras(ambiente):
    d=preparar_ingestao(ambiente);iniciar(d);enviar(d,leitura(d[-1]));enviar(d,leitura(d[-1],minutos=20))
    r=d[0].get(f"/api/v1/sessoes/{d[-1]['sessao_id']}/leituras",params={'apos_ordem':1,'limite':1},headers=d[3])
    assert r.status_code==200 and [x['ordem_recebimento'] for x in r.json()]==[2]


def test_banco_recusa_alterar_e_apagar_evidencia(ambiente):
    d=preparar_ingestao(ambiente);r=iniciar(d);assert r.status_code==201,r.text
    for sql in ['UPDATE leitura_etiqueta SET resposta_bruta=\'[]\'::jsonb','DELETE FROM leitura_etiqueta']:
        with pytest.raises(DBAPIError,match='append-only'):
            with d[1].begin_nested():d[1].execute(text(sql))


def test_verificador_detecta_adulteracao_mesmo_fora_do_gatilho(ambiente):
    d=preparar_ingestao(ambiente);iniciar(d)
    # Apenas o dono do banco de TESTE desliga o gatilho para comprovar a detecção.
    d[1].execute(text('ALTER TABLE leitura_etiqueta DISABLE TRIGGER leitura_etiqueta_imutavel'))
    d[1].execute(text("UPDATE leitura_etiqueta SET resposta_bruta='[\"ADULTERADA\"]'::jsonb"))
    d[1].execute(text('ALTER TABLE leitura_etiqueta ENABLE TRIGGER leitura_etiqueta_imutavel'))
    d[1].expire_all()
    r=d[0].get(f"/api/v1/sessoes/{d[-1]['sessao_id']}/integridade",headers=d[3])
    assert not r.json()['integra'] and len(r.json()['leituras_com_problema'])==1


def test_rls_filtra_sessoes_evidencias_etiquetas_aparelhos_sem_where(ambiente):
    a=preparar_ingestao(ambiente);ra=iniciar(a);assert ra.status_code==201,ra.text
    b=preparar_ingestao(ambiente,'b');rb=iniciar(b);assert rb.status_code==201,rb.text
    db=a[1];role='tt_ingest_'+uuid4().hex
    db.execute(text(f'CREATE ROLE {role} NOLOGIN NOSUPERUSER NOBYPASSRLS'))
    db.execute(text(f'GRANT SELECT,INSERT,UPDATE ON ALL TABLES IN SCHEMA public TO {role}'))
    db.execute(text(f'SET LOCAL ROLE {role}'))
    db.execute(text("SELECT set_config('app.empresa_id',:e,true),set_config('app.usuario_id',:u,true)"),
               {'e':str(a[2]['a'].empresa_id),'u':str(a[2]['a'].id)})
    for table in ['etiqueta','dispositivo','vinculo_etiqueta','sessao_monitoramento','leitura_etiqueta']:
        assert len(db.execute(text(f'SELECT id FROM {table}')).all())==1
    with pytest.raises(DBAPIError,match='row-level security'):
        with db.begin_nested():
            db.execute(text('INSERT INTO vinculo_etiqueta (volume_id,etiqueta_id) VALUES (:v,:e)'),
                       {'v':a[4]['volumes'][1]['id'],'e':b[5].id})
    db.execute(text("SELECT set_config('app.empresa_id','',true)"))
    for table in ['etiqueta','dispositivo','vinculo_etiqueta','sessao_monitoramento','leitura_etiqueta']:
        assert db.execute(text(f'SELECT id FROM {table}')).all()==[]
    db.execute(text('RESET ROLE'))


def test_fluxo_http_com_permissoes_minimas_e_autoria_restrita(ambiente):
    from pathlib import Path
    d=preparar_ingestao(ambiente);db=d[1];role='tt_min_ingest_'+uuid4().hex
    db.execute(text(f'CREATE ROLE {role} NOLOGIN NOSUPERUSER NOBYPASSRLS'))
    for linha in (Path(__file__).parents[1]/'scripts/runtime-permissoes.sql').read_text(encoding='utf-8').splitlines():
        if linha.startswith('GRANT '):db.execute(text(linha.replace('thermotrace_runtime',role)))
    db.execute(text(f'SET LOCAL ROLE {role}'))
    r=iniciar(d);assert r.status_code==201,r.text
    h=headers(ambiente[3]('op'))
    a=enviar(d,leitura(d[-1],'final',20),h=h);assert a.status_code==201,a.text
    with pytest.raises(DBAPIError,match='row-level security'):
        with db.begin_nested():
            db.execute(text('INSERT INTO dispositivo (empresa_id,install_id,registrado_por) VALUES (:e,:i,:u)'),
                {'e':d[2]['a'].empresa_id,'i':str(uuid4()),'u':d[2]['a'].id}) # contexto atual é operador
    with pytest.raises(DBAPIError):
        with db.begin_nested():db.execute(text('DELETE FROM leitura_etiqueta'))
    with pytest.raises(DBAPIError,match='imutável'):
        with db.begin_nested():db.execute(text("UPDATE sessao_monitoramento SET status='ativa',encerrada_em=NULL"))
    db.execute(text('RESET ROLE'))


def test_corpo_excessivo_recusado_antes_da_validacao(ambiente):
    c=ambiente[0]
    r=c.post('/api/v1/dispositivos',content=b'x'*1048577,headers={'Content-Type':'application/json'})
    assert r.status_code==413 and r.json()['erro']=='corpo_muito_grande'


def test_limite_conta_blocos_sem_confiar_no_cabecalho():
    import asyncio
    from app.core.limite_corpo import LimitarCorpo
    async def ensaio():
        mensagens=iter([{'type':'http.request','body':b'1234','more_body':True},
                        {'type':'http.request','body':b'5678','more_body':False}])
        respostas=[]
        async def receber():return next(mensagens)
        async def enviar(m):respostas.append(m)
        async def interno(*args):raise AssertionError('Não deveria chegar ao parser')
        await LimitarCorpo(interno,limite=7)({'type':'http','method':'POST','headers':[(b'content-length',b'1')]},receber,enviar)
        assert respostas[0]['status']==413
    asyncio.run(ensaio())


def test_catalogo_local_exige_autoria_admin_e_nao_transfere_uid(ambiente):
    from app.catalogo_cli import CadastroEtiqueta, cadastrar
    from app.core.errors import RegraDeNegocio, Conflito
    d=preparar_ingestao(ambiente);db=d[1]
    dados=CadastroEtiqueta(empresa_id=d[2]['a'].empresa_id,lote_id=d[5].lote_id,
                           serial='NOVO-'+uuid4().hex,qr_payload='NOVO:'+uuid4().hex,uid_canonico=uuid4().hex[:16].upper())
    with pytest.raises(RegraDeNegocio):cadastrar(db,d[2]['op'].id,dados)
    id_=cadastrar(db,d[2]['staff'].id,dados)
    assert db.get(Etiqueta,id_).empresa_id==d[2]['a'].empresa_id
    with pytest.raises(Conflito):cadastrar(db,d[2]['staff'].id,dados.model_copy(update={'empresa_id':d[2]['b'].empresa_id}))


def test_falha_apos_escrita_nao_deixa_sessao_leitura_ou_recibo(ambiente,monkeypatch):
    from app.services import ingestao
    d=preparar_ingestao(ambiente);key=str(uuid4())
    original=ingestao.auditar
    def falhar(*args,**kwargs):raise RuntimeError('Falha sintética antes do commit')
    monkeypatch.setattr(ingestao,'auditar',falhar)
    with pytest.raises(RuntimeError,match='Falha sintética'):iniciar(d,key)
    for model,condicao in [(LeituraEtiqueta,LeituraEtiqueta.sessao_id==UUID(d[-1]['sessao_id'])),
                           (SessaoMonitoramento,SessaoMonitoramento.id==UUID(d[-1]['sessao_id'])),
                           (VinculoEtiqueta,VinculoEtiqueta.volume_id==UUID(d[4]['volumes'][0]['id']))]:
        assert d[1].scalar(select(func.count()).select_from(model).where(condicao))==0
    assert d[1].scalar(select(ChaveIdempotencia.id).where(ChaveIdempotencia.chave==key)) is None
    monkeypatch.setattr(ingestao,'auditar',original)
    assert iniciar(d,key).status_code==201


def test_outro_operador_descobre_sessao_pelo_volume(ambiente):
    d=preparar_ingestao(ambiente);iniciar(d)
    path=f"/api/v1/volumes/{d[4]['volumes'][0]['id']}/sessoes"
    h=headers(ambiente[3]('op'));r=d[0].get(path,headers=h)
    assert r.status_code==200,r.text
    assert r.json()[0]['id']==d[-1]['sessao_id'] and r.json()[0]['origem']=='simulacao'
    assert r.json()[0]['stop_fisico']=='nao_confirmado_pelo_servidor'
    assert d[0].get(path,headers=headers(ambiente[3]('b'))).status_code==404

