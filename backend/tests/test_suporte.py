"""TT-051: a equipe ThermoTrace só vê a carga que o dono liberou, pelo prazo e só para consultar.

Aceite: sem liberação a equipe não alcança carga nenhuma; o gestor do dono libera
uma carga com motivo e prazo de até 72 h; alguém da equipe assume (fica o autor);
a consulta é auditada na empresa do dono; exportar e operar continuam proibidos;
o encerramento corta o acesso na requisição seguinte.
"""
from uuid import UUID

import pytest
from sqlalchemy import select, text
from sqlalchemy.exc import DBAPIError

from app.models import CompartilhamentoRemessa, LogAuditoria
from tests.test_acesso_perfis import ambiente, headers  # noqa: F401
from tests.test_cargas import post
from tests.test_compartilhamentos import MOTIVO, _papel_sem_privilegio, cenario, get

CHAMADO = 'Chamado 1234: coleta do checkpoint não aparece no portal.'


def liberar(s, horas=24, h=None):
    return post(s['c'], f"/remessas/{s['x']['id']}/suporte", h or s['ha'], {'motivo': CHAMADO, 'horas': horas})


def staff(ambiente):  # noqa: F811
    return headers(ambiente[3]('staff'))


def test_equipe_so_le_a_carga_liberada_e_tudo_fica_na_auditoria_do_dono(ambiente):  # noqa: F811
    s = cenario(ambiente)
    hs, x, db = staff(ambiente), s['x'], s['db']
    grafico = s['grafico'].removeprefix('/api/v1')

    # Sem liberação: nada.
    assert get(s, f"/remessas/{x['id']}", hs).status_code == 404
    assert get(s, grafico, hs).status_code == 404
    assert get(s, '/plataforma/suporte', hs).json() == []

    r = liberar(s)
    assert r.status_code == 201, r.text
    pedido = r.json()
    assert pedido['papel'] == 'suporte' and pedido['situacao'] == 'pendente'
    assert pedido['escopo'] == ['consultar'] and pedido['motivo_concessao'] == CHAMADO

    # Pendente: a equipe vê o pedido e o motivo, mas não os dados da carga.
    fila = get(s, '/plataforma/suporte', hs).json()
    assert [p['id'] for p in fila] == [pedido['id']] and fila[0]['remessa'] is None
    assert get(s, f"/remessas/{x['id']}", hs).status_code == 404

    assumido = post(s['c'], f"/plataforma/suporte/{pedido['id']}/assumir", hs, {})
    assert assumido.status_code == 200, assumido.text
    assert assumido.json()['situacao'] == 'ativo'
    linha = db.get(CompartilhamentoRemessa, UUID(pedido['id']))
    assert linha.aceito_por == s['users']['staff'].id  # fica quem assumiu

    # Só a carga liberada, só leitura.
    assert get(s, f"/remessas/{x['id']}", hs).json()['id'] == x['id']
    assert get(s, grafico, hs).status_code == 200
    assert get(s, f"/sessoes/{s['sessao']}/viagem", hs).status_code == 200
    assert get(s, f"/sessoes/{s['sessao']}/integridade", hs).json()['integra'] is True
    assert get(s, f"/volumes/{x['volumes'][0]['id']}/sessoes", hs).status_code == 200
    for formato in ('csv', 'svg', 'html'):
        assert get(s, f"{grafico}?formato={formato}", hs).status_code == 403
        assert get(s, f"/sessoes/{s['sessao']}/viagem?formato={formato}", hs).status_code == 403
    assert get(s, f"/remessas/{s['y']['id']}", hs).status_code == 404
    assert get(s, f"/remessas/{s['z']['id']}", hs).status_code == 404
    assert get(s, '/remessas', hs).status_code == 403  # lista de cargas segue fechada para a equipe
    assert post(s['c'], f"/remessas/{x['id']}/documentos", hs, {'tipo': 'pedido', 'numero': 'SUPORTE'}).status_code == 403

    # O dono vê a liberação e cada consulta, com a empresa e a pessoa da equipe.
    staff_id, plataforma_id = s['users']['staff'].id, s['users']['staff'].empresa_id
    logs = db.scalars(select(LogAuditoria).where(LogAuditoria.empresa_id == UUID(x['empresa_id']),
                                                 LogAuditoria.usuario_id == staff_id)).all()
    acoes = {l.acao for l in logs}
    assert {'compartilhamento_aceito', 'remessa_consultada', 'temperaturas_consultadas', 'viagem_consultada'} <= acoes
    assert all(l.depois.get('empresa_acesso_id') == str(plataforma_id) for l in logs if l.acao.endswith('consultada'))
    concedido = db.scalars(select(LogAuditoria).where(LogAuditoria.acao == 'suporte_concedido',
                                                      LogAuditoria.entidade_id == UUID(pedido['id']))).all()
    assert {l.empresa_id for l in concedido} == {UUID(x['empresa_id']), plataforma_id}
    assert concedido[0].depois['motivo'] == CHAMADO

    # O dono encerra pela revogação comum; vale na requisição seguinte.
    rev = post(s['c'], f"/compartilhamentos/{pedido['id']}/revogar", s['ha'], {'motivo': MOTIVO})
    assert rev.status_code == 200 and rev.json()['situacao'] == 'revogado'
    assert get(s, f"/remessas/{x['id']}", hs).status_code == 404
    assert get(s, grafico, hs).status_code == 404
    assert get(s, '/plataforma/suporte', hs).json() == []


