from dataclasses import dataclass
from uuid import UUID

import jwt
from fastapi import Depends
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy import select, text
from sqlalchemy.orm import Session

from app.core.errors import NaoAutenticado, SemPermissao
from app.core.security import agora, ler_token
from app.db.session import obter_sessao
from app.models import Empresa, Usuario, SessaoAuth
from app.models.enums import PapelUsuario, TipoEmpresa

bearer = HTTPBearer(auto_error=False)


@dataclass(frozen=True)
class Acesso:
    usuario: Usuario
    empresa: Empresa
    sessao: SessaoAuth


def autenticado(cred: HTTPAuthorizationCredentials | None = Depends(bearer),
                db: Session = Depends(obter_sessao)) -> Acesso:
    if cred is None:
        raise NaoAutenticado()
    try:
        claims = ler_token(cred.credentials)
        uid, sid = UUID(claims["sub"]), UUID(claims["sid"])
    except (jwt.PyJWTError, ValueError, TypeError, KeyError):
        raise NaoAutenticado() from None
    row = db.execute(select(Usuario, Empresa, SessaoAuth)
        .join(Empresa, Empresa.id == Usuario.empresa_id)
        .join(SessaoAuth, SessaoAuth.usuario_id == Usuario.id)
        .where(Usuario.id == uid, SessaoAuth.id == sid, Usuario.ativo.is_(True),
               Empresa.ativa.is_(True), SessaoAuth.revogada_em.is_(None),
               SessaoAuth.expira_em > agora())).one_or_none()
    if row is None:
        raise NaoAutenticado()
    # Escopo local à transação: não vaza para o próximo cliente no pool.
    db.execute(text("SELECT set_config('app.empresa_id', :id, true)"), {"id": str(row[1].id)})
    db.execute(text("SELECT set_config('app.usuario_id', :id, true)"), {"id": str(row[0].id)})
    return Acesso(*row)


def operador(acesso: Acesso = Depends(autenticado)) -> Acesso:
    if acesso.usuario.papel not in (PapelUsuario.OPERADOR, PapelUsuario.GESTOR) or acesso.empresa.tipo == TipoEmpresa.PLATAFORMA:
        raise SemPermissao()
    return acesso


def gestor(acesso: Acesso = Depends(autenticado)) -> Acesso:
    if acesso.usuario.papel != PapelUsuario.GESTOR:
        raise SemPermissao()
    return acesso


def plataforma(acesso: Acesso = Depends(autenticado)) -> Acesso:
    if acesso.usuario.papel != PapelUsuario.ADMIN or acesso.empresa.tipo != TipoEmpresa.PLATAFORMA:
        raise SemPermissao()
    return acesso
