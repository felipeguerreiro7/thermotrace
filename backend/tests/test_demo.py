"""Entrada na demonstração: só com DEMO_PUBLICA, só nas contas fictícias, nunca em produção."""
import pytest
from pydantic import ValidationError
from sqlalchemy import select

from app.core.config import Config, obter_config
from app.core.security import agora, hasher
from app.demo_cli import DONA, TRANSPORTADORA, email
from app.models import Empresa, LogAuditoria, Usuario
from app.models.enums import PapelUsuario
from app.services import mfa
from tests.test_acesso_perfis import ambiente, headers, SENHA  # noqa: F401


@pytest.fixture
def demo(ambiente, monkeypatch):  # noqa: F811
    c, db, users, entrar = ambiente
    monkeypatch.setattr(obter_config(), "DEMO_PUBLICA", True)
    contas = {}
    for cnpj, razao, tipo, prefixo in (DONA, TRANSPORTADORA):
        emp = db.scalar(select(Empresa).where(Empresa.cnpj == cnpj))
        if not emp:
            emp = Empresa(cnpj=cnpj, razao_social=razao, tipo=tipo)
            db.add(emp); db.flush()
        u = db.scalar(select(Usuario).where(Usuario.email == email(prefixo, "gestor")))
        if not u:
            u = Usuario(empresa_id=emp.id, nome="Gestor de demonstração", email=email(prefixo, "gestor"),
                        senha_hash=hasher.hash(SENHA), papel=PapelUsuario.GESTOR)
            db.add(u); db.flush()
        contas[prefixo] = u
    db.commit()
    return c, db, contas


def test_desligada_por_padrao_nao_abre_sessao(ambiente, monkeypatch):  # noqa: F811
    c, *_ = ambiente
    assert Config.model_fields["DEMO_PUBLICA"].default is False
    monkeypatch.setattr(obter_config(), "DEMO_PUBLICA", False)  # o .env local pode ligar
    assert c.get("/api/v1/demo").json() == {"habilitada": False, "perfis": []}
    assert c.post("/api/v1/demo/entrar", json={"perfil": "dono"}).status_code == 404


def test_entra_na_conta_ficticia_e_audita(demo):
    c, db, contas = demo
    info = c.get("/api/v1/demo").json()
    assert info["habilitada"] and {p["perfil"] for p in info["perfis"]} == {"dono", "transportadora"}
    r = c.post("/api/v1/demo/entrar", json={"perfil": "transportadora"})
    assert r.status_code == 200 and r.headers["cache-control"] == "no-store"
    me = c.get("/api/v1/auth/me", headers=headers(r.json())).json()
    assert me["email"] == contas["transportadora"].email
    assert db.scalar(select(LogAuditoria).where(LogAuditoria.acao == "login_demonstracao",
                                                LogAuditoria.usuario_id == contas["transportadora"].id))


def test_perfil_livre_ou_email_sao_recusados(demo):
    c, *_ = demo
    assert c.post("/api/v1/demo/entrar", json={"perfil": "admin"}).status_code == 422
    r = c.post("/api/v1/demo/entrar", json={"perfil": "dono", "email": "outra@example.org"})
    assert r.status_code == 200  # campo extra é ignorado: a conta é sempre a fictícia
    assert c.get("/api/v1/auth/me", headers=headers(r.json())).json()["email"] == email("dono", "gestor")


def test_nao_abre_conta_com_mfa_ou_inativa(demo):
    c, db, contas = demo
    dono = contas["dono"]
    dono.mfa_segredo, dono.mfa_ativado_em = mfa.novo_segredo(), agora()
    contas["transportadora"].ativo = False
    db.commit()
    assert c.get("/api/v1/demo").json()["habilitada"] is False
    assert c.post("/api/v1/demo/entrar", json={"perfil": "dono"}).status_code == 404
    assert c.post("/api/v1/demo/entrar", json={"perfil": "transportadora"}).status_code == 404


def test_configuracao_recusa_demo_em_producao():
    cfg = obter_config()
    with pytest.raises(ValidationError):
        Config(DATABASE_URL=str(cfg.DATABASE_URL), SECRET_KEY=cfg.SECRET_KEY, AMBIENTE="producao", DEMO_PUBLICA=True)
