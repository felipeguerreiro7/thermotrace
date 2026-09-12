"""Configuração versionada do produto de cada cliente para operação rápida."""
from uuid import UUID
from sqlalchemy import Boolean, CheckConstraint, ForeignKey, Index, Integer, String, UniqueConstraint
from sqlalchemy.dialects.postgresql import UUID as PgUUID
from sqlalchemy.orm import Mapped, mapped_column
from app.db.base import Base, IdMixin, CriadoMixin


class ProdutoConfiguracao(Base, IdMixin, CriadoMixin):
    __tablename__ = "produto_configuracao"
    empresa_id: Mapped[UUID] = mapped_column(PgUUID(as_uuid=True), ForeignKey("empresa.id"), nullable=False)
    codigo: Mapped[str] = mapped_column(String(64), nullable=False)
    versao: Mapped[int] = mapped_column(Integer, nullable=False)
    perfil_termico_id: Mapped[UUID] = mapped_column(PgUUID(as_uuid=True), ForeignKey("perfil_termico.id"), nullable=False)
    intervalo_segundos: Mapped[int] = mapped_column(Integer, nullable=False)
    criado_por: Mapped[UUID] = mapped_column(PgUUID(as_uuid=True), ForeignKey("usuario.id"), nullable=False)
    ativo: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default="true")
    __table_args__ = (
        UniqueConstraint("empresa_id", "codigo", "versao", name="uq_produto_empresa_codigo_versao"),
        Index("uq_produto_empresa_codigo_ativo", "empresa_id", "codigo", unique=True, postgresql_where=ativo.is_(True)),
        CheckConstraint("intervalo_segundos BETWEEN 1 AND 86400", name="intervalo_valido"),
        CheckConstraint("versao > 0", name="versao_positiva"),
    )
