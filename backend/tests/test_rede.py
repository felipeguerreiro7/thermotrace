"""IP do cliente e endereço público atrás do proxy do Render (Cloudflare na borda)."""
import ipaddress
import secrets

import pytest
from pydantic import ValidationError
from starlette.requests import Request

from app.core.config import Config, obter_config
from app.core.rede import ip_do_cliente, url_publica
from tests.test_acesso_perfis import ambiente  # noqa: F401
from tests.test_ingestao import preparar_ingestao, iniciar
from tests.test_viagem import PONTOS, coletar

URL = "/api/v1/conferencia/viagem"


def ip_novo():
    # Faixa de documentação; aleatório porque os limites ficam no banco por 5 minutos.
    return str(ipaddress.ip_address(f"2001:db8::{secrets.token_hex(2)}:{secrets.token_hex(2)}"))  # forma canônica


def requisicao(cabecalhos, cliente=("10.0.0.7", 1234), scheme="http"):
    return Request({"type": "http", "method": "GET", "path": "/", "query_string": b"", "scheme": scheme,
                    "server": ("thermotrace-api.onrender.com", 80), "client": cliente,
                    "headers": [(k.lower().encode(), v.encode()) for k, v in cabecalhos.items()]})


@pytest.fixture
def atras_da_cloudflare(monkeypatch):
    monkeypatch.setattr(obter_config(), "IP_CLIENTE_CABECALHO", "CF-Connecting-IP")


def conferir(c, ip, **extra):
    return c.get(URL, params={"sessao": "00000000-0000-0000-0000-0000000000aa", "hash": "0" * 64},
                 headers={"CF-Connecting-IP": ip, **extra}).status_code


def test_cada_cliente_tem_o_proprio_limite(ambiente, atras_da_cloudflare):  # noqa: F811
    c = ambiente[0]; a, b = ip_novo(), ip_novo()
    assert [conferir(c, a) for _ in range(30)] == [200] * 30
    assert conferir(c, b) == 200  # antes, todos dividiam o IP do proxy
    assert conferir(c, a) == 429


def test_x_forwarded_for_forjado_nao_troca_o_ip(ambiente, atras_da_cloudflare):  # noqa: F811
    c = ambiente[0]; a = ip_novo()
    codigos = [conferir(c, a, **{"X-Forwarded-For": ip_novo()}) for _ in range(31)]
    assert codigos[-1] == 429


def test_sem_configuracao_o_cabecalho_e_ignorado():
    """Fora da Cloudflare, quem manda CF-Connecting-IP não escolhe o próprio IP."""
    assert ip_do_cliente(requisicao({"CF-Connecting-IP": "198.51.100.9"})) == "10.0.0.7"


def test_cabecalho_invalido_cai_na_conexao(atras_da_cloudflare):
    assert ip_do_cliente(requisicao({"CF-Connecting-IP": "198.51.100.9"})) == "198.51.100.9"
    assert ip_do_cliente(requisicao({"CF-Connecting-IP": "' or 1=1"})) == "10.0.0.7"
    assert ip_do_cliente(requisicao({})) == "10.0.0.7"
    assert ip_do_cliente(requisicao({"CF-Connecting-IP": "2001:DB8::1"})) == "2001:db8::1"  # forma canônica


def test_url_publica(monkeypatch):
    assert url_publica(requisicao({})) == "http://thermotrace-api.onrender.com"
    monkeypatch.setattr(obter_config(), "URL_PUBLICA", "https://thermotrace-api.onrender.com")
    assert url_publica(requisicao({})) == "https://thermotrace-api.onrender.com"


@pytest.mark.parametrize("valor", ["http://thermotrace-api.onrender.com", "https://x.com/portal",
                                   "https://x.com?a=1", "https://u@x.com"])
def test_url_publica_recusa_endereco_que_nao_seja_https_puro(valor):
    with pytest.raises(ValidationError):
        Config(SECRET_KEY="synthetic-only-test-key-0a1b2c3d4e5f6g7h8i9j", URL_PUBLICA=valor)


def test_relatorio_imprime_o_endereco_publico(ambiente, monkeypatch):  # noqa: F811
    monkeypatch.setattr(obter_config(), "URL_PUBLICA", "https://thermotrace-api.onrender.com")
    d = preparar_ingestao(ambiente, codigo='LINK-PUBLICO'); assert iniciar(d).status_code == 201
    coletar(d, PONTOS, 90, 'final')
    html = d[0].get(f"/api/v1/sessoes/{d[-1]['sessao_id']}/viagem?formato=html", headers=d[3]).text
    assert f"https://thermotrace-api.onrender.com/portal/conferir?sessao={d[-1]['sessao_id']}" in html
    assert "http://testserver" not in html


# Registro de acesso (Marco Civil, art. 15): a política promete guardar IP, data e hora.

def _login(c, u, ip):
    from tests.test_acesso_perfis import SENHA
    r = c.post("/api/v1/auth/login", json={"email": u.email, "senha": SENHA}, headers={"CF-Connecting-IP": ip})
    assert r.status_code == 200, r.text
    return r.json()


def test_login_grava_ip_na_sessao_e_na_auditoria(ambiente, atras_da_cloudflare):  # noqa: F811
    from sqlalchemy import select
    from app.models import LogAuditoria, SessaoAuth
    c, db, dados, _ = ambiente
    u, ip = dados["a"], ip_novo()
    _login(c, u, ip)
    db.expire_all()
    sessao = db.scalars(select(SessaoAuth).where(SessaoAuth.usuario_id == u.id)).all()[-1]
    assert str(sessao.ip) == ip
    log = db.scalar(select(LogAuditoria).where(LogAuditoria.usuario_id == u.id, LogAuditoria.acao == "login"))
    assert str(log.ip) == ip and log.em is not None


def test_renovacao_registra_o_ip_de_quem_renovou(ambiente, atras_da_cloudflare):  # noqa: F811
    from sqlalchemy import select
    from app.models import SessaoAuth
    c, db, dados, _ = ambiente
    u, ip_login, ip_refresh = dados["b"], ip_novo(), ip_novo()
    tokens = _login(c, u, ip_login)
    r = c.post("/api/v1/auth/refresh", json={"refresh_token": tokens["refresh_token"]},
               headers={"CF-Connecting-IP": ip_refresh})
    assert r.status_code == 200, r.text
    db.expire_all()
    ips = {str(s.ip) for s in db.scalars(select(SessaoAuth).where(SessaoAuth.usuario_id == u.id))}
    assert {ip_login, ip_refresh} <= ips


def test_login_recusado_tambem_fica_com_ip(ambiente, atras_da_cloudflare):  # noqa: F811
    from sqlalchemy import select
    from app.models import LogAuditoria
    c, db, dados, _ = ambiente
    ip = ip_novo()
    r = c.post("/api/v1/auth/login", json={"email": dados["a"].email, "senha": "errada-mas-longa-123"},
               headers={"CF-Connecting-IP": ip})
    assert r.status_code == 401
    db.expire_all()
    assert db.scalar(select(LogAuditoria).where(LogAuditoria.acao == "login_recusado", LogAuditoria.ip == ip))


def test_endereco_invalido_nao_vira_texto_na_coluna(ambiente):  # noqa: F811
    # Sem cabeçalho configurado, o TestClient se apresenta como "testclient": não é IP, fica vazio.
    from sqlalchemy import select
    from app.models import SessaoAuth
    c, db, dados, entrar = ambiente
    entrar("op")
    db.expire_all()
    assert db.scalars(select(SessaoAuth).where(SessaoAuth.usuario_id == dados["op"].id)).all()[-1].ip is None
