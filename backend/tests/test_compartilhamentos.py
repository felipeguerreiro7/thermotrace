"""WEB-10: dono A compartilha só a carga X com a transportadora T; B e Y seguem invisíveis.

Aceite registrado nas notas: cliente A compartilha somente carga X com
transportadora T; B e carga Y permanecem invisíveis; revogação bloqueia novas
consultas/exportações imediatamente e é auditada.
"""
import copy
from datetime import timedelta
from uuid import uuid4, UUID

import pytest
from sqlalchemy import select, text
from sqlalchemy.exc import DBAPIError

from app.core.security import agora, hasher
from app.models import CompartilhamentoRemessa, Empresa, LogAuditoria, Usuario
from app.models.enums import PapelUsuario, TipoEmpresa
from tests.test_acesso_perfis import ambiente, headers, SENHA
from tests.test_cargas import post, remessa, preparar
from tests.test_temperaturas import preparar_grafico

MOTIVO = 'Encerrado no teste sintético de compartilhamento.'


def transportadora(ambiente):
    c, db, users, entrar = ambiente
    emp = Empresa(razao_social='Transportadora sintética', cnpj=str(uuid4().int)[:14], tipo=TipoEmpresa.TRANSPORTADORA)
    db.add(emp); db.flush()
    ph = hasher.hash(SENHA)
    for nome, papel in [('t', PapelUsuario.GESTOR), ('t_op', PapelUsuario.OPERADOR)]:
        u = Usuario(empresa_id=emp.id, nome=nome, email=f'{nome}-{uuid4().hex}@example.org', senha_hash=ph, papel=papel)
        db.add(u); db.flush(); users[nome] = u
    db.commit()
    return emp


def cenario(ambiente, exportar=False):
    """A: carga X (com gráfico) e Y. B: carga própria. T: transportadora convidada para X."""
    d, grafico, recibo = preparar_grafico(ambiente)
    c, db, users, ha, x = d[0], d[1], d[2], d[3], d[4]
    y = remessa(c, ha, {'id': x['produto_configuracao_id']}, 'CARGA-Y')
    hb = headers(ambiente[3]('b')); _, pb = preparar(c, hb); z = remessa(c, hb, pb, 'CARGA-B')
    t = transportadora(ambiente)
    ht, hop = headers(ambiente[3]('t')), headers(ambiente[3]('t_op'))
    convite = post(c, f"/remessas/{x['id']}/compartilhamentos", ha, {'cnpj_parceira': t.cnpj, 'pode_exportar': exportar})
    assert convite.status_code == 201, convite.text
    return dict(c=c, db=db, users=users, ha=ha, hb=hb, ht=ht, hop=hop, x=x, y=y, z=z, t=t,
                grafico=grafico, recibo=recibo, sessao=d[-1]['sessao_id'], body=d[-1], comp=convite.json())


def get(s, path, h):
    return s['c'].get('/api/v1' + path, headers=h)


def aceitar(s):
    r = post(s['c'], f"/compartilhamentos/{s['comp']['id']}/aceitar", s['ht'], {})
    assert r.status_code == 200, r.text
    return r.json()


