"""Conformidade térmica: classifica as viagens da empresa com a mesma viagem do relatório."""
from sqlalchemy import select

from app.models import LogAuditoria
from app.services.conformidade import situacao
from tests.test_acesso_perfis import ambiente, headers  # noqa: F401
from tests.test_ingestao import preparar_ingestao, iniciar
from tests.test_viagem import PONTOS, coletar

URL = "/api/v1/conformidade"
DENTRO = [4, 5, 6, 5.5, 4.5, 5]


def painel(d):
    r = d[0].get(URL, headers=d[3])
    assert r.status_code == 200, r.text
    return r.json()


def linha(p, d):
    return next(v for v in p["viagens"] if v["sessao_id"] == d[-1]["sessao_id"])


def test_regra_de_situacao_na_ordem():
    base = {"resumo": {"excursoes": 0, "inclui_leitura_final": True}, "conferencia": "coerente"}
    assert situacao({**base, "resumo": None}) == "sem_dados"
    assert situacao({**base, "conferencia": "divergente", "resumo": {"excursoes": 2, "inclui_leitura_final": True}}) == "divergente"
    assert situacao({**base, "resumo": {"excursoes": 1, "inclui_leitura_final": False}}) == "excursao"
    assert situacao({**base, "resumo": {"excursoes": 0, "inclui_leitura_final": False}}) == "em_andamento"
    assert situacao(base) == "conforme"


def test_viagem_muda_de_situacao_conforme_as_coletas(ambiente):  # noqa: F811
    d = preparar_ingestao(ambiente); assert iniciar(d).status_code == 201
    assert linha(painel(d), d)["situacao"] == "sem_dados"
    coletar(d, DENTRO[:3], 30)
    assert linha(painel(d), d)["situacao"] == "em_andamento"
    coletar(d, DENTRO, 60, "final")
    p = painel(d); v = linha(p, d)
    assert v["situacao"] == "conforme" and v["percentual_na_faixa"] == 100 and v["pior_extremo_c"] is None
    assert v["remessa_codigo"] == "CARGA-TESTE" and v["volume"] and v["integridade_da_cadeia"]
    assert p["totais"]["conforme"] >= 1 and p["totais"]["taxa_conformidade"] is not None
    # O hash é o mesmo da viagem do relatório: nada recalculado de outro jeito.
    viagem = d[0].get(f"/api/v1/sessoes/{d[-1]['sessao_id']}/viagem", headers=d[3]).json()
    assert v["hash_projecao"] == viagem["hash_projecao"]


def test_excursao_mostra_tempo_fora_e_pior_extremo(ambiente):  # noqa: F811
    d = preparar_ingestao(ambiente); assert iniciar(d).status_code == 201
    coletar(d, PONTOS, 90, "final")  # 9 e 9,5 acima; 1,5 abaixo (faixa 2 a 8)
    v = linha(painel(d), d)
    assert v["situacao"] == "excursao" and v["excursoes"] == 2 and v["tempo_fora_nominal_s"] == 1800
    assert v["pior_extremo_c"] == 9.5  # 1,5 dentro de 0,5 °C do mínimo; 9,5 passa 1,5 °C do máximo


def test_coletas_divergentes_vem_antes_de_excursao(ambiente):  # noqa: F811
    d = preparar_ingestao(ambiente); assert iniciar(d).status_code == 201
    alterado = list(PONTOS[:6]); alterado[1] = 7.25
    coletar(d, PONTOS[:3], 30); coletar(d, alterado, 60); coletar(d, PONTOS, 90, "final")
    assert linha(painel(d), d)["situacao"] == "divergente"


def test_isolamento_acesso_e_auditoria(ambiente):  # noqa: F811
    c, db, users, entrar = ambiente
    d = preparar_ingestao(ambiente); assert iniciar(d).status_code == 201
    coletar(d, DENTRO, 60, "final")
    outra = c.get(URL, headers=headers(entrar("b"))).json()
    assert all(v["sessao_id"] != d[-1]["sessao_id"] for v in outra["viagens"])
    assert c.get(URL, headers=headers(entrar("staff"))).status_code == 403
    assert c.get(URL).status_code == 401
    painel(d)
    log = db.scalar(select(LogAuditoria).where(LogAuditoria.acao == "conformidade_consultada",
                                               LogAuditoria.empresa_id == users["a"].empresa_id))
    assert log and log.depois["versao"] == "tt-conformidade-1"


def test_csv_para_planilha_com_auditoria_do_arquivo(ambiente):  # noqa: F811
    import hashlib
    d = preparar_ingestao(ambiente); assert iniciar(d).status_code == 201
    db = d[1]
    coletar(d, PONTOS, 90, "final")
    r = d[0].get(URL + "?formato=csv", headers=d[3])
    assert r.status_code == 200 and r.headers["content-type"].startswith("text/csv")
    assert "attachment" in r.headers["content-disposition"] and r.headers["cache-control"] == "no-store"
    assert r.text.startswith("﻿")
    linhas = r.text.lstrip("﻿").splitlines()
    assert linhas[2].split(";")[:5] == ["carga", "volume", "sessao_id", "iniciada_em_utc", "situacao"]
    minha = next(l.split(";") for l in linhas[3:] if d[-1]["sessao_id"] in l)
    assert minha[4] == "Com excursão" and minha[6] == "30" and minha[8] == "9,5" and minha[9] == "2,0"
    log = db.scalar(select(LogAuditoria).where(LogAuditoria.acao == "conformidade_exportada"))
    assert log.depois["sha256_arquivo"] == hashlib.sha256(r.text.encode("utf-8")).hexdigest()
    assert d[0].get(URL + "?formato=xlsx", headers=d[3]).status_code == 422
