"""Operação entre empresas: a transportadora T bipa na carga X que o dono A compartilhou.

Aceite: com permissão de operar, T inicia monitoramento com etiqueta e aparelho
dela e envia checkpoint e final; A enxerga tudo e a cadeia confere para os dois.
Sem a permissão, T continua só consultando. B e a carga Y seguem intocáveis.
"""
import copy
from datetime import datetime, timezone
from pathlib import Path
from uuid import uuid4, UUID

import pytest
from sqlalchemy import select, text
from sqlalchemy.exc import DBAPIError

from app.models import LeituraEtiqueta, LogAuditoria, SessaoMonitoramento
from tests.test_acesso_perfis import ambiente, headers
from tests.test_cargas import post
from tests.test_compartilhamentos import cenario, aceitar, get, MOTIVO, _papel_sem_privilegio
from tests.test_ingestao import catalogo, leitura


def operando(ambiente, operar=True, exportar=False):
    """Cenário do WEB-10 com um convite novo que inclui (ou não) a permissão de operar."""
    s = cenario(ambiente)
    assert post(s['c'], f"/compartilhamentos/{s['comp']['id']}/recusar", s['ht'], {}).status_code == 200
    convite = post(s['c'], f"/remessas/{s['x']['id']}/compartilhamentos", s['ha'],
                   {'cnpj_parceira': s['t'].cnpj, 'pode_exportar': exportar, 'pode_operar': operar})
    assert convite.status_code == 201, convite.text
    s['comp'] = convite.json()
    return s


def kit_da_transportadora(s):
    """Etiqueta do catálogo de T e aparelho registrado por um operador de T."""
    tag = catalogo(s['db'], s['t'].id)
    d = post(s['c'], '/dispositivos', s['hop'], {'install_id': str(uuid4()), 'modelo': 'Aparelho da transportadora',
                                               'versao_so': 'teste', 'versao_app': '0.9-teste'})
    assert d.status_code == 201, d.text
    t0 = datetime(2026, 9, 12, 13, tzinfo=timezone.utc)
    corpo = {'sessao_id': str(uuid4()), 'etiqueta_id': str(tag.id), 'epoch_inicio_etiqueta': int(t0.timestamp()),
             'intervalo_segundos': 600, 'quantidade_planejada': 100, 'min_configurado_c': '2.00', 'max_configurado_c': '8.00',
             'ativacao_confirmada': True,
             'evidencia': {'evento_id': str(uuid4()), 'dispositivo_id': d.json()['id'], 'lida_em': t0.isoformat(),
                           'uid_canonico': tag.nfc_uid, 'versao_sdk': 'sintetico-1', 'versao_decodificador': 'teste-1',
                           'resposta_bruta': ['ATIVACAO PELA TRANSPORTADORA'], 'origem': 'simulacao'}}
    return tag, d.json()['id'], corpo


def fluxo_da_transportadora(s):
    """T ativa o volume 2 de X, manda checkpoint e final. Devolve o corpo da ativação e os recibos."""
    tag, aparelho, corpo = kit_da_transportadora(s)
    v2 = s['x']['volumes'][1]['id']
    a = post(s['c'], f'/volumes/{v2}/sessoes', s['hop'], corpo); assert a.status_code == 201, a.text
    b = post(s['c'], f"/sessoes/{corpo['sessao_id']}/leituras", s['hop'], leitura(corpo)); assert b.status_code == 201, b.text
    f = post(s['c'], f"/sessoes/{corpo['sessao_id']}/leituras", s['hop'], leitura(corpo, 'final', 20)); assert f.status_code == 201, f.text
    return corpo, [a.json(), b.json(), f.json()], aparelho, tag


def test_convite_mostra_o_escopo_de_operar(ambiente):
    s = operando(ambiente)
    assert s['comp']['escopo'] == ['consultar', 'operar'] and s['comp']['pode_operar'] is True
    ativo = aceitar(s)
    assert ativo['remessa']['acesso'] == 'compartilhada' and ativo['remessa']['pode_operar'] is True
    det = get(s, f"/remessas/{s['x']['id']}", s['hop']).json()
    assert det['acesso'] == 'compartilhada' and det['pode_operar'] is True and det['empresa_dona'] == 'Teste a'
    proprio = get(s, f"/remessas/{s['x']['id']}", s['ha']).json()
    assert proprio['acesso'] == 'propria' and proprio['empresa_dona'] is None
    # O aplicativo de campo acha a carga pelo código só quando pede as operáveis.
    assert get(s, '/remessas/localizar?valor=' + s['x']['codigo'], s['hop']).json()['candidatas'] == []
    achadas = get(s, '/remessas/localizar?incluir_operaveis=true&valor=' + s['x']['codigo'], s['hop']).json()['candidatas']
    assert [(r['id'], r['acesso'], r['pode_operar']) for r in achadas] == [(s['x']['id'], 'compartilhada', True)]
    assert get(s, '/remessas/localizar?incluir_operaveis=true&valor=CARGA-Y', s['hop']).json()['candidatas'] == []
    assert get(s, '/remessas/localizar?incluir_operaveis=true&valor=' + s['x']['codigo'], s['hb']).json()['candidatas'] == []


