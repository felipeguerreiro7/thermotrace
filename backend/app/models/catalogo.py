"""Lote de etiquetas, etiqueta e perfil térmico.

O catálogo é a resposta ao segundo problema que o app sozinho não resolve:
cada aparelho cadastra etiqueta separado, então dez celulares produzem dez
catálogos divergentes — e o par QR↔UID, que é a prova de identidade do
produto, deixa de valer entre eles.
"""

from datetime import date, datetime
from decimal import Decimal
from uuid import UUID

from sqlalchemy import (
    Boolean,
    CheckConstraint,
    Date,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.dialects.postgresql import ARRAY, JSONB
from sqlalchemy.dialects.postgresql import UUID as PgUUID
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db.base import Base, IdMixin, TimestampMixin
from app.models.enums import EstadoEtiqueta
from app.models.tipos import TemperaturaC, TensaoV, enum_pg


class LoteEtiqueta(Base, IdMixin, TimestampMixin):
    """A tabela mais importante do ponto de vista regulatório.

    Sem certificado de calibração rastreável, os ±0,5 °C do datasheet não
    valem como evidência numa auditoria de distribuição de medicamentos. É o
    maior risco aberto do produto, e ele mora aqui.
    """

    __tablename__ = "lote_etiqueta"

    fornecedor: Mapped[str] = mapped_column(Text, nullable=False)
    ordem_compra: Mapped[str | None] = mapped_column(Text)

    modelo_hardware: Mapped[str] = mapped_column(Text, nullable=False)  # MI8654TE
    part_number_ci: Mapped[str | None] = mapped_column(Text)            # FM13DT160
    fabricante_ci: Mapped[str | None] = mapped_column(Text)

    # Os dois mapas de comando do fornecedor gravam o relógio em endereços
    # diferentes (0x0140 no SDK e no iOS, 0x0020 no app DT160 9.3.5).
    # Escrever no endereço errado corrompe a memória da etiqueta.
    mapa_comandos: Mapped[str | None] = mapped_column(String(32))

    fabricado_em: Mapped[date | None] = mapped_column(Date)
    recebido_em: Mapped[date | None] = mapped_column(Date)
    quantidade: Mapped[int] = mapped_column(Integer, nullable=False)

    certificado_ref: Mapped[str | None] = mapped_column(Text)
    certificado_arquivo_id: Mapped[UUID | None] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("arquivo.id")
    )
    pontos_calibracao_c: Mapped[list[Decimal] | None] = mapped_column(
        ARRAY(TemperaturaC)
    )
    incerteza_c: Mapped[Decimal | None] = mapped_column(TemperaturaC)
    calibracao_valida_ate: Mapped[date | None] = mapped_column(Date)

    observacoes: Mapped[str | None] = mapped_column(Text)

    etiquetas: Mapped[list["Etiqueta"]] = relationship(back_populates="lote")

    __table_args__ = (
        CheckConstraint("quantidade > 0", name="quantidade_positiva"),
    )


class Etiqueta(Base, IdMixin, TimestampMixin):
    __tablename__ = "etiqueta"

    lote_id: Mapped[UUID] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("lote_etiqueta.id"), nullable=False
    )
    empresa_id: Mapped[UUID | None] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("empresa.id")
    )

    # Identificador COMERCIAL. É por ele que o operador escolhe a etiqueta.
    serial: Mapped[str] = mapped_column(String(64), nullable=False, unique=True)
    qr_payload: Mapped[str] = mapped_column(Text, nullable=False, unique=True)

    # Identificador TÉCNICO, em forma canônica (maiúsculas, sem separador).
    # Android e iOS devolvem o UID com os bytes invertidos — canonicalizar na
    # borda é obrigação da API, não sugestão. Sem isso a mesma etiqueta vira
    # dois registros e a conferência QR↔UID passa a recusar etiqueta válida.
    nfc_uid: Mapped[str] = mapped_column(String(32), nullable=False, unique=True)

    uhf_epc: Mapped[str | None] = mapped_column(String(64))
    uhf_tid: Mapped[str | None] = mapped_column(String(64))

    estado: Mapped[EstadoEtiqueta] = mapped_column(
        enum_pg(EstadoEtiqueta, "estado_etiqueta"),
        nullable=False,
        server_default=EstadoEtiqueta.ESTOQUE.value,
    )

    # Ciclo de vida: bateria de 5 mAh não recarregável, etiqueta reutilizável.
    ciclos_ativacao: Mapped[int] = mapped_column(
        Integer, nullable=False, server_default="0"
    )
    ultima_tensao_v: Mapped[Decimal | None] = mapped_column(TensaoV)
    ultima_tensao_em: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    aposentada_em: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    aposentada_motivo: Mapped[str | None] = mapped_column(Text)

    lote: Mapped[LoteEtiqueta] = relationship(back_populates="etiquetas")

    __table_args__ = (
        Index("ix_etiqueta_empresa_id", "empresa_id"),
        Index(
            "ix_etiqueta_em_uso",
            "estado",
            postgresql_where=estado != EstadoEtiqueta.APOSENTADA.value,
        ),
        Index("ix_etiqueta_atualizado_em", "atualizado_em"),  # cursor do pull
    )


class PerfilTermico(Base, IdMixin, TimestampMixin):
    """Versionado de propósito.

    Mudar a faixa de um perfil não pode reescrever o critério de laudos já
    emitidos. A remessa aponta para uma *versão* específica; alterar o perfil
    cria a versão seguinte.
    """

    __tablename__ = "perfil_termico"

    codigo: Mapped[str] = mapped_column(String(48), nullable=False)
    versao: Mapped[int] = mapped_column(Integer, nullable=False, server_default="1")
    rotulo: Mapped[str] = mapped_column(Text, nullable=False)

    min_c: Mapped[Decimal] = mapped_column(TemperaturaC, nullable=False)
    max_c: Mapped[Decimal] = mapped_column(TemperaturaC, nullable=False)
    alerta_min_c: Mapped[Decimal | None] = mapped_column(TemperaturaC)
    alerta_max_c: Mapped[Decimal | None] = mapped_column(TemperaturaC)

    # Tolerância só pode existir com justificativa específica do produto.
    # O cadastro inicial mantém zero; nunca descarta excursões por conveniência.
    tolerancia_segundos: Mapped[int] = mapped_column(
        Integer, nullable=False, server_default="0"
    )
    tor_max_segundos: Mapped[int | None] = mapped_column(Integer)
    mkt_limite_c: Mapped[Decimal | None] = mapped_column(TemperaturaC)

    # NULL = perfil global da plataforma; preenchido = perfil da empresa.
    empresa_id: Mapped[UUID | None] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("empresa.id")
    )
    ativo: Mapped[bool] = mapped_column(
        Boolean, nullable=False, server_default="false"
    )

    evidencia: Mapped[dict | None] = mapped_column(JSONB)
    aprovado_em: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    aprovado_por: Mapped[UUID | None] = mapped_column(PgUUID(as_uuid=True), ForeignKey("usuario.id"))

    __table_args__ = (
        Index("uq_perfil_cliente_versao", "empresa_id", "codigo", "versao", unique=True,
              postgresql_where=empresa_id.is_not(None)),
        Index("uq_perfil_global_versao", "codigo", "versao", unique=True,
              postgresql_where=empresa_id.is_(None)),
        CheckConstraint("min_c < max_c", name="faixa_coerente"),
        Index("ix_perfil_termico_empresa_id", "empresa_id"),
    )
