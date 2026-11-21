"""Quem é o cliente e qual é o endereço público, quando a API roda atrás de proxy.

Um único lugar para os limites de tentativas e para o link do relatório, para que
nenhum ponto volte a usar `request.client.host` ou `request.base_url` direto.
"""
import ipaddress

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


def url_publica(request: Request) -> str:
    """Base para links impressos (conferência). Configurada, não deduzida do proxy."""
    return obter_config().URL_PUBLICA or str(request.base_url).rstrip("/")