def test_transportadora_ativa_acompanha_e_encerra_na_carga_do_dono(ambiente):
    s = operando(ambiente); aceitar(s)
    c, db, x = s['c'], s['db'], s['x']
    corpo, recibos, aparelho, tag = fluxo_da_transportadora(s)
    sid = corpo['sessao_id']
    assert [r['ordem_recebimento'] for r in recibos] == [1, 2, 3]
    # O recibo declara quem bipou; a carga continua sendo do dono.
    assert all(r['empresa_id'] == str(s['t'].id) and r['remessa_id'] == x['id'] for r in recibos)
    sessao = db.get(SessaoMonitoramento, UUID(sid))
    assert sessao.encerrada_em is not None and sessao.ativada_por == s['users']['t_op'].id
    assert {l.empresa_autora_id for l in db.scalars(select(LeituraEtiqueta).where(LeituraEtiqueta.sessao_id == UUID(sid)))} == {s['t'].id}

    # O dono vê a sessão no volume dele, e a cadeia confere para as duas empresas.
    v2 = x['volumes'][1]['id']
    for h in (s['ha'], s['hop']):
        assert [i['id'] for i in get(s, f'/volumes/{v2}/sessoes', h).json()] == [sid]
        integ = get(s, f'/sessoes/{sid}/integridade', h).json()
        assert integ['integra'] is True and integ['quantidade'] == 3
        assert get(s, f'/sessoes/{sid}/viagem', h).status_code == 200
    vol = {v['id']: v for v in get(s, f"/remessas/{x['id']}", s['ha']).json()['volumes']}
    assert vol[v2]['monitorado'] is True and vol[v2]['status'] == 'encerrado'
    # Evidência bruta segue restrita ao dono, inclusive a que a própria transportadora enviou.
    assert get(s, f"/sessoes/{sid}/leituras/{recibos[0]['leitura_id']}", s['ha']).status_code == 200
    assert get(s, f"/sessoes/{sid}/leituras/{recibos[0]['leitura_id']}", s['hop']).status_code == 404

    # Cada bipe da transportadora aparece na auditoria do dono, com quem bipou.
    espelho = db.scalars(select(LogAuditoria).where(LogAuditoria.empresa_id == UUID(x['empresa_id']),
        LogAuditoria.acao == 'evidencia_recebida', LogAuditoria.usuario_id == s['users']['t_op'].id)).all()
    assert len(espelho) == 3 and all(e.depois['empresa_acesso_id'] == str(s['t'].id) for e in espelho)
    assert all(e.depois['via_compartilhamento'] == s['comp']['id'] for e in espelho)
    inicio = db.scalars(select(LogAuditoria).where(LogAuditoria.acao == 'sessao_declarada_iniciada',
                                                   LogAuditoria.entidade_id == UUID(sid))).all()
    assert {l.empresa_id for l in inicio} == {UUID(x['empresa_id']), s['t'].id}


