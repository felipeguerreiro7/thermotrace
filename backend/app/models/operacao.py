"""Custódia, ocorrências, ações corretivas e alertas.

A etiqueta não tem rádio. Ela registra sozinha, mas não avisa ninguém — uma
excursão só é *descoberta* quando alguém encosta o celular. Por isso cada
ocorrência carrega três instantes distintos, e confundi-los é o que destrói a
credibilidade de um laudo.
"""

from datetime import datetime
from decimal import Decimal
from uuid import UUID

from sqlalchemy import (
    BigInteger,
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
from app.models.enums import (
    Gravidade,
    OrigemEvento,
    ParteCustodia,
    PapelUsuario,
    StatusEnvio,
    StatusOcorrencia,
    TipoAcaoCorretiva,
    TipoOcorrencia,
)
from app.models.tipos import TemperaturaC, enum_pg


class EventoCustodia(Base, IdMixin):
    __tablename__ = "evento_custodia"

    remessa_id: Mapped[UUID] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("remessa.id", ondelete="CASCADE"), nullable=False
    )
    de_parte: Mapped[ParteCustodia | None] = mapped_column(
        enum_pg(ParteCustodia, "parte_custodia")
    )
    para_parte: Mapped[ParteCustodia] = mapped_column(
        enum_pg(ParteCustodia, "parte_custodia"), nullable=False
    )
    de_empresa_id: Mapped[UUID | None] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("empresa.id")
    )
    para_empresa_id: Mapped[UUID | None] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("empresa.id")
    )

    # Os três tempos. "Entregue às 14h, lançado às 19h" é informação
    # diferente de "entregue às 19h", e a auditoria precisa distinguir.
    ocorrido_em: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False
    )
    registrado_em: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False
    )
    dispositivo_em: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    origem: Mapped[OrigemEvento] = mapped_column(
        enum_pg(OrigemEvento, "origem_evento"), nullable=False
    )

    volumes_confirmados: Mapped[int | None] = mapped_column(Integer)
    recebedor: Mapped[str | None] = mapped_column(Text)
    evidencia_arquivo_id: Mapped[UUID | None] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("arquivo.id")
    )
    observacao: Mapped[str | None] = mapped_column(Text)
    registrado_por: Mapped[UUID | None] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("usuario.id")
    )
    dispositivo_id: Mapped[UUID | None] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("dispositivo.id")
    )
    # Só quando o usuário autorizar. Dado pessoal sob a LGPD.
    geo: Mapped[dict | None] = mapped_column(JSONB)

    __table_args__ = (
        Index("ix_evento_custodia_remessa_ocorrido", "remessa_id", "ocorrido_em"),
    )


class Ocorrencia(Base, IdMixin, TimestampMixin):
    __tablename__ = "ocorrencia"

    remessa_id: Mapped[UUID] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("remessa.id", ondelete="CASCADE"), nullable=False
    )
    volume_id: Mapped[UUID | None] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("volume.id")
    )
    sessao_id: Mapped[UUID | None] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("sessao_monitoramento.id")
    )
    excursao_id: Mapped[UUID | None] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("excursao.id")
    )

    # sessao#epoch_inicio#tipo. Impede que reler a etiqueta no destino
    # duplique as ocorrências que o checkpoint já criou — e reenvie os
    # mesmos alertas, que é o jeito mais rápido de o cliente desligar a
    # notificação.
    chave_natural: Mapped[str] = mapped_column(String(128), nullable=False, unique=True)

    tipo: Mapped[TipoOcorrencia] = mapped_column(
        enum_pg(TipoOcorrencia, "tipo_ocorrencia"), nullable=False
    )
    gravidade: Mapped[Gravidade | None] = mapped_column(enum_pg(Gravidade, "gravidade"))
    status: Mapped[StatusOcorrencia] = mapped_column(
        enum_pg(StatusOcorrencia, "status_ocorrencia"),
        nullable=False,
        server_default=StatusOcorrencia.ABERTA.value,
    )

    titulo: Mapped[str] = mapped_column(Text, nullable=False)
    detalhe: Mapped[str | None] = mapped_column(Text)

    ocorrido_em: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False
    )
    detectado_em: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False
    )
    registrado_em: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False
    )

    # Congelados no momento da detecção de propósito: o laudo tem que mostrar
    # o que se sabia quando o alerta saiu, mesmo que uma leitura posterior
    # mude os números.
    pico_c: Mapped[Decimal | None] = mapped_column(TemperaturaC)
    limite_c: Mapped[Decimal | None] = mapped_column(TemperaturaC)
    duracao_segundos: Mapped[int | None] = mapped_column(BigInteger)
    versao_regra: Mapped[str] = mapped_column(String(32), nullable=False)

    alerta_enviado_em: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    alerta_canal: Mapped[str | None] = mapped_column(String(24))
    fechada_em: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    aberta_por: Mapped[UUID | None] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("usuario.id")
    )

    acoes: Mapped[list["AcaoCorretiva"]] = relationship(back_populates="ocorrencia")

    __table_args__ = (
        Index("ix_ocorrencia_remessa_ocorrido", "remessa_id", "ocorrido_em"),
        Index("ix_ocorrencia_status", "status"),
    )


