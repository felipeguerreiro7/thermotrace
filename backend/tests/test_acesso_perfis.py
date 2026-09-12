"""Integração HTTP + PostgreSQL real, com clientes independentes e rollback externo."""
import copy
from datetime import timedelta
from uuid import uuid4, UUID

import jwt
import pytest
from fastapi.testclient import TestClient
from sqlalchemy import select, text
from sqlalchemy.orm import Session
from sqlalchemy.exc import DBAPIError

from app.core.config import obter_config
from app.core.security import hasher, agora, ler_token
from app.db.session import engine, obter_sessao
from app.main import app
from app.models import Empresa, Usuario, SessaoAuth, LogAuditoria
from app.models.enums import PapelUsuario, TipoEmpresa

SENHA = "Senha-sintetica-para-teste-42"
PERFIL = {"codigo": "TESTE-REFRIGERADO", "rotulo": "Produto sintético fechado",
          "min_c": "2.00", "max_c": "8.00", "evidencia": {
              "produto": "Produto sintético", "apresentacao": "Frasco teste",
              "condicao": "Fechado, transporte", "fonte_url": "https://example.org/bula-teste",
              "fonte_titulo": "Fonte sintética de teste", "fonte_versao": "1",
              "verificada_em": "2026-01-01", "justificativa": "Critério sintético exclusivo de teste."}}


@pytest.fixture
def ambiente():
    with engine.connect() as conn:
        outer = conn.begin()
        db = Session(conn, join_transaction_mode="create_savepoint", expire_on_commit=False)
        dados = {}
        ph = hasher.hash(SENHA)
        for nome, papel, tipo in [("a", PapelUsuario.GESTOR, TipoEmpresa.EMBARCADOR),
                                  ("b", PapelUsuario.GESTOR, TipoEmpresa.EMBARCADOR),
                                  ("staff", PapelUsuario.ADMIN, TipoEmpresa.PLATAFORMA)]:
            emp = Empresa(razao_social=f"Teste {nome}", cnpj=str(uuid4().int)[:14], tipo=tipo)
            db.add(emp); db.flush()
            u = Usuario(empresa_id=emp.id, nome=nome, email=f"{nome}-{uuid4().hex}@example.org",
                        senha_hash=ph, papel=papel)
            db.add(u); db.flush()
            dados[nome] = u
        operador = Usuario(empresa_id=dados["a"].empresa_id, nome="Operador",
                           email=f"op-{uuid4().hex}@example.org", senha_hash=ph, papel=PapelUsuario.OPERADOR)
        db.add(operador); db.flush(); dados["op"] = operador
        db.commit()

        def override():
            try:
                yield db
                db.commit()
            except Exception:
                db.rollback()
                raise

        app.dependency_overrides[obter_sessao] = override
        client = TestClient(app)
        def entrar(nome="a"):
            r = client.post("/api/v1/auth/login", json={"email": dados[nome].email.upper(), "senha": SENHA})
            assert r.status_code == 200, r.text
            return r.json()
        try:
            yield client, db, dados, entrar
        finally:
            client.close()
            app.dependency_overrides.clear()
            db.close()
            outer.rollback()


def headers(tokens):
    return {"Authorization": "Bearer " + tokens["access_token"]}


def criar_perfil(client, h):
    r = client.post("/api/v1/perfis", headers=h, json=copy.deepcopy(PERFIL))
    assert r.status_code == 201, r.text
    return r.json()


def aprovar(client, h, pid):
    return client.post(f"/api/v1/perfis/{pid}/aprovar", headers=h,
                       json={"declaracao_responsavel": "Revisei a fonte e o escopo para este teste sintético."})


def test_login_sem_segredos_e_logout_revoga(ambiente):
    c, db, users, entrar = ambiente
    token = entrar()
    r = c.get("/api/v1/auth/me", headers=headers(token))
    assert r.status_code == 200
    assert r.json()["empresa_id"] == str(users["a"].empresa_id)
    assert "senha" not in r.text and "token" not in r.text
    s = db.scalar(select(SessaoAuth).where(SessaoAuth.usuario_id == users["a"].id))
    assert s.token_hash != token["refresh_token"] and len(s.token_hash) == 64
    assert c.post("/api/v1/auth/logout", headers=headers(token)).status_code == 204
    assert c.get("/api/v1/auth/me", headers=headers(token)).status_code == 401
    assert c.post("/api/v1/auth/refresh", json={"refresh_token": token["refresh_token"]}).status_code == 401