def test_criterio_de_aceite_tres_empresas_e_revogacao_imediata(ambiente):
    s = cenario(ambiente)
    x, y, z, ht, hop = s['x'], s['y'], s['z'], s['ht'], s['hop']
    assert s['comp']['situacao'] == 'pendente' and s['comp']['escopo'] == ['consultar']

    # Antes do aceite: convite visível ao gestor, sem dados da carga; nada legível.
    pend = get(s, '/compartilhamentos/recebidos', ht).json()
    assert [p['id'] for p in pend] == [s['comp']['id']] and pend[0]['remessa'] is None
    assert pend[0]['remessa_codigo'] == x['codigo']
    assert get(s, '/compartilhamentos/recebidos', hop).json() == []
    assert get(s, f"/remessas/{x['id']}", ht).status_code == 404

    ativo = aceitar(s)
    assert ativo['situacao'] == 'ativo' and ativo['remessa']['id'] == x['id']
    assert [p['remessa']['id'] for p in get(s, '/compartilhamentos/recebidos', hop).json()] == [x['id']]

    # Somente X, por concessão; Y do mesmo dono e Z de B continuam invisíveis.
    for h in (ht, hop):
        assert get(s, f"/remessas/{x['id']}", h).json()['id'] == x['id']
        assert get(s, f"/remessas/{y['id']}", h).status_code == 404
        assert get(s, f"/remessas/{z['id']}", h).status_code == 404
        assert get(s, '/remessas', h).json() == []  # lista própria não mistura cargas concedidas
        assert get(s, '/remessas/localizar?valor=' + x['codigo'], h).json()['candidatas'] == []
        assert get(s, s['grafico'].removeprefix('/api/v1'), h).status_code == 200
        assert get(s, s['grafico'].removeprefix('/api/v1') + '?formato=csv', h).status_code == 403
    assert get(s, f"/volumes/{x['volumes'][0]['id']}/sessoes", hop).json()[0]['empresa_id'] == x['empresa_id']
    assert len(get(s, f"/sessoes/{s['sessao']}/leituras", hop).json()) == 2
    assert get(s, f"/sessoes/{s['sessao']}/integridade", hop).json()['integra'] is True
    # Evidência bruta continua restrita ao dono.
    assert get(s, f"/sessoes/{s['sessao']}/leituras/{s['recibo']['leitura_id']}", hop).status_code == 404
    assert get(s, f"/remessas/{x['id']}", s['hb']).status_code == 404

    # Leitura não vira operação: documento, cancelamento e início de sessão recusados.
    assert post(s['c'], f"/remessas/{x['id']}/documentos", hop, {'tipo': 'pedido', 'numero': 'INVASAO'}).status_code == 404
    assert post(s['c'], f"/remessas/{x['id']}/cancelar", ht, {'motivo': MOTIVO}).status_code == 404
    corpo = copy.deepcopy(s['body']); corpo['sessao_id'] = str(uuid4()); corpo['evidencia']['evento_id'] = str(uuid4())
    assert post(s['c'], f"/volumes/{x['volumes'][1]['id']}/sessoes", hop, corpo).status_code == 404

    # Dono enxerga quem consultou a sua carga.
    db = s['db']
    espelho = db.scalars(select(LogAuditoria).where(LogAuditoria.empresa_id == UUID(x['empresa_id']),
        LogAuditoria.acao == 'temperaturas_consultadas', LogAuditoria.usuario_id == s['users']['t_op'].id)).all()
    assert espelho and espelho[0].depois['empresa_acesso_id'] == str(s['t'].id)
    assert espelho[0].depois['via_compartilhamento'] == s['comp']['id']

    # Revogação pelo dono: efeito na requisição seguinte, registrada nos dois lados.
    rev = post(s['c'], f"/compartilhamentos/{s['comp']['id']}/revogar", s['ha'], {'motivo': MOTIVO})
    assert rev.status_code == 200 and rev.json()['situacao'] == 'revogado'
    for h in (ht, hop):
        assert get(s, f"/remessas/{x['id']}", h).status_code == 404
        assert get(s, s['grafico'].removeprefix('/api/v1'), h).status_code == 404
        assert get(s, f"/sessoes/{s['sessao']}/leituras", h).status_code == 404
    assert get(s, '/compartilhamentos/recebidos', hop).json() == []
    logs = db.scalars(select(LogAuditoria).where(LogAuditoria.acao == 'compartilhamento_revogado',
                                                 LogAuditoria.entidade_id == UUID(s['comp']['id']))).all()
    assert {l.empresa_id for l in logs} == {UUID(x['empresa_id']), s['t'].id}
    assert get(s, f"/remessas/{x['id']}", s['ha']).status_code == 200  # dono não perde nada


