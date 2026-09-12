"""Laudo, arquivos, idempotência e trilha de auditoria."""

from datetime import datetime
from uuid import UUID

from sqlalchemy import (
    BigInteger,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    LargeBinary,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.dialects.postgresql import INET, JSONB
from sqlalchemy.dialects.postgresql import UUID as PgUUID
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base, CriadoMixin, IdMixin
from app.models.enums import CategoriaArquivo, VereditoLaudo
from app.models.tipos import enum_pg


class Arquivo(Base, IdMixin, CriadoMixin):
    """Laudo gerado, foto de evidência, certificado de calibração.

    Nunca servido por URL pública direta — sempre por endpoint autenticado.
    O `caminho` é relativo ao diretório configurado, para o backup e a
    migração para S3 não dependerem de caminho absoluto gravado no banco.
    """

    __tablename__ = "arquivo"

    empresa_id: Mapped[UUID | None] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("empresa.id")
    )
    nome_original: Mapped[str] = mapped_column(Text, nullable=False)
    caminho: Mapped[str] = mapped_column(Text, nullable=False)
    tipo_mime: Mapped[str] = mapped_column(String(128), nullable=False)
    tamanho_bytes: Mapped[int] = mapped_column(BigInteger, nullable=False)
    # Permite detectar corrupção e deduplicar upload repetido.
    sha256: Mapped[str] = mapped_column(String(64), nullable=False)
    categoria: Mapped[CategoriaArquivo] = mapped_column(
        enum_pg(CategoriaArquivo, "categoria_arquivo"), nullable=False
    )
    enviado_por: Mapped[UUID | None] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("usuario.id")
    )

    __table_args__ = (
        Index("ix_arquivo_empresa_categoria", "empresa_id", "categoria"),
        Index("ix_arquivo_sha256", "sha256"),
    )


class Laudo(Base, IdMixin, CriadoMixin):
    """Snapshot congelado. Imutável.

    O laudo não pode mudar se o mundo mudar depois. Uma reavaliação gera
    versão nova, e as duas coexistem.
    """

    __tablename__ = "laudo"

    remessa_id: Mapped[UUID] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("remessa.id"), nullable=False
    )
    versao: Mapped[int] = mapped_column(Integer, nullable=False, server_default="1")

    gerado_em: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False
    )
    gerado_por: Mapped[UUID | None] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("usuario.id")
    )

    versao_regra: Mapped[str] = mapped_column(String(32), nullable=False)
    versao_decodificador: Mapped[str] = mapped_column(String(64), nullable=False)
    veredito: Mapped[VereditoLaudo] = mapped_column(
        enum_pg(VereditoLaudo, "veredito_laudo"), nullable=False
    )

    payload: Mapped[dict] = mapped_column(JSONB, nullable=False)
    payload_sha256: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)

    # Código curto impresso no laudo, para conferência pública.
    # O portal de verificação está FORA do MVP (o cliente recebe por e-mail),
    # mas o código é gravado agora para não exigir migração depois.
    codigo_verificacao: Mapped[str] = mapped_column(
        String(24), nullable=False, unique=True
    )

    arquivo_xlsx_id: Mapped[UUID | None] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("arquivo.id")
    )
    arquivo_pdf_id: Mapped[UUID | None] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("arquivo.id")
    )

    __table_args__ = (
        UniqueConstraint("remessa_id", "versao", name="uq_laudo_remessa_versao"),
    )


class ChaveIdempotencia(Base, IdMixin, CriadoMixin):
    """Torna QUALQUER POST seguro para repetir, não só a ingestão de leitura.

    O app tem retry exponencial no WorkManager. Sem isto, uma resposta
    perdida na volta faz o aparelho reenviar e criar duplicata. Com isto, o
    reenvio recebe a mesma resposta da primeira vez.
    """

    __tablename__ = "chave_idempotencia"

    chave: Mapped[str] = mapped_column(String(160), nullable=False)
    empresa_id: Mapped[UUID | None] = mapped_column(PgUUID(as_uuid=True), ForeignKey("empresa.id"))
    requisicao_sha256: Mapped[str | None] = mapped_column(String(64))
    endpoint: Mapped[str] = mapped_column(String(128), nullable=False)
    usuario_id: Mapped[UUID | None] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("usuario.id")
    )
    resposta_status: Mapped[int | None] = mapped_column(Integer)
    resposta_corpo: Mapped[dict | None] = mapped_column(JSONB)

    __table_args__ = (
        Index("ix_chave_idempotencia_criado_em", "criado_em"),
        Index("uq_idempotencia_escopo", "empresa_id", "usuario_id", "endpoint", "chave", unique=True,
              postgresql_where=empresa_id.is_not(None)),
        Index("uq_idempotencia_legado", "chave", unique=True, postgresql_where=empresa_id.is_(None)),
    )


class LogAuditoria(Base):
    """Quem fez o quê. Append-only por trigger.

    `bigserial` e não UUID: o volume é alto e a ordem cronológica importa
    para a leitura da trilha.
    """

    __tablename__ = "log_auditoria"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    em: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False
    )
    usuario_id: Mapped[UUID | None] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("usuario.id")
    )
    empresa_id: Mapped[UUID | None] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("empresa.id")
    )
    dispositivo_id: Mapped[UUID | None] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("dispositivo.id")
    )

    acao: Mapped[str] = mapped_column(String(64), nullable=False)
    entidade: Mapped[str] = mapped_column(String(64), nullable=False)
    entidade_id: Mapped[UUID | None] = mapped_column(PgUUID(as_uuid=True))
    antes: Mapped[dict | None] = mapped_column(JSONB)
    depois: Mapped[dict | None] = mapped_column(JSONB)
    ip: Mapped[str | None] = mapped_column(INET)
    observacao: Mapped[str | None] = mapped_column(Text)

    __table_args__ = (
        Index("ix_log_auditoria_entidade", "entidade", "entidade_id", "em"),
        Index("ix_log_auditoria_em", "em"),
    )
