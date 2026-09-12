from uuid import UUID
from fastapi import APIRouter, Depends, Query
from sqlalchemy import select, or_
from sqlalchemy.orm import Session

from app.api.deps import Acesso, operador, gestor
from app.api.v1.produtos import Chave
from app.db.session import obter_sessao
from app.models import Remessa, Documento
from app.models.enums import StatusRemessa
from app.schemas.cargas import NovaRemessa, NovoDocumento, MotivoAcao
from app.schemas.cargas_saida import RemessaSaida, DetalheRemessa, LocalizacaoRemessa, DocumentoSaida
from app.services import cargas, idempotencia

router = APIRouter(prefix="/remessas", tags=["Cargas e documentos"])


@router.post("", status_code=201, response_model=DetalheRemessa)
def criar(body: NovaRemessa, chave: Chave, acesso: Acesso = Depends(operador),
          db: Session = Depends(obter_sessao)):
    return idempotencia.executar(db, acesso, chave, "/remessas", body,
                               lambda: cargas.criar_remessa(db, acesso, body), modelo_saida=DetalheRemessa)


@router.get("", response_model=list[RemessaSaida])
def listar(limite: int = Query(50, ge=1, le=100), offset: int = Query(0, ge=0),
           status: StatusRemessa | None = None,
           acesso: Acesso = Depends(operador), db: Session = Depends(obter_sessao)):
    q = select(Remessa).where(Remessa.empresa_embarcador_id == acesso.empresa.id)
    if status is not None:
        q = q.where(Remessa.status == status)
    return [cargas.remessa_resumo(r) for r in db.scalars(q.order_by(Remessa.criado_em.desc(), Remessa.id)
                                                        .limit(limite).offset(offset))]


@router.get("/localizar", response_model=LocalizacaoRemessa)
def localizar(valor: str = Query(min_length=1, max_length=96),
              incluir_canceladas: bool = False, limite: int = Query(20, ge=1, le=100),
              offset: int = Query(0, ge=0), acesso: Acesso = Depends(operador),
              db: Session = Depends(obter_sessao)):
    valor = valor.strip().upper()
    match_doc = select(Documento.id).where(Documento.remessa_id == Remessa.id,
                  or_(Documento.numero == valor, Documento.chave_acesso == valor)).exists()
    q = select(Remessa).where(Remessa.empresa_embarcador_id == acesso.empresa.id,
                            or_(Remessa.codigo == valor, Remessa.identidade_documento == valor, match_doc))
    if not incluir_canceladas:
        q = q.where(Remessa.status != StatusRemessa.CANCELADA)
    rows = db.scalars(q.order_by(Remessa.criado_em.desc(), Remessa.id).limit(limite+1).offset(offset)).all()
    # Sempre retorna candidatos. Nunca decide por uma entrega parcial silenciosamente.
    return {"candidatas": [cargas.remessa_resumo(r) for r in rows[:limite]], "ha_mais": len(rows)>limite}


@router.get("/{remessa_id}", response_model=DetalheRemessa)
def obter(remessa_id: UUID, acesso: Acesso = Depends(operador), db: Session = Depends(obter_sessao)):
    return cargas.detalhe_remessa(db, cargas.remessa_por_id(db, acesso.empresa.id, remessa_id))


@router.post("/{remessa_id}/documentos", status_code=201, response_model=DocumentoSaida)
def documento(remessa_id: UUID, body: NovoDocumento, chave: Chave,
              acesso: Acesso = Depends(operador), db: Session = Depends(obter_sessao)):
    return idempotencia.executar(db, acesso, chave, f"/remessas/{remessa_id}/documentos", body,
                               lambda: cargas.adicionar_documento(db, acesso, remessa_id, body), modelo_saida=DocumentoSaida)


@router.post("/{remessa_id}/cancelar", response_model=RemessaSaida)
def cancelar(remessa_id: UUID, body: MotivoAcao, chave: Chave,
             acesso: Acesso = Depends(gestor), db: Session = Depends(obter_sessao)):
    return idempotencia.executar(db, acesso, chave, f"/remessas/{remessa_id}/cancelar", body,
                               lambda: cargas.cancelar_remessa(db, acesso, remessa_id, body), status=200,
                               modelo_saida=RemessaSaida)