def test_exportacao_somente_com_escopo_e_auditada_para_o_dono(ambiente):
    s = cenario(ambiente, exportar=True); aceitar(s)
    path = s['grafico'].removeprefix('/api/v1')
    for formato in ('csv', 'svg', 'html'):
        r = get(s, path + '?formato=' + formato, s['hop'])
        assert r.status_code == 200 and 'attachment' in r.headers['content-disposition']
    logs = s['db'].scalars(select(LogAuditoria).where(LogAuditoria.acao == 'temperaturas_exportadas',
        LogAuditoria.empresa_id == UUID(s['x']['empresa_id']), LogAuditoria.usuario_id == s['users']['t_op'].id)).all()
    assert len(logs) == 3 and all(l.depois['sha256_arquivo'] for l in logs)


def test_decisoes_cabem_a_cada_parte(ambiente):
    s = cenario(ambiente)
    cid = s['comp']['id']
    assert post(s['c'], f'/compartilhamentos/{cid}/aceitar', s['hop'], {}).status_code == 403  # operador
    assert post(s['c'], f'/compartilhamentos/{cid}/aceitar', s['ha'], {}).status_code == 403   # dono
    assert post(s['c'], f'/compartilhamentos/{cid}/aceitar', s['hb'], {}).status_code == 404   # terceiro
    assert post(s['c'], f'/compartilhamentos/{cid}/revogar', s['hb'], {'motivo': MOTIVO}).status_code == 404
    assert post(s['c'], f"/remessas/{s['x']['id']}/compartilhamentos", s['ht'],
                {'cnpj_parceira': s['t'].cnpj}).status_code == 404  # parceira não recompartilha
    # Convite repetido enquanto pendente é conflito; tipo errado e CNPJ desconhecido têm a mesma resposta.
    assert post(s['c'], f"/remessas/{s['x']['id']}/compartilhamentos", s['ha'],
                {'cnpj_parceira': s['t'].cnpj, 'pode_exportar': True}).status_code == 409
    cnpj_b = s['db'].get(Empresa, s['users']['b'].empresa_id).cnpj
    r1 = post(s['c'], f"/remessas/{s['x']['id']}/compartilhamentos", s['ha'], {'cnpj_parceira': cnpj_b})
    r2 = post(s['c'], f"/remessas/{s['x']['id']}/compartilhamentos", s['ha'], {'cnpj_parceira': '99999999999999'})
    assert r1.status_code == r2.status_code == 422 and r1.json()['mensagem'] == r2.json()['mensagem']

    rec = post(s['c'], f'/compartilhamentos/{cid}/recusar', s['ht'], {})
    assert rec.status_code == 200 and rec.json()['situacao'] == 'recusado'
    assert post(s['c'], f'/compartilhamentos/{cid}/aceitar', s['ht'], {}).status_code == 422
    assert get(s, f"/remessas/{s['x']['id']}", s['ht']).status_code == 404
    # Recusa encerra o convite; o dono pode convidar de novo, com nova decisão.
    novo = post(s['c'], f"/remessas/{s['x']['id']}/compartilhamentos", s['ha'], {'cnpj_parceira': s['t'].cnpj})
    assert novo.status_code == 201 and novo.json()['id'] != cid
    hist = get(s, f"/remessas/{s['x']['id']}/compartilhamentos", s['ha']).json()
    assert sorted(h['situacao'] for h in hist) == ['pendente', 'recusado']
    assert get(s, f"/remessas/{s['x']['id']}/compartilhamentos", s['ht']).status_code == 404

    # Parceira também pode sair de um compartilhamento ativo.
    s['comp'] = novo.json(); aceitar(s)
    sair = post(s['c'], f"/compartilhamentos/{s['comp']['id']}/revogar", s['ht'], {'motivo': MOTIVO})
    assert sair.status_code == 200 and sair.json()['situacao'] == 'revogado'
    assert get(s, f"/remessas/{s['x']['id']}", s['ht']).status_code == 404


