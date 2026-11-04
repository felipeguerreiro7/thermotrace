"""WEB-10: compartilhamento de carga entre dono e transportadora."""
from typing import Literal
from uuid import UUID

from fastapi import APIRouter, Depends, Query
from sqlalchemy.orm import Session

from app.api.deps import Acesso, gestor, operador
from app.api.v1.produtos import Chave
from app.db.session import obter_sessao
from app.models.enums import PapelUsuario
from app.schemas.cargas import MotivoAcao
from app.schemas.compartilhamentos import CompartilhamentoSaida, Decisao, NovoCompartilhamento
from app.services import compartilhamentos, idempotencia

router = APIRouter(tags=["Compartilhamento de cargas"])
Situacao = Literal["pendente", "ativo", "recusado", "revogado", "expirado"]


@router.post("/remessas/{remessa_id}/compartilhamentos", status_code=201, response_model=CompartilhamentoSaida)
def convidar(remessa_id: UUID, body: NovoCompartilhamento, chave: Chave,
             acesso: Acesso = Depends(gestor), db: Session = Depends(obter_sessao)):
    return idempotencia.executar(db, acesso, chave, f"/remessas/{remessa_id}/compartilhamentos", body,
        lambda: compartilhamentos.convidar(db, acesso, remessa_id, body), modelo_saida=CompartilhamentoSaida)


@router.get("/remessas/{remessa_id}/compartilhamentos", response_model=list[CompartilhamentoSaida])
def da_remessa(remessa_id: UUID, acesso: Acesso = Depends(gestor), db: Session = Depends(obter_sessao)):
    return compartilhamentos.da_remessa(db, acesso, remessa_id)


@router.get("/compartilhamentos/recebidos", response_model=list[CompartilhamentoSaida])
def recebidos(situacao: list[Situacao] | None = Query(None), limite: int = Query(50, ge=1, le=100),
              offset: int = Query(0, ge=0), acesso: Acesso = Depends(operador),
              db: Session = Depends(obter_sessao)):
    pedidas = set(situacao or ["pendente", "ativo"])
    # Convite pendente e histórico são decisão do gestor; operador vê só o que pode consultar.
    if acesso.usuario.papel != PapelUsuario.GESTOR:
        pedidas &= {"ativo"}
    return compartilhamentos.recebidos(db, acesso, pedidas, limite, offset)


@router.post("/compartilhamentos/{compartilhamento_id}/aceitar", response_model=CompartilhamentoSaida)
def aceitar(compartilhamento_id: UUID, body: Decisao, chave: Chave,
            acesso: Acesso = Depends(gestor), db: Session = Depends(obter_sessao)):
    return idempotencia.executar(db, acesso, chave, f"/compartilhamentos/{compartilhamento_id}/aceitar", body,
        lambda: compartilhamentos.decidir(db, acesso, compartilhamento_id, True), status=200,
        modelo_saida=CompartilhamentoSaida)


@router.post("/compartilhamentos/{compartilhamento_id}/recusar", response_model=CompartilhamentoSaida)
def recusar(compartilhamento_id: UUID, body: Decisao, chave: Chave,
            acesso: Acesso = Depends(gestor), db: Session = Depends(obter_sessao)):
    return idempotencia.executar(db, acesso, chave, f"/compartilhamentos/{compartilhamento_id}/recusar", body,
        lambda: compartilhamentos.decidir(db, acesso, compartilhamento_id, False), status=200,
        modelo_saida=CompartilhamentoSaida)


@router.post("/compartilhamentos/{compartilhamento_id}/revogar", response_model=CompartilhamentoSaida)
def revogar(compartilhamento_id: UUID, body: MotivoAcao, chave: Chave,
            acesso: Acesso = Depends(gestor), db: Session = Depends(obter_sessao)):
    return idempotencia.executar(db, acesso, chave, f"/compartilhamentos/{compartilhamento_id}/revogar", body,
        lambda: compartilhamentos.revogar(db, acesso, compartilhamento_id, body), status=200,
        modelo_saida=CompartilhamentoSaida)