def test_as_duas_empresas_bipam_na_mesma_sessao(ambiente):
    s = operando(ambiente); aceitar(s)
    c = s['c']
    # Sessão do dono (volume 1): a transportadora manda um checkpoint com o aparelho dela.
    _, aparelho, _ = kit_da_transportadora(s)
    do_dono = s['body']
    ck = leitura(do_dono, minutos=40); ck['dispositivo_id'] = aparelho
    r = post(c, f"/sessoes/{s['sessao']}/leituras", s['hop'], ck)
    assert r.status_code == 201, r.text
    assert r.json()['empresa_id'] == str(s['t'].id) and r.json()['ordem_recebimento'] == 3
    # Aparelho de outra empresa não serve, nem para a transportadora nem para o dono.
    alheio = leitura(do_dono, minutos=45)
    assert post(c, f"/sessoes/{s['sessao']}/leituras", s['hop'], alheio).status_code == 404
    alheio = leitura(do_dono, minutos=46); alheio['dispositivo_id'] = aparelho
    assert post(c, f"/sessoes/{s['sessao']}/leituras", s['ha'], alheio).status_code == 404
    # UID de outra etiqueta é recusado mesmo sem a transportadora enxergar o catálogo do dono.
    trocado = leitura(do_dono, minutos=47); trocado['dispositivo_id'] = aparelho; trocado['uid_canonico'] = 'AABBCCDD'
    assert post(c, f"/sessoes/{s['sessao']}/leituras", s['hop'], trocado).status_code == 409
    # O dono fecha a sessão que a transportadora continuou.
    fim = post(c, f"/sessoes/{s['sessao']}/leituras", s['ha'], leitura(do_dono, 'final', 60))
    assert fim.status_code == 201 and fim.json()['empresa_id'] == s['x']['empresa_id']
    for h in (s['ha'], s['hop']):
        integ = get(s, f"/sessoes/{s['sessao']}/integridade", h).json()
        assert integ['integra'] is True and integ['quantidade'] == 4
    autoras = [l.empresa_autora_id for l in s['db'].scalars(select(LeituraEtiqueta)
               .where(LeituraEtiqueta.sessao_id == UUID(s['sessao'])).order_by(LeituraEtiqueta.ordem_recebimento))]
    assert autoras == [UUID(s['x']['empresa_id'])] * 2 + [s['t'].id, UUID(s['x']['empresa_id'])]


def test_operar_nao_vira_administrar_a_carga(ambiente):
    s = operando(ambiente); aceitar(s)
    c, x = s['c'], s['x']
    tag, aparelho, corpo = kit_da_transportadora(s)
    v2 = x['volumes'][1]['id']
    assert post(c, f"/remessas/{x['id']}/documentos", s['hop'], {'tipo': 'pedido', 'numero': 'INVASAO'}).status_code == 404
    assert post(c, f"/remessas/{x['id']}/cancelar", s['ht'], {'motivo': MOTIVO}).status_code == 404
    assert post(c, f"/remessas/{x['id']}/compartilhamentos", s['ht'], {'cnpj_parceira': s['t'].cnpj}).status_code == 404
    # Carga Y do mesmo dono e carga de B: nem com o kit completo.
    for alvo in (s['y'], s['z']):
        assert post(c, f"/volumes/{alvo['volumes'][0]['id']}/sessoes", s['hop'], corpo).status_code == 404
    # Etiqueta do dono não está no catálogo da transportadora.
    do_dono = copy.deepcopy(s['body']); do_dono['sessao_id'] = str(uuid4()); do_dono['evidencia']['evento_id'] = str(uuid4())
    do_dono['evidencia']['dispositivo_id'] = aparelho
    assert post(c, f'/volumes/{v2}/sessoes', s['hop'], do_dono).status_code == 404
    # B não opera nada de A, com ou sem o identificador da sessão.
    assert post(c, f'/volumes/{v2}/sessoes', s['hb'], corpo).status_code == 404
    assert post(c, f"/sessoes/{s['sessao']}/leituras", s['hb'], leitura(s['body'], minutos=40)).status_code == 404


def test_sem_permissao_de_operar_e_depois_de_encerrado(ambiente):
    s = operando(ambiente, operar=False); aceitar(s)
    c, x = s['c'], s['x']
    tag, aparelho, corpo = kit_da_transportadora(s)
    v2 = x['volumes'][1]['id']
    negado = post(c, f'/volumes/{v2}/sessoes', s['hop'], corpo)
    assert negado.status_code == 403 and 'operar' in negado.json()['mensagem']
    ck = leitura(s['body'], minutos=40); ck['dispositivo_id'] = aparelho
    assert post(c, f"/sessoes/{s['sessao']}/leituras", s['hop'], ck).status_code == 403
    assert get(s, '/remessas/localizar?incluir_operaveis=true&valor=' + x['codigo'], s['hop']).json()['candidatas'] == []

    # Dono troca o convite por um com operação; depois encerra no meio da viagem.
    assert post(c, f"/compartilhamentos/{s['comp']['id']}/revogar", s['ha'], {'motivo': MOTIVO}).status_code == 200
    novo = post(c, f"/remessas/{x['id']}/compartilhamentos", s['ha'], {'cnpj_parceira': s['t'].cnpj, 'pode_operar': True})
    assert novo.status_code == 201; s['comp'] = novo.json()
    assert post(c, f'/volumes/{v2}/sessoes', s['hop'], corpo).status_code == 404  # ainda não aceito
    aceitar(s)
    assert post(c, f'/volumes/{v2}/sessoes', s['hop'], corpo).status_code == 201
    assert post(c, f"/compartilhamentos/{s['comp']['id']}/revogar", s['ha'], {'motivo': MOTIVO}).status_code == 200
    depois = post(c, f"/sessoes/{corpo['sessao_id']}/leituras", s['hop'], leitura(corpo))
    assert depois.status_code == 404
    # O que a transportadora já enviou fica com o dono, íntegro, e ele pode continuar a sessão.
    assert get(s, f"/sessoes/{corpo['sessao_id']}/integridade", s['ha']).json()['integra'] is True
    assert [i['id'] for i in get(s, f'/volumes/{v2}/sessoes', s['ha']).json()] == [corpo['sessao_id']]