def test_prazo_vencido_bloqueia_e_permite_novo_convite(ambiente):
    s = cenario(ambiente)
    db = s['db']
    comp = db.get(CompartilhamentoRemessa, UUID(s['comp']['id']))
    db.execute(text("SELECT set_config('app.empresa_id', :e, true)"), {'e': str(comp.empresa_dona_id)})
    comp.revogado_em = agora(); comp.revogado_por = s['users']['a'].id; comp.motivo_revogacao = MOTIVO
    db.flush()
    vencido = CompartilhamentoRemessa(remessa_id=comp.remessa_id, remessa_codigo=comp.remessa_codigo,
        empresa_dona_id=comp.empresa_dona_id, empresa_parceira_id=comp.empresa_parceira_id,
        criado_por=comp.criado_por, criado_em=agora()-timedelta(hours=2), expira_em=agora()-timedelta(hours=1),
        aceito_em=agora()-timedelta(hours=2), aceito_por=s['users']['t'].id)
    db.add(vencido); db.commit()
    assert get(s, f"/remessas/{s['x']['id']}", s['ht']).status_code == 404
    lista = get(s, '/compartilhamentos/recebidos?situacao=expirado', s['ht']).json()
    assert [l['id'] for l in lista] == [str(vencido.id)] and lista[0]['remessa'] is None
    futuro = (agora()+timedelta(days=3)).isoformat()
    novo = post(s['c'], f"/remessas/{s['x']['id']}/compartilhamentos", s['ha'],
                {'cnpj_parceira': s['t'].cnpj, 'expira_em': futuro})
    assert novo.status_code == 201, novo.text
    db.refresh(vencido)
    assert vencido.revogado_em is not None and vencido.motivo_revogacao == 'Substituído após o prazo.'
    passado = (agora()-timedelta(minutes=1)).isoformat()
    assert post(s['c'], f"/remessas/{s['y']['id']}/compartilhamentos", s['ha'],
                {'cnpj_parceira': s['t'].cnpj, 'expira_em': passado}).status_code == 422


def _papel_sem_privilegio(db, empresa, usuario):
    role = 'tt_comp_' + uuid4().hex
    db.execute(text(f'CREATE ROLE {role} NOLOGIN NOSUPERUSER NOBYPASSRLS'))
    db.execute(text(f'GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO {role}'))
    db.execute(text(f'SET LOCAL ROLE {role}'))
    db.execute(text("SELECT set_config('app.empresa_id',:e,true),set_config('app.usuario_id',:u,true)"),
               {'e': str(empresa), 'u': str(usuario)})


def test_rls_parceira_le_sem_where_e_nao_escreve(ambiente):
    s = cenario(ambiente); aceitar(s)
    db, x, t = s['db'], s['x'], s['t']
    _papel_sem_privilegio(db, t.id, s['users']['t'].id)
    assert [str(v) for v in db.scalars(text('SELECT id FROM remessa'))] == [x['id']]
    assert len(db.execute(text('SELECT id FROM volume')).all()) == 2
    assert len(db.execute(text('SELECT id FROM documento')).all()) == 1
    for tabela in ('vinculo_etiqueta', 'sessao_monitoramento'):
        assert len(db.execute(text(f'SELECT id FROM {tabela}')).all()) == 1
    assert len(db.execute(text('SELECT id FROM leitura_etiqueta')).all()) == 2
    assert db.execute(text('SELECT id FROM etiqueta')).all() == []
    assert db.execute(text('SELECT id FROM perfil_termico')).all() == []
    # Visível não é gravável.
    assert db.execute(text("UPDATE remessa SET status='cancelada' WHERE id=:id"), {'id': x['id']}).rowcount == 0
    assert db.execute(text("UPDATE volume SET descricao='invasão' WHERE remessa_id=:id"), {'id': x['id']}).rowcount == 0
    with pytest.raises(DBAPIError, match='row-level security'):
        with db.begin_nested():
            db.execute(text("INSERT INTO documento (remessa_id,tipo,numero) VALUES (:id,'pedido','INVASAO')"), {'id': x['id']})
    with pytest.raises(DBAPIError, match='row-level security'):
        with db.begin_nested():
            db.execute(text('INSERT INTO compartilhamento_remessa (remessa_id,remessa_codigo,empresa_dona_id,empresa_parceira_id,criado_por) '
                            'VALUES (:r,:c,:d,:p,:u)'), {'r': x['id'], 'c': x['codigo'], 'd': x['empresa_id'],
                                                         'p': t.id, 'u': s['users']['t'].id})
    with pytest.raises(DBAPIError, match='imutável'):
        with db.begin_nested():
            db.execute(text('UPDATE compartilhamento_remessa SET pode_exportar=true'))
    # Sem política de DELETE: nenhuma linha alcançável. O gatilho cobre quem contorna o RLS.
    assert db.execute(text('DELETE FROM compartilhamento_remessa')).rowcount == 0
    db.execute(text('RESET ROLE'))
    with pytest.raises(DBAPIError, match='apagado'):
        with db.begin_nested():
            db.execute(text('DELETE FROM compartilhamento_remessa WHERE id=:id'), {'id': s['comp']['id']})
    _papel_sem_privilegio(db, t.id, s['users']['t'].id)
    # Revogada no banco, a leitura some na mesma transação, sem filtro na consulta.
    db.execute(text('UPDATE compartilhamento_remessa SET revogado_em=now(), revogado_por=:u, motivo_revogacao=:m'),
               {'u': s['users']['t'].id, 'm': MOTIVO})
    for tabela in ('remessa', 'volume', 'documento', 'vinculo_etiqueta', 'sessao_monitoramento', 'leitura_etiqueta'):
        assert db.execute(text(f'SELECT id FROM {tabela}')).all() == [], tabela
    with pytest.raises(DBAPIError, match='imutável'):
        with db.begin_nested():
            db.execute(text('UPDATE compartilhamento_remessa SET revogado_em=NULL, revogado_por=NULL, motivo_revogacao=NULL'))
    db.execute(text('RESET ROLE'))


