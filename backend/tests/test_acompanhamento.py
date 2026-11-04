"""Acompanhamento da operação: sessões sem leitura final e saúde de envio dos aparelhos."""
from datetime import datetime, timedelta, timezone
from uuid import UUID

from app.services import acompanhamento
from tests.test_acesso_perfis import ambiente, headers  # noqa: F401 (fixture)
from tests.test_ingestao import preparar_ingestao, iniciar, enviar, leitura

INICIO = datetime(2026, 9, 12, 12, tzinfo=timezone.utc)
# Fixture: 100 medições a cada 600 s, sem atraso programado → memória acaba 1000 min depois.
FIM = INICIO + timedelta(minutes=1000)


def test_sessao_aberta_aparece_com_fim_previsto_e_situacao(ambiente):
    d = preparar_ingestao(ambiente); assert iniciar(d).status_code == 201
    c, db, users, h, r, tag, body = d
    assert enviar(d, leitura(body, 'checkpoint', 60)).status_code == 201
    empresa = users['a'].empresa_id

    def situacao(agora, horas=24):
        p = acompanhamento.painel(db, empresa, agora, horas)
        assert p['totais']['sessoes_abertas'] == 1
        return p['sessoes_abertas'][0]

    s = situacao(INICIO + timedelta(hours=3))
    assert s['sessao_id'] == UUID(body['sessao_id']) and s['remessa_codigo'] == r['codigo']
    assert s['coletas'] == 2 and s['fim_previsto_memoria'] == FIM
    assert s['ultima_coleta_em'] == INICIO + timedelta(minutes=60)
    assert s['situacao'] == 'em_andamento' and s['horas_ate_fim_previsto'] == 13.7
    # Duas horas sem coleta com limite de 1 h: alerta antes de a memória acabar.
    assert situacao(INICIO + timedelta(hours=3), horas=1)['situacao'] == 'sem_coleta_recente'
    assert situacao(FIM)['situacao'] == 'registro_encerrado'


def test_leitura_final_tira_sessao_do_painel(ambiente):
    d = preparar_ingestao(ambiente); assert iniciar(d).status_code == 201
    c, db, users, h, r, tag, body = d
    assert enviar(d, leitura(body, 'final', 90)).status_code == 201
    p = c.get('/api/v1/acompanhamento', headers=h).json()
    assert p['sessoes_abertas'] == [] and p['totais']['sessoes_abertas'] == 0


def test_rota_mostra_aparelho_e_isola_empresas(ambiente):
    d = preparar_ingestao(ambiente); assert iniciar(d).status_code == 201
    c, db, users, h, r, tag, body = d
    p = c.get('/api/v1/acompanhamento', headers=h)
    assert p.status_code == 200, p.text
    p = p.json()
    assert p['versao'] == acompanhamento.VERSAO
    # Data da fixture já passou: a memória programada acabou e a sessão vem primeiro.
    assert [s['situacao'] for s in p['sessoes_abertas']] == ['registro_encerrado']
    [a] = p['aparelhos']
    assert a['id'] == body['evidencia']['dispositivo_id'] and a['ultimo_operador'] == users['a'].nome
    assert a['ultima_recepcao_em'] is not None
    # Leitura de setembro recebida agora: atraso de envio grande e visível, sem esconder.
    assert a['coletas_7_dias'] == 1 and a['maior_atraso_envio_7_dias_s'] > 86400
    b = c.get('/api/v1/acompanhamento', headers=headers(ambiente[3]('b'))).json()
    assert b['sessoes_abertas'] == [] and b['aparelhos'] == []
    assert c.get('/api/v1/acompanhamento', headers=headers(ambiente[3]('staff'))).status_code == 403
    assert c.get('/api/v1/acompanhamento').status_code == 401
    assert c.get('/api/v1/acompanhamento?horas_sem_coleta=0', headers=h).status_code == 422