def _papel_runtime(db):
    role = 'tt_min_op_' + uuid4().hex
    db.execute(text(f'CREATE ROLE {role} NOLOGIN NOSUPERUSER NOBYPASSRLS'))
    for linha in (Path(__file__).parents[1] / 'scripts/runtime-permissoes.sql').read_text(encoding='utf-8').splitlines():
        if linha.startswith('GRANT '):
            db.execute(text(linha.replace('thermotrace_runtime', role)))
    db.execute(text(f'SET LOCAL ROLE {role}'))


def test_fluxo_completo_com_papel_runtime_minimo(ambiente):
    """O mesmo fluxo com RLS valendo de verdade, como no ambiente publicado."""
    s = operando(ambiente)
    tag, aparelho, corpo = kit_da_transportadora(s)
    db = s['db']
    _papel_runtime(db)
    aceitar(s)
    c, x = s['c'], s['x']; v2 = x['volumes'][1]['id']; sid = corpo['sessao_id']
    a = post(c, f'/volumes/{v2}/sessoes', s['hop'], corpo); assert a.status_code == 201, a.text
    b = post(c, f'/sessoes/{sid}/leituras', s['hop'], leitura(corpo)); assert b.status_code == 201, b.text
    # O dono continua a sessão aberta pela transportadora, com etiqueta que não é dele.
    aparelho_dono = s['body']['evidencia']['dispositivo_id']
    ck = leitura(corpo, minutos=15); ck['dispositivo_id'] = aparelho_dono
    d = post(c, f'/sessoes/{sid}/leituras', s['ha'], ck); assert d.status_code == 201, d.text
    f = post(c, f'/sessoes/{sid}/leituras', s['hop'], leitura(corpo, 'final', 20)); assert f.status_code == 201, f.text
    # E a transportadora continua a sessão aberta pelo dono, sem enxergar a etiqueta dele.
    ck = leitura(s['body'], minutos=40); ck['dispositivo_id'] = aparelho
    e = post(c, f"/sessoes/{s['sessao']}/leituras", s['hop'], ck); assert e.status_code == 201, e.text
    for h in (s['ha'], s['hop']):
        assert [i['id'] for i in get(s, f'/volumes/{v2}/sessoes', h).json()] == [sid]
        assert get(s, f'/sessoes/{sid}/integridade', h).json()['integra'] is True
        assert get(s, f"/sessoes/{s['sessao']}/integridade", h).json()['quantidade'] == 3
    assert post(c, f"/volumes/{s['y']['volumes'][0]['id']}/sessoes", s['hop'], corpo).status_code == 404
    assert post(c, f'/sessoes/{sid}/leituras', s['hb'], leitura(corpo, minutos=5)).status_code == 404
    assert post(c, f"/compartilhamentos/{s['comp']['id']}/revogar", s['ha'], {'motivo': MOTIVO}).status_code == 200
    assert post(c, f"/sessoes/{s['sessao']}/leituras", s['hop'], leitura(s['body'], minutos=50)).status_code == 404
    db.execute(text('RESET ROLE'))