def test_fluxo_http_com_papel_runtime_minimo(ambiente):
    from pathlib import Path
    s = cenario(ambiente)
    db = s['db']; role = 'tt_min_comp_' + uuid4().hex
    db.execute(text(f'CREATE ROLE {role} NOLOGIN NOSUPERUSER NOBYPASSRLS'))
    for linha in (Path(__file__).parents[1] / 'scripts/runtime-permissoes.sql').read_text(encoding='utf-8').splitlines():
        if linha.startswith('GRANT '):
            db.execute(text(linha.replace('thermotrace_runtime', role)))
    db.execute(text(f'SET LOCAL ROLE {role}'))
    aceitar(s)
    assert get(s, f"/remessas/{s['x']['id']}", s['hop']).status_code == 200
    assert get(s, s['grafico'].removeprefix('/api/v1'), s['hop']).status_code == 200
    assert get(s, f"/remessas/{s['y']['id']}", s['hop']).status_code == 404
    rev = post(s['c'], f"/compartilhamentos/{s['comp']['id']}/revogar", s['ha'], {'motivo': MOTIVO})
    assert rev.status_code == 200, rev.text
    assert get(s, f"/remessas/{s['x']['id']}", s['hop']).status_code == 404
    db.execute(text('RESET ROLE'))


def test_rls_terceiro_nao_ve_convite_nem_carga(ambiente):
    s = cenario(ambiente); aceitar(s)
    db = s['db']
    _papel_sem_privilegio(db, s['users']['b'].empresa_id, s['users']['b'].id)
    assert db.execute(text('SELECT id FROM compartilhamento_remessa')).all() == []
    assert {str(v) for v in db.scalars(text('SELECT id FROM remessa'))} == {s['z']['id']}
    assert db.execute(text('UPDATE compartilhamento_remessa SET revogado_em=now()')).rowcount == 0
    db.execute(text('RESET ROLE'))


def test_dono_so_escreve_na_propria_carga_sob_rls(ambiente):
    """As políticas de escrita agora citam o dono; a visibilidade concedida não as amplia."""
    s = cenario(ambiente); aceitar(s)
    db = s['db']
    _papel_sem_privilegio(db, s['users']['a'].empresa_id, s['users']['a'].id)
    db.execute(text("INSERT INTO documento (remessa_id,tipo,numero) VALUES (:id,'pedido','PED-DONO')"), {'id': s['x']['id']})
    with pytest.raises(DBAPIError, match='row-level security'):
        with db.begin_nested():
            db.execute(text("INSERT INTO documento (remessa_id,tipo,numero) VALUES (:id,'pedido','INVASAO')"), {'id': s['z']['id']})
    db.execute(text('RESET ROLE'))
