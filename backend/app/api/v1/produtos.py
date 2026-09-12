from typing import Annotated
from uuid import UUID
from fastapi import APIRouter, Depends, Header, Query
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.api.deps import Acesso, operador, gestor
from app.core.errors import NaoEncontrado
from app.db.session import obter_sessao
from app.models import ProdutoConfiguracao
from app.schemas.cargas import ConfigurarProduto, Codigo
from app.schemas.cargas_saida import ProdutoSaida
from app.services import cargas, idempotencia

Chave = Annotated[str, Header(alias="Idempotency-Key", min_length=16, max_length=160,
                             pattern=r"^[A-Za-z0-9._:-]+$")]
router = APIRouter(prefix="/produtos", tags=["Produtos e configuração rápida"])


@router.post("", status_code=201, response_model=ProdutoSaida)
def configurar(body: ConfigurarProduto, chave: Chave, acesso: Acesso = Depends(gestor),
               db: Session = Depends(obter_sessao)):
    return idempotencia.executar(db, acesso, chave, "/produtos", body,
                               lambda: cargas.configurar_produto(db, acesso, body), modelo_saida=ProdutoSaida)


@router.get("", response_model=list[ProdutoSaida])
def listar(limite: int = Query(50, ge=1, le=100), offset: int = Query(0, ge=0),
           acesso: Acesso = Depends(operador), db: Session = Depends(obter_sessao)):
    ps = db.scalars(select(ProdutoConfiguracao).where(ProdutoConfiguracao.empresa_id == acesso.empresa.id,
              ProdutoConfiguracao.ativo.is_(True)).order_by(ProdutoConfiguracao.codigo).limit(limite).offset(offset))
    return [cargas.produto_resumo(db, p) for p in ps]


@router.get("/localizar", response_model=ProdutoSaida)
def localizar(codigo: Codigo, acesso: Acesso = Depends(operador), db: Session = Depends(obter_sessao)):
    p = db.scalar(select(ProdutoConfiguracao).where(ProdutoConfiguracao.empresa_id == acesso.empresa.id,
                ProdutoConfiguracao.codigo == codigo, ProdutoConfiguracao.ativo.is_(True)))
    if p is None:
        raise NaoEncontrado("Produto ainda não configurado para sua empresa.")
    return cargas.produto_resumo(db, p)


@router.get("/{produto_id}", response_model=ProdutoSaida)
def obter(produto_id: UUID, acesso: Acesso = Depends(operador), db: Session = Depends(obter_sessao)):
    return cargas.produto_resumo(db, cargas.produto_por_id(db, acesso.empresa.id, produto_id))
