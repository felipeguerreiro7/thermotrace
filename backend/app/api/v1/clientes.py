from uuid import UUID
from fastapi import APIRouter, Depends, Query
from sqlalchemy import select, update
from sqlalchemy.orm import Session

from app.api.deps import Acesso, gestor, plataforma
from app.core.errors import NaoEncontrado, RegraDeNegocio
from app.core.security import hasher, agora
from app.db.session import obter_sessao
from app.models import Empresa, Usuario, SessaoAuth, LogAuditoria
from app.models.enums import PapelUsuario, TipoEmpresa
from app.schemas.acesso import NovoCliente, NovoUsuario
from app.services.auditoria import auditar

router = APIRouter(tags=["Clientes e equipe"])


def usuario_resumo(u):
    return {"id": u.id, "empresa_id": u.empresa_id, "nome": u.nome,
            "email": u.email, "papel": u.papel, "ativo": u.ativo}


def adicionar_usuario(db, empresa_id, body, papel=None):
    user = Usuario(empresa_id=empresa_id, nome=body.nome, email=str(body.email).strip().lower(),
                   senha_hash=hasher.hash(body.senha.get_secret_value()), papel=papel or body.papel)
    db.add(user)
    db.flush()
    return user


@router.post("/plataforma/clientes", status_code=201)
def criar_cliente(body: NovoCliente, acesso: Acesso = Depends(plataforma),
                  db: Session = Depends(obter_sessao)):
    empresa = Empresa(razao_social=body.razao_social, cnpj=body.cnpj, tipo=body.tipo)
    db.add(empresa)
    db.flush()
    user = adicionar_usuario(db, empresa.id, body.gestor, PapelUsuario.GESTOR)
    auditar(db, "cliente_cadastrado", "empresa", empresa.id, acesso.usuario,
            empresa_id=empresa.id, depois={"gestor_id": str(user.id)})
    db.commit()
    return {"id": empresa.id, "razao_social": empresa.razao_social, "gestor": usuario_resumo(user)}


@router.get("/plataforma/clientes")
def listar_clientes(limite: int = Query(50, ge=1, le=100), offset: int = Query(0, ge=0),
                    _: Acesso = Depends(plataforma), db: Session = Depends(obter_sessao)):
    return [{"id": e.id, "razao_social": e.razao_social, "cnpj": e.cnpj, "ativa": e.ativa}
            for e in db.scalars(select(Empresa).where(Empresa.tipo != TipoEmpresa.PLATAFORMA)
                               .order_by(Empresa.criado_em, Empresa.id).limit(limite).offset(offset))]


@router.get("/usuarios")
def listar_usuarios(limite: int = Query(50, ge=1, le=100), offset: int = Query(0, ge=0),
                    acesso: Acesso = Depends(gestor), db: Session = Depends(obter_sessao)):
    return [usuario_resumo(u) for u in db.scalars(select(Usuario).where(
        Usuario.empresa_id == acesso.empresa.id).order_by(Usuario.criado_em, Usuario.id).limit(limite).offset(offset))]


@router.post("/usuarios", status_code=201)
def criar_usuario(body: NovoUsuario, acesso: Acesso = Depends(gestor), db: Session = Depends(obter_sessao)):
    user = adicionar_usuario(db, acesso.empresa.id, body)
    auditar(db, "usuario_criado", "usuario", user.id, acesso.usuario, depois={"papel": user.papel})
    db.commit()
    return usuario_resumo(user)


@router.post("/usuarios/{usuario_id}/desativar", status_code=204)
def desativar_usuario(usuario_id: UUID, acesso: Acesso = Depends(gestor), db: Session = Depends(obter_sessao)):
    user = db.scalar(select(Usuario).where(Usuario.id == usuario_id,
                         Usuario.empresa_id == acesso.empresa.id).with_for_update())
    if user is None:
        raise NaoEncontrado()
    if user.id == acesso.usuario.id or user.papel != PapelUsuario.OPERADOR:
        raise RegraDeNegocio("Este fluxo desativa operadores; alteração de gestores exige suporte.")
    user.ativo = False
    db.execute(update(SessaoAuth).where(SessaoAuth.usuario_id == user.id,
               SessaoAuth.revogada_em.is_(None)).values(revogada_em=agora()))
    auditar(db, "usuario_desativado", "usuario", user.id, acesso.usuario)
    db.commit()


@router.get("/auditoria")
def auditoria(limite: int = Query(50, ge=1, le=100), depois_id: int = Query(0, ge=0),
               acesso: Acesso = Depends(gestor), db: Session = Depends(obter_sessao)):
    # Cursor estável; não retorna hashes, tokens, e-mails de tentativas ou segredos.
    rows = db.scalars(select(LogAuditoria).where(LogAuditoria.empresa_id == acesso.empresa.id,
                      LogAuditoria.id > depois_id).order_by(LogAuditoria.id).limit(limite))
    return [{"id": e.id, "em": e.em, "usuario_id": e.usuario_id, "acao": e.acao,
             "entidade": e.entidade, "entidade_id": e.entidade_id, "depois": e.depois} for e in rows]
