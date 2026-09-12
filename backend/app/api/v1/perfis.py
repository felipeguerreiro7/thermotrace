import json
from pathlib import Path
from uuid import UUID
from fastapi import APIRouter, Depends, Query
from sqlalchemy import select, func, text
from sqlalchemy.orm import Session

from app.api.deps import Acesso, autenticado, gestor
from app.core.errors import NaoEncontrado, Conflito
from app.core.security import agora
from app.db.session import obter_sessao
from app.models import PerfilTermico
from app.schemas.perfis import NovoPerfil, AprovarPerfil
from app.services.auditoria import auditar

router = APIRouter(prefix="/perfis", tags=["Perfis térmicos"])


def resumo(p):
    return {"id": p.id, "empresa_id": p.empresa_id, "codigo": p.codigo, "versao": p.versao,
            "rotulo": p.rotulo, "min_c": str(p.min_c), "max_c": str(p.max_c),
            "ativo": p.ativo, "aprovado_em": p.aprovado_em, "aprovado_por": p.aprovado_por,
            "evidencia": p.evidencia, "tolerancia_segundos": p.tolerancia_segundos}


def buscar(db, perfil_id, empresa_id):
    p = db.scalar(select(PerfilTermico).where(PerfilTermico.id == perfil_id,
            PerfilTermico.empresa_id == empresa_id).with_for_update())
    if p is None:
        raise NaoEncontrado()
    return p


@router.get("/modelos")
def modelos(_: Acesso = Depends(autenticado)):
    # Biblioteca de referência, nunca perfil operacional automaticamente aprovado.
    return json.loads((Path(__file__).parents[2] / "data" / "perfis_referencia.json").read_text(encoding="utf-8-sig"))


@router.get("")
def listar(somente_ativos: bool = True, limite: int = Query(50, ge=1, le=100),
           offset: int = Query(0, ge=0), acesso: Acesso = Depends(autenticado),
           db: Session = Depends(obter_sessao)):
    q = select(PerfilTermico).where(PerfilTermico.empresa_id == acesso.empresa.id)
    if somente_ativos:
        q = q.where(PerfilTermico.ativo.is_(True), PerfilTermico.aprovado_em.is_not(None))
    return [resumo(p) for p in db.scalars(q.order_by(PerfilTermico.codigo, PerfilTermico.versao)
                                         .limit(limite).offset(offset))]


@router.post("", status_code=201)
def criar(body: NovoPerfil, acesso: Acesso = Depends(gestor), db: Session = Depends(obter_sessao)):
    db.execute(text("SELECT pg_advisory_xact_lock(hashtextextended(:chave, 0))"),
               {"chave": f"perfil:{acesso.empresa.id}:{body.codigo}"})
    versao = (db.scalar(select(func.max(PerfilTermico.versao)).where(
        PerfilTermico.empresa_id == acesso.empresa.id, PerfilTermico.codigo == body.codigo)) or 0) + 1
    p = PerfilTermico(empresa_id=acesso.empresa.id, codigo=body.codigo, versao=versao,
                     rotulo=body.rotulo, min_c=body.min_c, max_c=body.max_c, ativo=False,
                     evidencia=body.evidencia.model_dump(mode="json"))
    db.add(p)
    db.flush()
    auditar(db, "perfil_rascunho_criado", "perfil_termico", p.id, acesso.usuario,
            depois={"codigo": p.codigo, "versao": p.versao})
    db.commit()
    return resumo(p)


@router.get("/{perfil_id}")
def obter(perfil_id: UUID, acesso: Acesso = Depends(autenticado), db: Session = Depends(obter_sessao)):
    return resumo(buscar(db, perfil_id, acesso.empresa.id))


@router.post("/{perfil_id}/aprovar")
def aprovar(perfil_id: UUID, body: AprovarPerfil, acesso: Acesso = Depends(gestor),
            db: Session = Depends(obter_sessao)):
    p = buscar(db, perfil_id, acesso.empresa.id)
    if p.aprovado_em or not p.evidencia:
        raise Conflito("Perfil já aprovado ou sem evidência; crie uma nova versão.")
    p.aprovado_em, p.aprovado_por, p.ativo = agora(), acesso.usuario.id, True
    auditar(db, "perfil_aprovado_internamente", "perfil_termico", p.id, acesso.usuario,
            depois={"declaracao": body.declaracao_responsavel, "versao": p.versao})
    db.commit()
    return resumo(p)


@router.post("/{perfil_id}/retirar")
def retirar(perfil_id: UUID, acesso: Acesso = Depends(gestor), db: Session = Depends(obter_sessao)):
    p = buscar(db, perfil_id, acesso.empresa.id)
    p.ativo = False
    auditar(db, "perfil_retirado_de_uso", "perfil_termico", p.id, acesso.usuario)
    db.commit()
    return resumo(p)
