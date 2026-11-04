from uuid import UUID
from fastapi import APIRouter, Depends, Query, Response
from sqlalchemy import select, update
from sqlalchemy.orm import Session

from app.api.deps import Acesso, gestor, plataforma
from app.core.errors import Conflito, NaoEncontrado, RegraDeNegocio
from app.core.security import hasher, agora
from app.db.session import obter_sessao
from app.models import Empresa, Usuario, SessaoAuth, LogAuditoria
from app.models.enums import PapelUsuario, TipoEmpresa
from app.schemas.acesso import ConviteUsuario, NovoCliente, NovoUsuario
from app.services import codigo_acesso
from app.services.auditoria import auditar

router = APIRouter(tags=["Clientes e equipe"])


def usuario_resumo(u):
    acesso = "desativado" if not u.ativo else "aguardando_primeiro_acesso" if u.ultimo_login_em is None else "ativo"
    return {"id": u.id, "empresa_id": u.empresa_id, "nome": u.nome,
            "email": u.email, "papel": u.papel, "ativo": u.ativo, "acesso": acesso}


def adicionar_usuario(db, empresa_id, body, papel=None):
    email = str(body.email).strip().lower()
    if db.scalar(select(Usuario.id).where(Usuario.email == email)):
        raise Conflito("Já existe um acesso com este e-mail.")
    senha = getattr(body, "senha", None)
    user = Usuario(empresa_id=empresa_id, nome=body.nome, email=email,
                   senha_hash=hasher.hash(senha.get_secret_value()) if senha else codigo_acesso.senha_inutilizavel(),
                   papel=papel or body.papel)
    db.add(user)
    db.flush()
    return user


def _sem_cache(response):
    # Resposta com código de acesso: não guardar em cache, proxy ou histórico.
    response.headers["Cache-Control"] = "no-store"
    response.headers["Pragma"] = "no-cache"


@router.post("/plataforma/clientes", status_code=201)
def criar_cliente(body: NovoCliente, response: Response, acesso: Acesso = Depends(plataforma),
                  db: Session = Depends(obter_sessao)):
    if db.scalar(select(Empresa.id).where(Empresa.cnpj == body.cnpj)):
        raise Conflito("Já existe uma empresa com este CNPJ.")
    empresa = Empresa(razao_social=body.razao_social, cnpj=body.cnpj, tipo=body.tipo)
    db.add(empresa)
    db.flush()
    user = adicionar_usuario(db, empresa.id, body.gestor, PapelUsuario.GESTOR)
    auditar(db, "cliente_cadastrado", "empresa", empresa.id, acesso.usuario,
            empresa_id=empresa.id, depois={"gestor_id": str(user.id)})
    saida = {"id": empresa.id, "razao_social": empresa.razao_social, "gestor": usuario_resumo(user)}
    if not hasattr(body.gestor, "senha"):
        saida["acesso"] = codigo_acesso.gerar(db, user, "convite", acesso.usuario, empresa.id)
        _sem_cache(response)
    db.commit()
    return saida


@router.get("/plataforma/clientes")
def listar_clientes(limite: int = Query(50, ge=1, le=100), offset: int = Query(0, ge=0),
                    _: Acesso = Depends(plataforma), db: Session = Depends(obter_sessao)):
    empresas = list(db.scalars(select(Empresa).where(Empresa.tipo != TipoEmpresa.PLATAFORMA)
                               .order_by(Empresa.criado_em, Empresa.id).limit(limite).offset(offset)))
    gestores = {}
    for u in db.scalars(select(Usuario).where(Usuario.empresa_id.in_([e.id for e in empresas]),
                                              Usuario.papel == PapelUsuario.GESTOR).order_by(Usuario.criado_em)):
        gestores.setdefault(u.empresa_id, []).append(usuario_resumo(u))
    # Staff vê o cadastro e os gestores para suporte de acesso; nunca as cargas.
    return [{"id": e.id, "razao_social": e.razao_social, "cnpj": e.cnpj, "tipo": e.tipo, "ativa": e.ativa,
             "gestores": gestores.get(e.id, [])} for e in empresas]


@router.post("/plataforma/clientes/{empresa_id}/gestores/{usuario_id}/codigo-acesso", status_code=201)
def codigo_gestor(empresa_id: UUID, usuario_id: UUID, response: Response, acesso: Acesso = Depends(plataforma),
                  db: Session = Depends(obter_sessao)):
    user = db.scalar(select(Usuario).where(Usuario.id == usuario_id, Usuario.empresa_id == empresa_id,
                                           Usuario.papel == PapelUsuario.GESTOR).with_for_update())
    if user is None or db.get(Empresa, empresa_id).tipo == TipoEmpresa.PLATAFORMA:
        raise NaoEncontrado()
    if not user.ativo:
        raise RegraDeNegocio("Gestor desativado não recebe código de acesso.")
    # Auditado na empresa do cliente: o gestor vê que o suporte gerou um código.
    saida = codigo_acesso.gerar(db, user, "convite" if user.ultimo_login_em is None else "redefinicao",
                                acesso.usuario, empresa_id)
    _sem_cache(response)
    db.commit()
    return saida


@router.post("/usuarios/convites", status_code=201)
def convidar_usuario(body: ConviteUsuario, response: Response, acesso: Acesso = Depends(gestor),
                     db: Session = Depends(obter_sessao)):
    user = adicionar_usuario(db, acesso.empresa.id, body)
    auditar(db, "usuario_convidado", "usuario", user.id, acesso.usuario, depois={"papel": user.papel})
    saida = {"usuario": usuario_resumo(user), "acesso": codigo_acesso.gerar(db, user, "convite", acesso.usuario)}
    _sem_cache(response)
    db.commit()
    return saida


@router.post("/usuarios/{usuario_id}/codigo-acesso", status_code=201)
def novo_codigo(usuario_id: UUID, response: Response, acesso: Acesso = Depends(gestor),
                db: Session = Depends(obter_sessao)):
    user = db.scalar(select(Usuario).where(Usuario.id == usuario_id,
                         Usuario.empresa_id == acesso.empresa.id).with_for_update())
    if user is None:
        raise NaoEncontrado()
    if user.id == acesso.usuario.id or not user.ativo:
        raise RegraDeNegocio("Gere códigos para outras pessoas ativas da equipe.")
    if user.papel != PapelUsuario.OPERADOR and user.ultimo_login_em is not None:
        raise RegraDeNegocio("A redefinição do acesso de um gestor é feita pela equipe ThermoTrace.")
    saida = codigo_acesso.gerar(db, user, "convite" if user.ultimo_login_em is None else "redefinicao", acesso.usuario)
    _sem_cache(response)
    db.commit()
    return saida


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
    codigo_acesso.revogar_pendentes(db, user.id)
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
