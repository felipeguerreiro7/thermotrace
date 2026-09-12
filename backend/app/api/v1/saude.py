"""Saude da aplicacao.

Dois endpoints com propositos diferentes:

- `/saude`      barato, sem tocar no banco. E o que o balanceador usa para
                saber se o processo esta vivo.
- `/saude/completa`  verifica o banco. E o que voce olha quando algo esta
                estranho, e o que o healthcheck do Docker usa.

Separar os dois evita que uma lentidao no banco derrube o processo inteiro
por falha de liveness.
"""

from fastapi import APIRouter, status
from fastapi.responses import JSONResponse

from app.core.config import obter_config
from app.db.session import verificar_banco

router = APIRouter(tags=["saude"])
cfg = obter_config()


@router.get("/saude", summary="Processo esta vivo?")
def saude():
    return {"status": "ok", "versao": cfg.VERSAO, "ambiente": cfg.AMBIENTE}


@router.get("/saude/completa", summary="Processo e dependencias estao vivos?")
def saude_completa():
    banco_ok = verificar_banco()
    corpo = {
        "status": "ok" if banco_ok else "degradado",
        "versao": cfg.VERSAO,
        "ambiente": cfg.AMBIENTE,
        "dependencias": {"banco": "ok" if banco_ok else "indisponivel"},
    }
    return JSONResponse(
        status_code=status.HTTP_200_OK if banco_ok
        else status.HTTP_503_SERVICE_UNAVAILABLE,
        content=corpo,
    )
