"""TT-051: suporte da equipe ThermoTrace, liberado pelo dono da carga.

O gestor do cliente libera uma carga com motivo e prazo (até 72 h). Alguém da
equipe assume o pedido (fica o nome de quem assumiu) e passa a consultar a
carga pelas mesmas rotas de leitura do portal, sem exportar nem operar.
Cada consulta aparece na auditoria do dono. Qualquer parte encerra; o dono
encerra pela rota de revogação do compartilhamento.
"""
from typing import Literal
from uuid import UUID

from fastapi import APIRouter, Depends, Query
from sqlalchemy.orm import Session

from app.api.deps import Acesso, gestor, plataforma
from app.api.v1.produtos import Chave
from app.db.session import obter_sessao
from app.schemas.cargas import MotivoAcao
from app.schemas.compartilhamentos import CompartilhamentoSaida, Decisao, NovoSuporte
from app.services import compartilhamentos, idempotencia

router = APIRouter(tags=["Suporte autorizado"])
Situacao = Literal["pendente", "ativo", "recusado", "revogado", "expirado"]


@router.post("/remessas/{remessa_id}/suporte", status_code=201, response_model=CompartilhamentoSaida)
def liberar(remessa_id: UUID, body: NovoSuporte, chave: Chave,
            acesso: Acesso = Depends(gestor), db: Session = Depends(obter_sessao)):
    return idempotencia.executar(db, acesso, chave, f"/remessas/{remessa_id}/suporte", body,
        lambda: compartilhamentos.conceder_suporte(db, acesso, remessa_id, body), modelo_saida=CompartilhamentoSaida)


@router.get("/plataforma/suporte", response_model=list[CompartilhamentoSaida])
def pedidos(situacao: list[Situacao] | None = Query(None), limite: int = Query(50, ge=1, le=100),
            offset: int = Query(0, ge=0), acesso: Acesso = Depends(plataforma),
            db: Session = Depends(obter_sessao)):
    return compartilhamentos.recebidos(db, acesso, set(situacao or ["pendente", "ativo"]), limite, offset)


@router.post("/plataforma/suporte/{compartilhamento_id}/assumir", response_model=CompartilhamentoSaida)
def assumir(compartilhamento_id: UUID, body: Decisao, chave: Chave,
            acesso: Acesso = Depends(plataforma), db: Session = Depends(obter_sessao)):
    return idempotencia.executar(db, acesso, chave, f"/plataforma/suporte/{compartilhamento_id}/assumir", body,
        lambda: compartilhamentos.decidir(db, acesso, compartilhamento_id, True), status=200,
        modelo_saida=CompartilhamentoSaida)


@router.post("/plataforma/suporte/{compartilhamento_id}/encerrar", response_model=CompartilhamentoSaida)
def encerrar(compartilhamento_id: UUID, body: MotivoAcao, chave: Chave,
             acesso: Acesso = Depends(plataforma), db: Session = Depends(obter_sessao)):
    return idempotencia.executar(db, acesso, chave, f"/plataforma/suporte/{compartilhamento_id}/encerrar", body,
        lambda: compartilhamentos.revogar(db, acesso, compartilhamento_id, body), status=200,
        modelo_saida=CompartilhamentoSaida)
