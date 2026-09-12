"""Remessa, documento fiscal, volume e vínculo com a etiqueta.

Remessa e volume têm UUID próprio. Cada remessa pode ter vários documentos;
o mesmo documento pode constar em entregas parciais diferentes. A etiqueta
é reutilizável e seu vínculo ao volume preserva o histórico de substituições.
"""

from datetime import datetime
from decimal import Decimal
from uuid import UUID

from sqlalchemy import (
    Boolean,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.dialects.postgresql import UUID as PgUUID
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db.base import Base, CriadoMixin, IdMixin, TimestampMixin
from app.models.enums import StatusRemessa, StatusVolume, TipoDocumento
from app.models.tipos import enum_pg


class Remessa(Base, IdMixin, TimestampMixin):
    __tablename__ = "remessa"

    codigo: Mapped[str] = mapped_column(String(64), nullable=False)

    empresa_embarcador_id: Mapped[UUID] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("empresa.id"), nullable=False
    )
    empresa_transportadora_id: Mapped[UUID | None] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("empresa.id")
    )

    # Referência legada preservada. Um documento pode acompanhar várias entregas.
    identidade_documento: Mapped[str | None] = mapped_column(
        String(96)
    )

    destinatario_nome: Mapped[str] = mapped_column(Text, nullable=False)
    destinatario_cnpj: Mapped[str | None] = mapped_column(String(14))
    destinatario_endereco: Mapped[dict | None] = mapped_column(JSONB)
    destinatario_contato: Mapped[dict | None] = mapped_column(JSONB)

    perfil_termico_id: Mapped[UUID] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("perfil_termico.id"), nullable=False
    )
    produto_configuracao_id: Mapped[UUID | None] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("produto_configuracao.id")
    )
    criterio_snapshot: Mapped[dict | None] = mapped_column(JSONB)
    intervalo_segundos: Mapped[int] = mapped_column(
        Integer, nullable=False, server_default="600"
    )

    previsao_coleta_em: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    previsao_entrega_em: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    descricao_carga: Mapped[str | None] = mapped_column(Text)

    status: Mapped[StatusRemessa] = mapped_column(
        enum_pg(StatusRemessa, "status_remessa"),
        nullable=False,
        server_default=StatusRemessa.PREPARACAO.value,
    )
    criada_por: Mapped[UUID | None] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("usuario.id")
    )

    documentos: Mapped[list["Documento"]] = relationship(back_populates="remessa")
    volumes: Mapped[list["Volume"]] = relationship(back_populates="remessa")

    __table_args__ = (
        UniqueConstraint(
            "empresa_embarcador_id", "codigo", name="uq_remessa_embarcador_codigo"
        ),
        Index("ix_remessa_transportadora_status", "empresa_transportadora_id", "status"),
        Index("ix_remessa_embarcador_criado", "empresa_embarcador_id", "criado_em"),
        # Cursor do pull de sincronização: o app pede ?desde=<iso> e recebe
        # só o que mudou.
        Index("ix_remessa_atualizado_em", "atualizado_em"),
    )


class Documento(Base, IdMixin, CriadoMixin):
    __tablename__ = "documento"

    remessa_id: Mapped[UUID] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("remessa.id", ondelete="CASCADE"), nullable=False
    )
    tipo: Mapped[TipoDocumento] = mapped_column(
        enum_pg(TipoDocumento, "tipo_documento"), nullable=False
    )
    numero: Mapped[str] = mapped_column(String(64), nullable=False)
    identidade_hash: Mapped[str | None] = mapped_column(String(64))

    # Chave de acesso de 44 dígitos (NF-e mod. 55, CT-e 57, MDF-e 58).
    chave_acesso: Mapped[str | None] = mapped_column(String(44))
    cnpj_emitente: Mapped[str | None] = mapped_column(String(14))
    serie: Mapped[str | None] = mapped_column(String(8))
    uf_emitente: Mapped[str | None] = mapped_column(String(2))
    competencia: Mapped[str | None] = mapped_column(String(7))  # AAAA-MM

    # O que a câmera leu, EXATAMENTE, antes de qualquer interpretação.
    # Se o parser melhorar, reprocessa-se. Guardar só os campos interpretados
    # torna um erro de parsing permanente.
    conteudo_bruto: Mapped[str | None] = mapped_column(Text)
    simbologia: Mapped[str | None] = mapped_column(String(24))
    digitado_manualmente: Mapped[bool] = mapped_column(
        Boolean, nullable=False, server_default="false"
    )

    # Validação de dígito verificador, feita offline no aparelho e reconferida
    # aqui. Não consulta a SEFAZ — a doca não tem sinal.
    validado: Mapped[bool] = mapped_column(
        Boolean, nullable=False, server_default="false"
    )
    observacao_validacao: Mapped[str | None] = mapped_column(Text)
    lido_em: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    remessa: Mapped[Remessa] = relationship(back_populates="documentos")

    __table_args__ = (
        Index("uq_documento_remessa_identidade", "remessa_id", "identidade_hash", unique=True,
              postgresql_where=identidade_hash.is_not(None)),
        Index("ix_documento_remessa_id", "remessa_id"),
        Index("ix_documento_chave_acesso", "chave_acesso"),
    )