class AcaoCorretiva(Base, IdMixin):
    """A ação acontece na estrada; o lançamento acontece quando dá.

    `ocorrido_em` é informado pelo usuário e pode ser retroativo.
    `registrado_em` é do sistema e não se edita. A distinção sustenta o laudo.
    """

    __tablename__ = "acao_corretiva"

    ocorrencia_id: Mapped[UUID] = mapped_column(
        PgUUID(as_uuid=True),
        ForeignKey("ocorrencia.id", ondelete="CASCADE"),
        nullable=False,
    )
    tipo: Mapped[TipoAcaoCorretiva] = mapped_column(
        enum_pg(TipoAcaoCorretiva, "tipo_acao_corretiva"), nullable=False
    )
    descricao: Mapped[str | None] = mapped_column(Text)

    ocorrido_em: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False
    )
    registrado_em: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False
    )
    origem: Mapped[OrigemEvento] = mapped_column(
        enum_pg(OrigemEvento, "origem_evento"), nullable=False
    )

    executada_por_nome: Mapped[str | None] = mapped_column(Text)
    empresa_id: Mapped[UUID | None] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("empresa.id")
    )
    evidencia_arquivo_id: Mapped[UUID | None] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("arquivo.id")
    )
    registrada_por: Mapped[UUID | None] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("usuario.id")
    )

    ocorrencia: Mapped[Ocorrencia] = relationship(back_populates="acoes")

    __table_args__ = (Index("ix_acao_corretiva_ocorrencia_id", "ocorrencia_id"),)


class DestinatarioAlerta(Base, IdMixin, TimestampMixin):
    __tablename__ = "destinatario_alerta"

    empresa_id: Mapped[UUID] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("empresa.id"), nullable=False
    )
    nome: Mapped[str] = mapped_column(Text, nullable=False)
    email: Mapped[str] = mapped_column(String(320), nullable=False)
    papel: Mapped[PapelUsuario | None] = mapped_column(
        enum_pg(PapelUsuario, "papel_usuario")
    )

    # Contra fadiga de alerta: o motorista não recebe desvio de 5 minutos;
    # o responsável técnico recebe tudo. Sem isso, ninguém lê os que importam.
    gravidade_minima: Mapped[Gravidade] = mapped_column(
        enum_pg(Gravidade, "gravidade"),
        nullable=False,
        server_default=Gravidade.ALERTA.value,
    )
    ativo: Mapped[bool] = mapped_column(
        Boolean, nullable=False, server_default="true"
    )

    __table_args__ = (
        UniqueConstraint("empresa_id", "email", name="uq_destinatario_empresa_email"),
    )


class EnvioAlerta(Base, IdMixin, CriadoMixin):
    """Fila de e-mail. Uma linha por destinatário.

    Sem granularidade por destinatário não dá para responder "o responsável
    técnico recebeu?" numa auditoria — e essa é exatamente a pergunta que
    aparece quando dá problema.
    """

    __tablename__ = "envio_alerta"

    ocorrencia_id: Mapped[UUID] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("ocorrencia.id"), nullable=False
    )
    destinatario_email: Mapped[str] = mapped_column(String(320), nullable=False)
    assunto: Mapped[str] = mapped_column(Text, nullable=False)
    corpo: Mapped[str] = mapped_column(Text, nullable=False)

    provedor: Mapped[str | None] = mapped_column(String(32))
    provedor_mensagem_id: Mapped[str | None] = mapped_column(String(128))

    status: Mapped[StatusEnvio] = mapped_column(
        enum_pg(StatusEnvio, "status_envio"),
        nullable=False,
        server_default=StatusEnvio.FILA.value,
    )
    tentativas: Mapped[int] = mapped_column(
        Integer, nullable=False, server_default="0"
    )
    ultimo_erro: Mapped[str | None] = mapped_column(Text)
    enviado_em: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    __table_args__ = (
        Index(
            "ix_envio_alerta_pendentes",
            "status",
            postgresql_where=status == StatusEnvio.FILA.value,
        ),
        Index("ix_envio_alerta_ocorrencia_id", "ocorrencia_id"),
    )