def test_refresh_reuso_revoga_familia_e_persiste_no_401(ambiente):
    c, db, users, entrar = ambiente
    old = entrar()
    new = c.post("/api/v1/auth/refresh", json={"refresh_token": old["refresh_token"]}).json()
    assert c.get("/api/v1/auth/me", headers=headers(new)).status_code == 200
    assert c.get("/api/v1/auth/me", headers=headers(old)).status_code == 401
    assert c.post("/api/v1/auth/refresh", json={"refresh_token": old["refresh_token"]}).status_code == 401
    assert c.get("/api/v1/auth/me", headers=headers(new)).status_code == 401
    assert not db.scalars(select(SessaoAuth).where(SessaoAuth.usuario_id == users["a"].id,
                                                 SessaoAuth.revogada_em.is_(None))).all()


@pytest.mark.parametrize("mutacao", ["expirado", "audiencia", "emissor", "assinatura", "sem_exp", "sid_invalido"])
def test_tokens_invalidos_recusados(ambiente, mutacao):
    c, db, users, entrar = ambiente
    claims = ler_token(entrar()["access_token"])
    key = obter_config().SECRET_KEY
    if mutacao == "expirado": claims["exp"] = agora() - timedelta(seconds=1)
    if mutacao == "audiencia": claims["aud"] = "outro-app"
    if mutacao == "emissor": claims["iss"] = "outro-emissor"
    if mutacao == "assinatura": key = "outra-chave-sintetica-1234567890abcdefgh"
    if mutacao == "sem_exp": del claims["exp"]
    if mutacao == "sid_invalido": claims["sid"] = "nao-uuid"
    token = jwt.encode(claims, key, algorithm="HS256")
    assert c.get("/api/v1/auth/me", headers={"Authorization": "Bearer " + token}).status_code == 401


def test_erro_login_generico_e_limite_persistido(ambiente):
    c, db, users, entrar = ambiente
    a = c.post("/api/v1/auth/login", json={"email": users["a"].email, "senha": "errada"})
    b = c.post("/api/v1/auth/login", json={"email": "ausente@example.org", "senha": "errada"})
    assert a.status_code == b.status_code == 401
    assert a.json()["mensagem"] == b.json()["mensagem"]
    for _ in range(9):
        assert c.post("/api/v1/auth/login", json={"email": users["a"].email, "senha": "errada"}).status_code == 401
    assert c.post("/api/v1/auth/login", json={"email": users["a"].email, "senha": SENHA}).status_code == 429
    assert db.scalar(text("SELECT max(tentativas) FROM limite_login")) >= 11


def test_empresa_desativada_revoga_acesso_e_renovacao(ambiente):
    c, db, users, entrar = ambiente
    token = entrar()
    emp = db.get(Empresa, users["a"].empresa_id)
    emp.ativa = False; db.commit()
    assert c.get("/api/v1/auth/me", headers=headers(token)).status_code == 401
    assert c.post("/api/v1/auth/refresh", json={"refresh_token": token["refresh_token"]}).status_code == 401


def test_isolamento_clientes_por_lista_id_e_mutacao(ambiente):
    c, db, users, entrar = ambiente
    a, b, staff = headers(entrar()), headers(entrar("b")), headers(entrar("staff"))
    p = criar_perfil(c, a)
    for h in (b, staff):
        assert c.get("/api/v1/perfis?somente_ativos=false", headers=h).json() == []
        assert c.get(f"/api/v1/perfis/{p['id']}", headers=h).status_code == 404
    assert aprovar(c, b, p["id"]).status_code == 404
    assert c.post(f"/api/v1/perfis/{p['id']}/retirar", headers=b).status_code == 404
    assert c.post(f"/api/v1/usuarios/{users['op'].id}/desativar", headers=b).status_code == 404
    assert all(e["entidade_id"] != p["id"] for e in c.get("/api/v1/auditoria", headers=b).json())


def test_cliente_nao_injeta_empresa_ou_papel_admin(ambiente):
    c, db, users, entrar = ambiente
    h = headers(entrar())
    body = copy.deepcopy(PERFIL); body["empresa_id"] = str(users["b"].empresa_id)
    assert c.post("/api/v1/perfis", headers=h, json=body).status_code == 422
    u = {"nome": "Invasor", "email": "invasor@example.org", "senha": SENHA, "papel": "admin"}
    assert c.post("/api/v1/usuarios", headers=h, json=u).status_code == 422
    assert c.get("/api/v1/plataforma/clientes", headers=h).status_code == 403


