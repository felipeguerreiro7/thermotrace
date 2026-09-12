from fastapi import APIRouter, Depends, Request, Response
from sqlalchemy.orm import Session

from app.api.deps import Acesso, autenticado
from app.db.session import obter_sessao
from app.schemas.acesso import Login, Renovacao
from app.services import autenticacao as service
from app.services.auditoria import auditar

router = APIRouter(prefix="/auth", tags=["Acesso"])


@router.post("/login")
def login(body: Login, request: Request, response: Response, db: Session = Depends(obter_sessao)):
    response.headers["Cache-Control"] = "no-store"
    # Não confiar em X-Forwarded-For arbitrário; proxy confiável configura request.client.
    return service.login(db, body.email, body.senha.get_secret_value(),
                         request.client.host if request.client else "desconhecido")


@router.post("/refresh")
def refresh(body: Renovacao, response: Response, db: Session = Depends(obter_sessao)):
    response.headers["Cache-Control"] = "no-store"
    return service.renovar(db, body.refresh_token.get_secret_value())


@router.post("/logout", status_code=204)
def logout(acesso: Acesso = Depends(autenticado), db: Session = Depends(obter_sessao)):
    service.bloquear_familia(db, acesso.sessao.familia_id)
    service.revogar_familia(db, acesso.sessao.familia_id)
    auditar(db, "logout", "sessao_auth", acesso.sessao.id, acesso.usuario)
    db.commit()


@router.get("/me")
def me(acesso: Acesso = Depends(autenticado)):
    return {"id": acesso.usuario.id, "nome": acesso.usuario.nome, "email": acesso.usuario.email,
            "papel": acesso.usuario.papel, "empresa_id": acesso.empresa.id,
            "empresa": acesso.empresa.razao_social, "tipo_empresa": acesso.empresa.tipo}
