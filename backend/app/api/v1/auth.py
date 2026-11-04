from fastapi import APIRouter, Depends, Request, Response
from sqlalchemy import select, update
from sqlalchemy.orm import Session

from app.api.deps import Acesso, autenticado
from app.core.errors import Conflito, DadosInvalidos, RegraDeNegocio
from app.core.security import agora
from app.db.session import obter_sessao
from app.models import SessaoAuth, Usuario
from app.models.enums import TipoEmpresa
from app.schemas.acesso import AtivarAcesso, CodigoMfa, Login, Renovacao
from app.services import autenticacao as service, codigo_acesso, mfa
from app.services.auditoria import auditar

router = APIRouter(prefix="/auth", tags=["Acesso"])


@router.post("/login")
def login(body: Login, request: Request, response: Response, db: Session = Depends(obter_sessao)):
    response.headers["Cache-Control"] = "no-store"
    # Não confiar em X-Forwarded-For arbitrário; proxy confiável configura request.client.
    return service.login(db, body.email, body.senha.get_secret_value(),
                         request.client.host if request.client else "desconhecido",
                         body.codigo_mfa.get_secret_value() if body.codigo_mfa else None)


@router.post("/mfa/iniciar")
def mfa_iniciar(response: Response, acesso: Acesso = Depends(autenticado), db: Session = Depends(obter_sessao)):
    """Gera segredo pendente. Só vale depois de confirmado com um código do app."""
    response.headers["Cache-Control"] = "no-store"
    user = db.scalar(select(Usuario).where(Usuario.id == acesso.usuario.id).with_for_update())
    if user.mfa_ativado_em:
        raise Conflito("A verificação em duas etapas já está ativa nesta conta.")
    user.mfa_segredo = mfa.novo_segredo(); user.mfa_ultimo_passo = None
    auditar(db, "mfa_configuracao_iniciada", "usuario", user.id, user)
    db.commit()
    grupos = " ".join(user.mfa_segredo[i:i+4] for i in range(0, len(user.mfa_segredo), 4))
    return {"chave": grupos, "uri": mfa.uri(user.mfa_segredo, user.email)}


@router.post("/mfa/confirmar", status_code=204)
def mfa_confirmar(body: CodigoMfa, acesso: Acesso = Depends(autenticado), db: Session = Depends(obter_sessao)):
    user = db.scalar(select(Usuario).where(Usuario.id == acesso.usuario.id).with_for_update())
    if user.mfa_ativado_em:
        raise Conflito("A verificação em duas etapas já está ativa nesta conta.")
    passo = mfa.verificar(user.mfa_segredo, body.codigo.get_secret_value(), None)
    if passo is None:
        raise DadosInvalidos("Código não confere. Confira a hora do celular e digite o código atual do app.")
    user.mfa_ativado_em = agora(); user.mfa_ultimo_passo = passo
    # Sessões abertas antes da ativação deixam de valer; esta continua.
    db.execute(update(SessaoAuth).where(SessaoAuth.usuario_id == user.id, SessaoAuth.revogada_em.is_(None),
               SessaoAuth.familia_id != acesso.sessao.familia_id).values(revogada_em=agora()))
    auditar(db, "mfa_ativada", "usuario", user.id, user)
    db.commit()


@router.post("/mfa/desativar", status_code=204)
def mfa_desativar(body: CodigoMfa, acesso: Acesso = Depends(autenticado), db: Session = Depends(obter_sessao)):
    if acesso.empresa.tipo == TipoEmpresa.PLATAFORMA:
        raise RegraDeNegocio("Na equipe ThermoTrace a verificação em duas etapas é obrigatória.")
    user = db.scalar(select(Usuario).where(Usuario.id == acesso.usuario.id).with_for_update())
    if not user.mfa_ativado_em:
        raise Conflito("A verificação em duas etapas não está ativa.")
    passo = mfa.verificar(user.mfa_segredo, body.codigo.get_secret_value(), user.mfa_ultimo_passo)
    if passo is None:
        raise DadosInvalidos("Código não confere ou já foi usado.")
    user.mfa_segredo = None; user.mfa_ativado_em = None; user.mfa_ultimo_passo = None
    auditar(db, "mfa_desativada", "usuario", user.id, user)
    db.commit()


@router.post("/refresh")
def refresh(body: Renovacao, response: Response, db: Session = Depends(obter_sessao)):
    response.headers["Cache-Control"] = "no-store"
    return service.renovar(db, body.refresh_token.get_secret_value())


@router.post("/ativar", status_code=204)
def ativar(body: AtivarAcesso, request: Request, response: Response, db: Session = Depends(obter_sessao)):
    """Primeiro acesso ou redefinição: e-mail + código de uso único + nova senha (WEB-11)."""
    response.headers["Cache-Control"] = "no-store"
    codigo_acesso.ativar(db, body.email, body.codigo.get_secret_value(), body.nova_senha.get_secret_value(),
                         request.client.host if request.client else "desconhecido")


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
            "empresa": acesso.empresa.razao_social, "tipo_empresa": acesso.empresa.tipo,
            "mfa_ativo": acesso.usuario.mfa_ativado_em is not None,
            "mfa_obrigatorio": acesso.empresa.tipo == TipoEmpresa.PLATAFORMA}
