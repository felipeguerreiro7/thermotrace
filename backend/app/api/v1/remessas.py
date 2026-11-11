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
from app.services import cargas, compartilhamentos, idempotencia

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
    rows = db.scalars(q.order_by(Remessa.criado_em.desc(), Remessa.id).limit(limite).offset(offset)).all()
    andamento = cargas.andamentos(db, rows)
    return [cargas.remessa_resumo(r, andamento[r.id]) for r in rows]


@router.get("/localizar", response_model=LocalizacaoRemessa)
def localizar(valor: str = Query(min_length=1, max_length=96),
              incluir_canceladas: bool = False, incluir_operaveis: bool = False, limite: int = Query(20, ge=1, le=100),
              offset: int = Query(0, ge=0), acesso: Acesso = Depends(operador),
              db: Session = Depends(obter_sessao)):
    valor = valor.strip().upper()
    match_doc = select(Documento.id).where(Documento.remessa_id == Remessa.id,
                  or_(Documento.numero == valor, Documento.chave_acesso == valor)).exists()
    escopo = Remessa.empresa_embarcador_id == acesso.empresa.id
    if incluir_operaveis:
        # Para o aplicativo de campo: também as cargas de outra empresa que esta pode bipar.
        escopo = or_(escopo, Remessa.id.in_(compartilhamentos.operaveis_por(acesso.empresa.id)))
    q = select(Remessa).where(escopo,
                            or_(Remessa.codigo == valor, Remessa.identidade_documento == valor, match_doc))
    if not incluir_canceladas:
        q = q.where(Remessa.status != StatusRemessa.CANCELADA)
    rows = db.scalars(q.order_by(Remessa.criado_em.desc(), Remessa.id).limit(limite+1).offset(offset)).all()
    # Sempre retorna candidatos. Nunca decide por uma entrega parcial silenciosamente.
    candidatas = []
    andamento = cargas.andamentos(db, rows[:limite])
    for r in rows[:limite]:
        comp = None if r.empresa_embarcador_id == acesso.empresa.id else compartilhamentos.vigente_para(db, r.id, acesso.empresa.id)
        if comp is not None:
            compartilhamentos.auditar_acesso(db, "remessa_localizada", "remessa", r.id, acesso, r, comp)
        candidatas.append(compartilhamentos.marcar_acesso(db, cargas.remessa_resumo(r, andamento[r.id]), comp))
    return {"candidatas": candidatas, "ha_mais": len(rows)>limite}


@router.get("/{remessa_id}", response_model=DetalheRemessa)
def obter(remessa_id: UUID, acesso: Acesso = Depends(operador), db: Session = Depends(obter_sessao)):
    r, comp = compartilhamentos.remessa_legivel(db, acesso, remessa_id)
    if comp is not None:
        # Consulta da própria empresa segue sem registro, como antes; a da parceira fica visível ao dono.
        compartilhamentos.auditar_acesso(db, "remessa_consultada", "remessa", r.id, acesso, r, comp)
    return compartilhamentos.marcar_acesso(db, cargas.detalhe_remessa(db, r), comp)


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
