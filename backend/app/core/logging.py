"""Logs estruturados.

Em produção sai JSON, uma linha por evento, para a máquina ler. Em
desenvolvimento sai colorido, para você ler.

A regra que faz o log valer alguma coisa: **todo evento carrega o
`request_id`**. Quando o operador ligar dizendo "deu erro às 14h20 na doca",
você acha a requisição inteira com um `grep` — a entrada, o SQL, o erro e a
resposta, todos com o mesmo id.
"""

import logging
import sys
import uuid
from contextvars import ContextVar

import structlog

from app.core.config import obter_config

# Contexto por requisição. ContextVar funciona com async sem vazar entre
# requisições concorrentes — variável global comum não funcionaria.
_request_id: ContextVar[str] = ContextVar("request_id", default="-")


def definir_request_id(valor: str | None = None) -> str:
    rid = valor or uuid.uuid4().hex[:16]
    _request_id.set(rid)
    return rid


def obter_request_id() -> str:
    return _request_id.get()


def _injetar_request_id(_, __, evento: dict) -> dict:
    evento["request_id"] = obter_request_id()
    return evento


def configurar_logs() -> None:
    cfg = obter_config()

    logging.basicConfig(
        format="%(message)s",
        stream=sys.stdout,
        level=getattr(logging, cfg.NIVEL_LOG),
    )
    # O uvicorn duplica linhas de acesso; nosso middleware já registra
    # cada requisição com mais contexto.
    logging.getLogger("uvicorn.access").disabled = True

    processadores = [
        structlog.contextvars.merge_contextvars,
        _injetar_request_id,
        structlog.stdlib.add_log_level,
        structlog.processors.TimeStamper(fmt="iso", utc=True),
        structlog.processors.StackInfoRenderer(),
        structlog.processors.format_exc_info,
    ]

    if cfg.LOG_JSON:
        processadores.append(structlog.processors.JSONRenderer())
    else:
        processadores.append(structlog.dev.ConsoleRenderer(colors=True))

    structlog.configure(
        processors=processadores,
        wrapper_class=structlog.make_filtering_bound_logger(
            getattr(logging, cfg.NIVEL_LOG)
        ),
        logger_factory=structlog.stdlib.LoggerFactory(),
        cache_logger_on_first_use=True,
    )


def obter_logger(nome: str = "thermotrace"):
    return structlog.get_logger(nome)


# Campos que nunca podem aparecer no log, em nenhuma circunstância.
CAMPOS_SENSIVEIS = {
    "senha", "password", "senha_hash", "token", "access_token",
    "refresh_token", "authorization", "secret_key", "api_key",
}


def limpar_sensiveis(dados: dict) -> dict:
    """Troca valores sensíveis por '***' antes de logar.

    Vale para log de corpo de requisição. Um `senha` que cai no log fica lá
    para sempre, e o log costuma ir para lugares com menos controle de acesso
    que o banco.
    """
    return {
        k: ("***" if k.lower() in CAMPOS_SENSIVEIS else v)
        for k, v in dados.items()
    }
