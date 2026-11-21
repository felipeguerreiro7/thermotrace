"""Conferência pública de relatório de viagem (sem login). Ver app.services.conferencia."""
from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, Query, Request, Response
from sqlalchemy.orm import Session

from app.db.session import obter_sessao
from app.services import autenticacao, conferencia
from app.core.rede import ip_do_cliente

router = APIRouter(prefix="/conferencia", tags=["Conferência pública"])


@router.get("/viagem")
def conferir_viagem(request: Request, response: Response,
                    sessao: UUID,
                    hash: Annotated[str, Query(pattern=r"^[0-9a-fA-F]{64}$")],
                    db: Session = Depends(obter_sessao)):
    """Diz se este servidor emitiu um relatório de viagem com esta sessão e este hash."""
    response.headers["Cache-Control"] = "no-store"
    ip = ip_do_cliente(request)
    autenticacao.limitar(db, [("conferencia-ip:" + ip, 30)])
    return conferencia.conferir(db, sessao, hash.lower())