def test_rls_operadora_so_grava_o_que_a_operacao_exige(ambiente):
    s = operando(ambiente); aceitar(s)
    corpo, recibos, aparelho, tag = fluxo_da_transportadora(s)
    db, x, t = s['db'], s['x'], s['t']
    _papel_sem_privilegio(db, t.id, s['users']['t_op'].id)
    v1, v2 = x['volumes'][0]['id'], x['volumes'][1]['id']
    # Andamento do volume, sim; dados do volume e da carga, não.
    with pytest.raises(DBAPIError, match='Somente o dono'):
        with db.begin_nested():
            db.execute(text("UPDATE volume SET descricao='invasão' WHERE id=:v"), {'v': v2})
    assert db.execute(text("UPDATE remessa SET status='cancelada' WHERE id=:id"), {'id': x['id']}).rowcount == 0
    assert db.execute(text('UPDATE volume SET monitorado=true WHERE remessa_id=:id'), {'id': s['y']['id']}).rowcount == 0
    with pytest.raises(DBAPIError, match='row-level security'):
        with db.begin_nested():
            db.execute(text("INSERT INTO documento (remessa_id,tipo,numero) VALUES (:id,'pedido','INVASAO')"), {'id': x['id']})
    # Leitura em nome do dono (autora nula ou a empresa dele) é recusada pelo banco.
    base = db.execute(text('SELECT * FROM leitura_etiqueta WHERE id=:id'), {'id': recibos[1]['leitura_id']}).mappings().one()
    for autora in (None, x['empresa_id']):
        nova = {**base, 'id': uuid4(), 'evento_id': uuid4(), 'ordem_recebimento': 99, 'chave_idempotencia': uuid4().hex,
                'hash_payload': uuid4().bytes, 'empresa_autora_id': autora}
        with pytest.raises(DBAPIError, match='row-level security'):
            with db.begin_nested():
                db.execute(LeituraEtiqueta.__table__.insert().values(**nova))
    db.execute(text('RESET ROLE'))

    # O dono também não grava em nome da transportadora.
    _papel_sem_privilegio(db, UUID(x['empresa_id']), s['users']['a'].id)
    nova = {**base, 'id': uuid4(), 'evento_id': uuid4(), 'ordem_recebimento': 98, 'chave_idempotencia': uuid4().hex,
            'hash_payload': uuid4().bytes, 'lida_por': s['users']['a'].id, 'dispositivo_id': None, 'empresa_autora_id': t.id}
    with pytest.raises(DBAPIError, match='row-level security'):
        with db.begin_nested():
            db.execute(LeituraEtiqueta.__table__.insert().values(**nova))
    # Enxerga o vínculo e a sessão do seu volume, mesmo com etiqueta da transportadora.
    assert len(db.execute(text('SELECT id FROM vinculo_etiqueta')).all()) == 2
    assert len(db.execute(text('SELECT id FROM sessao_monitoramento')).all()) == 2
    assert [str(i) for i in db.scalars(text('SELECT id FROM etiqueta'))] != [str(tag.id)]
    db.execute(text('RESET ROLE'))

    # Terceiro, e a própria transportadora depois de revogada: nada.
    _papel_sem_privilegio(db, s['users']['b'].empresa_id, s['users']['b'].id)
    assert db.execute(text('UPDATE volume SET monitorado=false WHERE id=:v'), {'v': v2}).rowcount == 0
    assert db.execute(text("UPDATE sessao_monitoramento SET verificado_em=now()")).rowcount == 0
    db.execute(text('RESET ROLE'))
    assert post(s['c'], f"/compartilhamentos/{s['comp']['id']}/revogar", s['ha'], {'motivo': MOTIVO}).status_code == 200
    _papel_sem_privilegio(db, t.id, s['users']['t_op'].id)
    assert db.execute(text('UPDATE volume SET monitorado=false WHERE id=:v'), {'v': v1}).rowcount == 0
    with pytest.raises(DBAPIError, match='row-level security'):
        with db.begin_nested():
            db.execute(LeituraEtiqueta.__table__.insert().values(
                **{**base, 'id': uuid4(), 'evento_id': uuid4(), 'ordem_recebimento': 97,
                   'chave_idempotencia': uuid4().hex, 'hash_payload': uuid4().bytes}))
    db.execute(text('RESET ROLE'))


def test_permissao_de_operar_nao_muda_depois_do_convite(ambiente):
    s = operando(ambiente, operar=False); aceitar(s)
    db = s['db']
    db.execute(text("SELECT set_config('app.empresa_id', :e, true)"), {'e': str(s['t'].id)})
    with pytest.raises(DBAPIError, match='imutável'):
        with db.begin_nested():
            db.execute(text('UPDATE compartilhamento_remessa SET pode_operar=true WHERE id=:id'), {'id': s['comp']['id']})
