from fastapi import APIRouter, Depends, Query
from sqlalchemy.orm import Session

from app.api.deps import Acesso, operador
from app.core.security import agora
from app.db.session import obter_sessao
from app.services import acompanhamento
from app.services.auditoria import auditar

router = APIRouter(tags=['Acompanhamento da operação'])


@router.get('/acompanhamento')
def painel(horas_sem_coleta: int = Query(24, ge=1, le=720),
           acesso: Acesso = Depends(operador), db: Session = Depends(obter_sessao)):
    """Sessões sem leitura final (cargas próprias e as que a empresa pode operar) e envio dos aparelhos."""
    p = acompanhamento.painel(db, acesso.empresa.id, agora(), horas_sem_coleta)
    # O dono fica sabendo que a transportadora acompanhou as etiquetas da carga dele.
    por_dona = {}
    for s in p['sessoes_abertas']:
        if s['acesso'] == 'compartilhada':
            por_dona.setdefault(s['empresa_dona_id'], set()).add(str(s['remessa_id']))
    for dona, remessas in por_dona.items():
        auditar(db, 'acompanhamento_consultado', 'empresa', acesso.empresa.id, acesso.usuario, empresa_id=dona,
                depois={'empresa_acesso_id': str(acesso.empresa.id), 'remessas': sorted(remessas)})
    return p
