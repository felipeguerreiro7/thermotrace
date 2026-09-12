from app.core.security import agora
from app.models import LogAuditoria


def auditar(db, acao, entidade, entidade_id=None, usuario=None, empresa_id=None, depois=None):
    db.add(LogAuditoria(em=agora(), usuario_id=usuario.id if usuario else None,
                       empresa_id=empresa_id or (usuario.empresa_id if usuario else None),
                       acao=acao, entidade=entidade, entidade_id=entidade_id, depois=depois))
