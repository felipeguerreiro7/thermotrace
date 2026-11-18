from fastapi import APIRouter, Depends, Response
from sqlalchemy.orm import Session

from app.api.deps import Acesso, operador
from app.core.security import agora
from app.db.session import obter_sessao
from app.services import conformidade
from app.services.auditoria import auditar

router = APIRouter(tags=['Conformidade térmica'])


@router.get('/conformidade')
def painel(response: Response, acesso: Acesso = Depends(operador), db: Session = Depends(obter_sessao)):
    """Viagens recentes das cargas da empresa: conformes, com excursão, divergentes, em andamento."""
    response.headers['Cache-Control'] = 'no-store'
    p = conformidade.painel(db, acesso.empresa.id, agora())
    auditar(db, 'conformidade_consultada', 'empresa', acesso.empresa.id, acesso.usuario,
            depois={'viagens': p['totais']['viagens'], 'versao': p['versao']})
    db.commit()
    return p
