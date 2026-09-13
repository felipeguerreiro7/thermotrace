"""Ponto de entrada da API.

Este arquivo faz três coisas e nada mais: monta a aplicação, liga os
middlewares e registra as rotas. Regra de negócio nenhuma mora aqui.
"""

import time
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.responses import RedirectResponse
from fastapi.middleware.cors import CORSMiddleware

from app.api.v1 import router_v1
from app.core.config import obter_config
from app.core.errors import registrar_tratadores
from app.core.logging import configurar_logs, definir_request_id, obter_logger
from app.db.session import verificar_banco, runtime_sem_privilegios_administrativos

cfg = obter_config()
configurar_logs()
log = obter_logger("api")


@asynccontextmanager
async def ciclo_de_vida(_: FastAPI):
    if cfg.em_producao and not runtime_sem_privilegios_administrativos():
        raise RuntimeError("Produção exige usuário de banco sem superusuário, BYPASSRLS ou propriedade das tabelas.")
    log.info(
        "api_iniciando",
        versao=cfg.VERSAO,
        ambiente=cfg.AMBIENTE,
        prefixo=cfg.PREFIXO_API,
    )
    if not verificar_banco():
        # Não derruba o processo: em produção o banco pode subir alguns
        # segundos depois da API. O endpoint de saúde reporta a situação, e
        # o orquestrador decide se reinicia.
        log.error("banco_indisponivel_no_start")
    yield
    log.info("api_encerrando")


app = FastAPI(
    title=cfg.NOME_APP,
    version=cfg.VERSAO,
    description=(
        "API do ThermoTrace — monitoramento térmico de remessas por etiqueta NFC.\n\n"
        "Versão 0.4: acesso, perfis, cargas e recepção idempotente de evidências. "
        "Declarações de início/checkpoint/final preservadas com cadeia de integridade. "
        "Receber uma evidência não confirma o hardware nem libera uma carga. "
        "Fila Android e decodificação no servidor estão em implementação."
    ),
    docs_url="/docs" if not cfg.em_producao else None,
    redoc_url="/redoc" if not cfg.em_producao else None,
    openapi_url="/openapi.json" if not cfg.em_producao else None,
    lifespan=ciclo_de_vida,
)

# CORS existe para o futuro painel web. O app Android não é navegador e não
# usa CORS. Lista vazia = nada liberado, que é o padrão certo.
if cfg.ORIGENS_PERMITIDAS:
    app.add_middleware(
        CORSMiddleware,
        allow_origins=cfg.ORIGENS_PERMITIDAS,
        allow_credentials=True,
        allow_methods=["GET", "POST", "PATCH", "DELETE"],
        allow_headers=["Authorization", "Content-Type", "Idempotency-Key",
                       "X-Install-Id", "X-App-Version"],
    )


@app.middleware("http")
async def rastrear_requisicao(request: Request, proximo):
    """Um id por requisição, do começo ao fim.

    Quando o operador ligar dizendo "deu erro às 14h20 na doca", você acha a
    requisição inteira com um `grep` do request_id: a entrada, o erro e a
    resposta, todos com o mesmo identificador.
    """
    rid = definir_request_id(request.headers.get("x-request-id"))
    inicio = time.perf_counter()

    resposta = await proximo(request)

    duracao_ms = round((time.perf_counter() - inicio) * 1000, 1)
    resposta.headers["X-Request-Id"] = rid
    if request.headers.get("authorization") or "/auth/" in request.url.path or request.url.path.startswith("/portal"):
        resposta.headers["Cache-Control"] = "no-store"
    resposta.headers["X-Content-Type-Options"] = "nosniff"
    resposta.headers["Referrer-Policy"] = "no-referrer"
    if request.url.path == "/":
        resposta.headers["Vary"] = "Accept"
        resposta.headers["Cache-Control"] = "no-store"
    if request.url.path.startswith("/portal"):
        resposta.headers["Content-Security-Policy"] = (
            "default-src 'self'; script-src 'self'; style-src 'self'; "
            "connect-src 'self'; img-src 'self'; object-src 'none'; "
            "frame-ancestors 'none'; base-uri 'none'; form-action 'self'"
        )
        resposta.headers["Permissions-Policy"] = "camera=(), microphone=(), geolocation=()"

    # /saude é chamado pelo healthcheck a cada poucos segundos; logá-lo
    # afogaria o log de verdade.
    if not request.url.path.endswith("/saude"):
        log.info(
            "requisicao",
            metodo=request.method,
            caminho=request.url.path,
            status=resposta.status_code,
            duracao_ms=duracao_ms,
            install_id=request.headers.get("x-install-id"),
            versao_app=request.headers.get("x-app-version"),
        )
    return resposta


registrar_tratadores(app)
from app.core.limite_corpo import LimitarCorpo
app.add_middleware(LimitarCorpo)
app.include_router(router_v1, prefix=cfg.PREFIXO_API)
from app.portal.routes import registrar_portal
registrar_portal(app)


@app.get("/", include_in_schema=False)
def raiz(request: Request):
    if "text/html" in request.headers.get("accept", ""):
        return RedirectResponse("/portal", status_code=307)
    return {
        "nome": cfg.NOME_APP,
        "versao": cfg.VERSAO,
        "portal": "/portal",
        "documentacao": "/docs" if not cfg.em_producao else "indisponível em produção",
    }
