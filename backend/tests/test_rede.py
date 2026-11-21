"""IP do cliente e endereço público atrás do proxy do Render (Cloudflare na borda)."""
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
    return f"2001:db8::{secrets.token_hex(2)}:{secrets.token_hex(2)}"


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
