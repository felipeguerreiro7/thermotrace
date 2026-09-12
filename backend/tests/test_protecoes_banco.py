from uuid import uuid4
from sqlalchemy import text
from sqlalchemy.exc import DBAPIError
import pytest

from tests.test_imutabilidade import _semear_leitura
from tests.test_acesso_perfis import ambiente, criar_perfil, headers, aprovar


def test_rls_perfis_sem_where_e_sem_contexto(ambiente):
    c, db, users, entrar = ambiente
    a = criar_perfil(c, headers(entrar()))
    b = criar_perfil(c, headers(entrar("b")))
    # NOLOGIN + NOBYPASSRLS: testa a política sem privilégios de superusuário.
    role = "tt_test_" + uuid4().hex
    db.execute(text(f"CREATE ROLE {role} NOLOGIN NOSUPERUSER NOBYPASSRLS"))
    db.execute(text(f"GRANT SELECT, INSERT, UPDATE ON perfil_termico TO {role}"))
    db.execute(text(f"SET LOCAL ROLE {role}"))
    db.execute(text("SELECT set_config('app.empresa_id', '', true)"))
    assert db.execute(text("SELECT id FROM perfil_termico")).all() == []
    db.execute(text("SELECT set_config('app.empresa_id', :id, true)"), {"id": str(users["a"].empresa_id)})
    assert [str(x) for x in db.scalars(text("SELECT id FROM perfil_termico"))] == [a["id"]]
    assert db.execute(text("UPDATE perfil_termico SET rotulo='invasão' WHERE id=:id"), {"id": b["id"]}).rowcount == 0
    with pytest.raises(DBAPIError, match="row-level security"):
        with db.begin_nested():
            db.execute(text("INSERT INTO perfil_termico (empresa_id,codigo,rotulo,min_c,max_c) VALUES (:id,'X','Invasão',2,8)"),
                       {"id": users["b"].empresa_id})
    db.execute(text("RESET ROLE"))


def test_excursao_nao_altera_evidencia_junto_com_substituicao(sessao):
    ids = _semear_leitura(sessao)
    a, b, c = uuid4(), uuid4(), uuid4()
    for id_ in (a, b, c):
        sessao.execute(text("""
            INSERT INTO excursao (id, sessao_id, leitura_id, tipo, gravidade, inicio_em, fim_em,
                duracao_segundos, quantidade_pontos, pico_c, limite_c, indice_primeira_amostra,
                versao_regra, avaliada_em)
            VALUES (:id,:ses,:lei,'acima','acao',now(),now(),0,1,9,8,0,'teste',now())
        """), {"id": id_, "ses": ids["sessao"], "lei": ids["leitura"]})
    with pytest.raises(DBAPIError, match="sem alterar"):
        with sessao.begin_nested():
            sessao.execute(text("UPDATE excursao SET substituida_por=:b, pico_c=5 WHERE id=:a"), {"a": a, "b": b})
    sessao.execute(text("UPDATE excursao SET substituida_por=:b WHERE id=:a"), {"a": a, "b": b})
    with pytest.raises(DBAPIError):
        with sessao.begin_nested():
            sessao.execute(text("UPDATE excursao SET substituida_por=:c WHERE id=:a"), {"a": a, "c": c})


def test_fluxo_http_funciona_com_papel_runtime_sem_bypassrls(ambiente):
    c, db, users, entrar = ambiente
    role = "tt_runtime_test_" + uuid4().hex
    db.execute(text(f"CREATE ROLE {role} NOLOGIN NOSUPERUSER NOBYPASSRLS"))
    db.execute(text(f"GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO {role}"))
    db.execute(text(f"GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO {role}"))
    db.execute(text(f"SET LOCAL ROLE {role}"))
    a = headers(entrar())
    p = criar_perfil(c, a)
    assert aprovar(c, a, p["id"]).status_code == 200
    assert c.get("/api/v1/perfis", headers=a).json()[0]["id"] == p["id"]
    b = headers(entrar("b"))
    assert c.get("/api/v1/perfis", headers=b).json() == []
    db.execute(text("RESET ROLE"))
