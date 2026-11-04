from fastapi import APIRouter, Depends, Query
from sqlalchemy.orm import Session

from app.api.deps import Acesso, operador
from app.core.security import agora
from app.db.session import obter_sessao
from app.services import acompanhamento

router = APIRouter(tags=['Acompanhamento da operação'])


@router.get('/acompanhamento')
def painel(horas_sem_coleta: int = Query(24, ge=1, le=720),
           acesso: Acesso = Depends(operador), db: Session = Depends(obter_sessao)):
    """Sessões sem leitura final e saúde de envio dos aparelhos da própria empresa."""
    return acompanhamento.painel(db, acesso.empresa.id, agora(), horas_sem_coleta)
