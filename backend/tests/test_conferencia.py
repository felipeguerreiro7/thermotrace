"""Conferência pública do relatório: confirma o que o servidor emitiu, sem login e sem vazar dados."""
from uuid import UUID, uuid4

from sqlalchemy import select

from app.models import LogAuditoria
from tests.test_acesso_perfis import ambiente  # noqa: F401
from tests.test_ingestao import preparar_ingestao, iniciar
from tests.test_viagem import PONTOS, coletar

URL = "/api/v1/conferencia/viagem"


def exportar(d):
    c, h, sid = d[0], d[3], d[-1]["sessao_id"]
    html = c.get(f"/api/v1/sessoes/{sid}/viagem?formato=html", headers=h)
    assert html.status_code == 200
    hash_ = c.get(f"/api/v1/sessoes/{sid}/viagem", headers=h).json()["hash_projecao"]
    return sid, hash_, html.text


def test_relatorio_exportado_confere_sem_login_e_fica_na_auditoria_do_dono(ambiente):  # noqa: F811
    d = preparar_ingestao(ambiente); assert iniciar(d).status_code == 201
    c, db = d[0], d[1]
    coletar(d, PONTOS[:6], 60); coletar(d, PONTOS, 90, "final")
    sid, hash_, html = exportar(d)
    assert f"/portal/conferir?sessao={sid}&amp;hash={hash_}" in html
    r = c.get(URL, params={"sessao": sid, "hash": hash_.upper()})
    assert r.status_code == 200 and r.headers["cache-control"] == "no-store"
    v = r.json()
    assert v["confere"] and v["versao_atual"] and v["formatos"] == ["html"]
    assert v["resumo"]["quantidade"] == 9 and v["resumo"]["inclui_leitura_final"] and v["integridade_da_cadeia"]
    # Só o que o relatório já mostra: nada de empresa, usuário, aparelho ou série ponto a ponto.
    assert not {"empresa", "usuario", "pontos", "coletas", "dispositivo_id"} & set(v)
    log = db.scalar(select(LogAuditoria).where(LogAuditoria.acao == "viagem_conferida_publicamente",
                                               LogAuditoria.entidade_id == UUID(sid)))
    assert log and log.usuario_id is None and log.empresa_id == d[2]["a"].empresa_id
    assert log.depois == {"hash_projecao": hash_, "confere": True, "versao_atual": True}


def test_relatorio_antigo_confere_mas_avisa_versao_mais_nova_sem_resumo(ambiente):  # noqa: F811
    d = preparar_ingestao(ambiente); assert iniciar(d).status_code == 201
    c = d[0]
    coletar(d, PONTOS[:6], 60)
    sid, antigo, _ = exportar(d)
    coletar(d, PONTOS, 90, "final")
    v = c.get(URL, params={"sessao": sid, "hash": antigo}).json()
    assert v["confere"] and not v["versao_atual"]
    assert v["resumo"] is None and v["origem"] is None and v["conferencia"] is None


def test_nao_confere_e_igual_para_hash_errado_sessao_inexistente_e_so_consultada(ambiente):  # noqa: F811
    d = preparar_ingestao(ambiente); assert iniciar(d).status_code == 201
    c, db, h = d[0], d[1], d[3]
    coletar(d, PONTOS, 90, "final")
    sid = d[-1]["sessao_id"]
    consultado = c.get(f"/api/v1/sessoes/{sid}/viagem", headers=h).json()["hash_projecao"]  # JSON não é relatório
    respostas = [c.get(URL, params=p).json() for p in (
        {"sessao": sid, "hash": consultado},
        {"sessao": sid, "hash": "0" * 64},
        {"sessao": str(uuid4()), "hash": consultado},
    )]
    assert respostas[0] == respostas[1] == respostas[2] and respostas[0]["confere"] is False
    assert set(respostas[0]) == {"confere", "mensagem"}
    assert not db.scalar(select(LogAuditoria).where(LogAuditoria.acao == "viagem_conferida_publicamente"))


def test_formato_invalido_e_limite_por_ip(ambiente):  # noqa: F811
    c = ambiente[0]
    assert c.get(URL, params={"sessao": str(uuid4()), "hash": "abc"}).status_code == 422
    assert c.get(URL, params={"sessao": "nao-e-uuid", "hash": "0" * 64}).status_code == 422
    codigos = [c.get(URL, params={"sessao": str(uuid4()), "hash": "0" * 64}).status_code for _ in range(31)]
    assert codigos[:30] == [200] * 30 and codigos[30] == 429


def test_confere_com_papel_minimo_da_api_e_rls(ambiente):  # noqa: F811
    """Sem empresa logada, a rota pública só lê a carga depois de achar a exportação na auditoria."""
    from pathlib import Path
    from sqlalchemy import text
    d = preparar_ingestao(ambiente); assert iniciar(d).status_code == 201
    c, db = d[0], d[1]
    coletar(d, PONTOS, 90, "final")
    sid, hash_, _ = exportar(d)
    role = "tt_min_conferencia_" + uuid4().hex
    db.execute(text(f"CREATE ROLE {role} NOLOGIN NOSUPERUSER NOBYPASSRLS"))
    for linha in (Path(__file__).parents[1] / "scripts/runtime-permissoes.sql").read_text(encoding="utf-8").splitlines():
        if linha.startswith("GRANT "):
            db.execute(text(linha.replace("thermotrace_runtime", role)))
    db.execute(text(f"SET LOCAL ROLE {role}"))
    try:
        # Sem contexto de empresa, o RLS esconde a sessão: a leitura direta não vê nada.
        db.execute(text("SELECT set_config('app.empresa_id', '', true)"))
        assert db.execute(text("SELECT id FROM sessao_monitoramento")).all() == []
        v = c.get(URL, params={"sessao": sid, "hash": hash_}).json()
        assert v["confere"] and v["versao_atual"] and v["resumo"]["quantidade"] == 9
        assert c.get(URL, params={"sessao": sid, "hash": "0" * 64}).json()["confere"] is False
    finally:
        db.execute(text("RESET ROLE"))