def test_regras_da_liberacao(ambiente):  # noqa: F811
    s = cenario(ambiente)
    hs, hop = staff(ambiente), headers(ambiente[3]('op'))
    assert liberar(s, horas=73).status_code == 422
    assert post(s['c'], f"/remessas/{s['x']['id']}/suporte", s['ha'], {'motivo': 'curto', 'horas': 4}).status_code == 422
    assert liberar(s, h=hop).status_code == 403          # operador não libera
    assert liberar(s, h=s['hb']).status_code == 404      # outra empresa não libera carga alheia
    assert liberar(s, h=hs).status_code == 403           # a equipe não se autoconcede
    primeira = liberar(s, horas=72)
    assert primeira.status_code == 201, primeira.text
    assert liberar(s).status_code == 409                 # uma liberação vigente por carga
    # A equipe também encerra (por exemplo, chamado resolvido).
    pid = primeira.json()['id']
    fim = post(s['c'], f"/plataforma/suporte/{pid}/encerrar", hs, {'motivo': 'Chamado resolvido, acesso devolvido.'})
    assert fim.status_code == 200 and fim.json()['situacao'] == 'revogado'
    assert liberar(s).status_code == 201                 # depois de encerrada, pode liberar de novo
    # Suporte não aparece como transportadora: o convite comum recusa o CNPJ da plataforma.
    from app.models import Empresa
    cnpj = s['db'].get(Empresa, s['users']['staff'].empresa_id).cnpj
    assert post(s['c'], f"/remessas/{s['x']['id']}/compartilhamentos", s['ha'], {'cnpj_parceira': cnpj}).status_code == 422


def test_banco_recusa_suporte_que_exporta_opera_ou_dura_demais(ambiente):  # noqa: F811
    s = cenario(ambiente)
    db, x, u = s['db'], s['x'], s['users']['a']
    plataforma = s['users']['staff'].empresa_id
    base = ("INSERT INTO compartilhamento_remessa (remessa_id,remessa_codigo,empresa_dona_id,empresa_parceira_id,"
            "criado_por,papel,pode_exportar,pode_operar,expira_em,motivo_concessao) "
            "VALUES (:r,:c,:d,:p,:u,'suporte',:e,:o,now() + make_interval(hours => :h),:m)")
    valores = dict(r=x['id'], c=x['codigo'], d=x['empresa_id'], p=plataforma, u=u.id, e=False, o=False, h=24, m=CHAMADO)
    for errado in (dict(e=True), dict(o=True), dict(h=100), dict(m='curto')):
        with pytest.raises(DBAPIError, match='suporte_limitado'):
            with db.begin_nested():
                db.execute(text(base), {**valores, **errado})


def test_rls_equipe_sem_liberacao_nao_ve_carga_e_com_liberacao_ve_so_ela(ambiente):  # noqa: F811
    s = cenario(ambiente)
    db, hs = s['db'], staff(ambiente)
    staff_u = s['users']['staff']
    _papel_sem_privilegio(db, staff_u.empresa_id, staff_u.id)
    assert db.execute(text('SELECT id FROM remessa')).all() == []
    assert db.execute(text('SELECT id FROM leitura_etiqueta')).all() == []
    db.execute(text('RESET ROLE'))
    pid = liberar(s).json()['id']
    assert post(s['c'], f"/plataforma/suporte/{pid}/assumir", hs, {}).status_code == 200
    _papel_sem_privilegio(db, staff_u.empresa_id, staff_u.id)
    assert [str(v) for v in db.scalars(text('SELECT id FROM remessa'))] == [s['x']['id']]
    # Visível não é gravável.
    assert db.execute(text("UPDATE remessa SET status='cancelada' WHERE id=:id"), {'id': s['x']['id']}).rowcount == 0
    with pytest.raises(DBAPIError, match='row-level security'):
        with db.begin_nested():
            db.execute(text("INSERT INTO documento (remessa_id,tipo,numero) VALUES (:id,'pedido','SUPORTE')"),
                       {'id': s['x']['id']})
    db.execute(text('RESET ROLE'))


def test_auditoria_recente_primeiro_com_nome_e_empresa_de_quem_consultou(ambiente):  # noqa: F811
    s = cenario(ambiente)
    hs = staff(ambiente)
    pid = liberar(s).json()['id']
    assert post(s['c'], f"/plataforma/suporte/{pid}/assumir", hs, {}).status_code == 200
    assert get(s, f"/remessas/{s['x']['id']}", hs).status_code == 200
    eventos = get(s, '/auditoria?ordem=recentes&limite=5', s['ha']).json()
    assert [e['id'] for e in eventos] == sorted((e['id'] for e in eventos), reverse=True)
    consulta = next(e for e in eventos if e['acao'] == 'remessa_consultada')
    assert consulta['usuario_nome'] == s['users']['staff'].nome and consulta['usuario_empresa'] == 'Teste staff'
    proprio = next(e for e in get(s, '/auditoria?ordem=recentes&limite=100', s['ha']).json() if e['acao'] == 'suporte_concedido')
    assert proprio['usuario_nome'] == s['users']['a'].nome and proprio['usuario_empresa'] is None
    assert 'email' not in str(proprio).lower()
    # Cursor: a página seguinte começa antes do último id visto, sem repetir.
    seguinte = get(s, f"/auditoria?ordem=recentes&limite=5&antes_id={eventos[-1]['id']}", s['ha']).json()
    assert seguinte and max(e['id'] for e in seguinte) < eventos[-1]['id']
