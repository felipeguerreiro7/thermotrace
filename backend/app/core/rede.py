"""Quem é o cliente e qual é o endereço público, quando a API roda atrás de proxy.

Um único lugar para os limites de tentativas e para o link do relatório, para que
nenhum ponto volte a usar `request.client.host` ou `request.base_url` direto.
"""
import ipaddress
from contextvars import ContextVar

from fastapi import Request

from app.core.config import obter_config


def ip_do_cliente(request: Request) -> str:
    """IP contado nos limites. Nunca lê X-Forwarded-For: no Render ele aceita valor forjado."""
    cabecalho = obter_config().IP_CLIENTE_CABECALHO
    if cabecalho:
        valor = request.headers.get(cabecalho, "").strip()
        try:
            return str(ipaddress.ip_address(valor))
        except ValueError:
            pass  # Sem o cabeçalho (ou inválido): cai no endereço da conexão, nunca em texto livre.
    return request.client.host if request.client else "desconhecido"


# IP da requisição em andamento, para gravar na sessão e na auditoria sem passar o
# Request por todos os serviços. Mesmo mecanismo do request_id (ContextVar).
_ip_atual: ContextVar[str | None] = ContextVar("ip_atual", default=None)


def definir_ip_atual(request: Request) -> None:
    """Chamado uma vez por requisição. Só guarda endereço válido: a coluna é INET."""
    try:
        _ip_atual.set(str(ipaddress.ip_address(ip_do_cliente(request))))
    except ValueError:
        _ip_atual.set(None)  # "desconhecido" ou cliente de teste: melhor vazio que texto livre.


def ip_atual() -> str | None:
    """Registro de acesso exigido pelo Marco Civil (art. 15): IP, data e hora."""
    return _ip_atual.get()


def url_publica(request: Request) -> str:
    """Base para links impressos (conferência). Configurada, não deduzida do proxy."""
    return obter_config().URL_PUBLICA or str(request.base_url).rstrip("/")