class Volume(Base, IdMixin, TimestampMixin):
    __tablename__ = "volume"

    remessa_id: Mapped[UUID] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("remessa.id", ondelete="CASCADE"), nullable=False
    )
    sequencia: Mapped[int] = mapped_column(Integer, nullable=False)

    # Novas cargas: <UUID da remessa>#V001. Sobrevive à troca de etiqueta.
    identidade: Mapped[str | None] = mapped_column(String(110))
    codigo_externo: Mapped[str | None] = mapped_column(String(64))
    codigo_externo_bruto: Mapped[str | None] = mapped_column(Text)

    monitorado: Mapped[bool] = mapped_column(
        Boolean, nullable=False, server_default="false"
    )
    status: Mapped[StatusVolume] = mapped_column(
        enum_pg(StatusVolume, "status_volume"),
        nullable=False,
        server_default=StatusVolume.SEM_ETIQUETA.value,
    )
    descricao: Mapped[str | None] = mapped_column(Text)

    remessa: Mapped[Remessa] = relationship(back_populates="volumes")

    __table_args__ = (
        UniqueConstraint("remessa_id", "sequencia", name="uq_volume_remessa_sequencia"),
        Index("ix_volume_remessa_id", "remessa_id"),
    )


class VinculoEtiqueta(Base, IdMixin, CriadoMixin):
    """Histórico de qual etiqueta esteve em qual volume.

    Nunca sobrescrever: encerra-se o vínculo (`liberada_em`) e cria-se outro.
    Uma etiqueta trocada no meio do trajeto deixa rastro dos dois períodos.
    """

    __tablename__ = "vinculo_etiqueta"

    volume_id: Mapped[UUID] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("volume.id"), nullable=False
    )
    etiqueta_id: Mapped[UUID] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("etiqueta.id"), nullable=False
    )
    vinculada_por: Mapped[UUID | None] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("usuario.id")
    )
    dispositivo_id: Mapped[UUID | None] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("dispositivo.id")
    )

    # COMO a identidade física foi provada no momento do vínculo. É o que
    # separa "o operador escaneou o QR e o celular conferiu o UID" de
    # "alguém digitou". No laudo, os dois não valem a mesma coisa.
    qr_escaneado: Mapped[bool] = mapped_column(
        Boolean, nullable=False, server_default="false"
    )
    uid_conferiu: Mapped[bool] = mapped_column(
        Boolean, nullable=False, server_default="false"
    )

    liberada_em: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    motivo_liberacao: Mapped[str | None] = mapped_column(Text)

    __table_args__ = (
        # Uma etiqueta não pode estar em dois volumes ao mesmo tempo.
        # Índice parcial: o banco garante, a aplicação não precisa lembrar.
        Index(
            "uq_vinculo_etiqueta_ativo",
            "etiqueta_id",
            unique=True,
            postgresql_where=liberada_em.is_(None),
        ),
        Index("ix_vinculo_etiqueta_volume_id", "volume_id"),
        Index("uq_vinculo_volume_ativo", "volume_id", unique=True, postgresql_where=liberada_em.is_(None)),
    )