def test_operador_sem_permissao_de_aprovar_cadastrar_ou_auditar(ambiente):
    c, db, users, entrar = ambiente
    a, op = headers(entrar()), headers(entrar("op"))
    p = criar_perfil(c, a)
    assert c.post("/api/v1/perfis", headers=op, json=PERFIL).status_code == 403
    assert aprovar(c, op, p["id"]).status_code == 403
    assert c.get("/api/v1/usuarios", headers=op).status_code == 403
    assert c.get("/api/v1/auditoria", headers=op).status_code == 403


def test_perfil_rascunho_aprovacao_versao_e_retirada(ambiente):
    c, db, users, entrar = ambiente
    a, b = headers(entrar()), headers(entrar("b"))
    p = criar_perfil(c, a)
    assert not p["ativo"] and p["aprovado_em"] is None
    assert c.get("/api/v1/perfis", headers=a).json() == []
    assert aprovar(c, a, p["id"]).status_code == 200
    assert len(c.get("/api/v1/perfis", headers=a).json()) == 1
    assert aprovar(c, a, p["id"]).status_code == 409
    assert criar_perfil(c, a)["versao"] == 2
    assert criar_perfil(c, b)["versao"] == 1
    assert c.post(f"/api/v1/perfis/{p['id']}/retirar", headers=a).status_code == 200
    assert c.get("/api/v1/perfis", headers=a).json() == []
    assert c.get(f"/api/v1/perfis/{p['id']}", headers=a).json()["aprovado_em"] is not None


@pytest.mark.parametrize("mudanca", [{"min_c": "8"}, {"max_c": "NaN"}, {"min_c": "2.001"},
                                       {"evidencia": {}}, {"tolerancia_segundos": 300}])
def test_criterios_invalidos_recusados(ambiente, mudanca):
    c, db, users, entrar = ambiente
    body = copy.deepcopy(PERFIL); body.update(mudanca)
    assert c.post("/api/v1/perfis", headers=headers(entrar()), json=body).status_code == 422


def test_banco_protege_perfil_aprovado_e_auditoria(ambiente):
    c, db, users, entrar = ambiente
    h = headers(entrar()); p = criar_perfil(c, h)
    assert aprovar(c, h, p["id"]).status_code == 200
    for sql in ["UPDATE perfil_termico SET min_c=1 WHERE id=:id", "DELETE FROM perfil_termico WHERE id=:id"]:
        with pytest.raises(DBAPIError):
            with db.begin_nested(): db.execute(text(sql), {"id": UUID(p["id"])})
    with pytest.raises(DBAPIError):
        with db.begin_nested():
            db.execute(text("UPDATE log_auditoria SET acao='adulterada' WHERE empresa_id=:id"), {"id": users["a"].empresa_id})


def test_desativar_operador_invalida_token(ambiente):
    c, db, users, entrar = ambiente
    op = entrar("op"); h = headers(entrar())
    assert c.post(f"/api/v1/usuarios/{users['op'].id}/desativar", headers=h).status_code == 204
    assert c.get("/api/v1/auth/me", headers=headers(op)).status_code == 401


def test_staff_provisiona_cliente_e_gestor_sem_senha_na_resposta(ambiente):
    c, db, users, entrar = ambiente
    h = headers(entrar("staff"))
    body = {"razao_social": "Cliente sintético", "cnpj": "11222333000181",
            "gestor": {"nome": "Gestor", "email": "novo@example.org", "senha": SENHA}}
    r = c.post("/api/v1/plataforma/clientes", headers=h, json=body)
    assert r.status_code == 201, r.text
    assert r.json()["gestor"]["papel"] == "gestor"
    assert SENHA not in r.text and "senha_hash" not in r.text
    clientes = c.get("/api/v1/plataforma/clientes?limite=100", headers=h).json()
    assert {str(users["a"].empresa_id), str(users["b"].empresa_id), r.json()["id"]}.issubset({e["id"] for e in clientes})
    tokens = c.post("/api/v1/auth/login", json={"email": "novo@example.org", "senha": SENHA}).json()
    assert c.get("/api/v1/auth/me", headers=headers(tokens)).json()["empresa_id"] == r.json()["id"]


def test_modelos_nao_ativam_perfis(ambiente):
    c, db, users, entrar = ambiente
    h = headers(entrar())
    r = c.get("/api/v1/perfis/modelos", headers=h)
    assert r.status_code == 200
    assert c.get("/api/v1/perfis", headers=h).json() == []


@pytest.mark.parametrize("path", ["/auth/me", "/perfis", "/perfis/modelos", "/usuarios", "/auditoria", "/plataforma/clientes"])
def test_rotas_privadas_exigem_login(ambiente, path):
    assert ambiente[0].get("/api/v1"+path).status_code == 401
