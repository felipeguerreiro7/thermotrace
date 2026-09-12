"""Empresa, usuário, dispositivo e sessões de autenticação.

Este grupo é o que faz o produto funcionar **entre duas empresas** — o
problema que o app sozinho não resolve: hoje o celular do destinatário não
conhece a remessa criada no celular do embarcador, e a barreira de identidade
recusa a leitura final.
"""

from datetime import datetime
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
    text,
)
from sqlalchemy.dialects.postgresql import INET
from sqlalchemy.dialects.postgresql import UUID as PgUUID
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db.base import Base, CriadoMixin, IdMixin, TimestampMixin
from app.models.enums import PapelUsuario, TipoEmpresa
from app.models.tipos import enum_pg


class Empresa(Base, IdMixin, TimestampMixin):
    __tablename__ = "empresa"

    razao_social: Mapped[str] = mapped_column(Text, nullable=False)
    nome_fantasia: Mapped[str | None] = mapped_column(Text)
    cnpj: Mapped[str] = mapped_column(String(14), nullable=False, unique=True)
    tipo: Mapped[TipoEmpresa] = mapped_column(
        enum_pg(TipoEmpresa, "tipo_empresa"), nullable=False
    )

    # Alavanca comercial: o cliente escolhe por quanto tempo guardamos.
    # No volume atual, guardar 5 anos custa quase nada — cobrar diferente
    # por retenção é decisão comercial, não recuperação de custo.
    retencao_meses: Mapped[int] = mapped_column(
        Integer, nullable=False, server_default="60"
    )
    ativa: Mapped[bool] = mapped_column(
        Boolean, nullable=False, server_default="true"
    )

    usuarios: Mapped[list["Usuario"]] = relationship(back_populates="empresa")
    dispositivos: Mapped[list["Dispositivo"]] = relationship(back_populates="empresa")

    __table_args__ = (
        Index("ix_empresa_ativa", "ativa", postgresql_where=ativa.is_(True)),
    )


class Usuario(Base, IdMixin, TimestampMixin):
    __tablename__ = "usuario"

    empresa_id: Mapped[UUID] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("empresa.id"), nullable=False
    )
    nome: Mapped[str] = mapped_column(Text, nullable=False)
    # Normalizado na entrada; índice funcional também bloqueia duplicata por caixa.
    email: Mapped[str] = mapped_column(
        String(320), nullable=False, unique=True
    )
    # Argon2id. Nunca a senha, nunca reversível, nunca no log.
    senha_hash: Mapped[str] = mapped_column(Text, nullable=False)
    papel: Mapped[PapelUsuario] = mapped_column(
        enum_pg(PapelUsuario, "papel_usuario"), nullable=False
    )
    ativo: Mapped[bool] = mapped_column(
        Boolean, nullable=False, server_default="true"
    )
    ultimo_login_em: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    empresa: Mapped[Empresa] = relationship(back_populates="usuarios")

    __table_args__ = (Index("ix_usuario_empresa_id", "empresa_id"),
                     Index("uq_usuario_email_normalizado", text("lower(TRIM(BOTH FROM email))"), unique=True))


class Dispositivo(Base, IdMixin, TimestampMixin):
    """Cada celular que ativa ou lê etiqueta é um instrumento de medição.

    Precisa aparecer no laudo — o auditor pergunta com qual aparelho o dado
    foi coletado. E precisa poder ser revogado sozinho quando o celular
    sumir, sem trocar a senha de todo mundo.
    """

    __tablename__ = "dispositivo"

    empresa_id: Mapped[UUID] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("empresa.id"), nullable=False
    )
    registrado_por: Mapped[UUID | None] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("usuario.id")
    )
    # UUID gerado pelo app na primeira execução. NÃO é IMEI nem Android ID:
    # identificador de hardware é dado pessoal sob a LGPD e não precisamos dele.
    install_id: Mapped[str] = mapped_column(String(64), nullable=False)

    modelo: Mapped[str | None] = mapped_column(Text)
    versao_so: Mapped[str | None] = mapped_column(Text)
    versao_app: Mapped[str | None] = mapped_column(Text)
    pilha_nfc: Mapped[str | None] = mapped_column(String(16))  # nfc_v | nfc_a

    ativo: Mapped[bool] = mapped_column(
        Boolean, nullable=False, server_default="true"
    )
    primeiro_acesso_em: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    ultimo_acesso_em: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    empresa: Mapped[Empresa] = relationship(back_populates="dispositivos")

    __table_args__ = (
        UniqueConstraint("empresa_id", "install_id", name="uq_dispositivo_empresa_install"),
        Index("ix_dispositivo_ativo", "ativo", postgresql_where=ativo.is_(True)),
    )


class SessaoAuth(Base, IdMixin, CriadoMixin):
    """Refresh tokens.

    Guardamos o **hash** do token, não o token. Um dump vazado do banco não
    vira sessão válida — mesma lógica de senha.

    `familia_id` implementa rotação com detecção de reuso: se um refresh já
    usado aparecer de novo, foi roubado, e a família inteira é revogada.
    """

    __tablename__ = "sessao_auth"

    usuario_id: Mapped[UUID] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("usuario.id"), nullable=False
    )
    dispositivo_id: Mapped[UUID | None] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("dispositivo.id")
    )
    token_hash: Mapped[str] = mapped_column(String(64), nullable=False, unique=True)
    familia_id: Mapped[UUID] = mapped_column(PgUUID(as_uuid=True), nullable=False)
    expira_em: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    revogada_em: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    ip: Mapped[str | None] = mapped_column(INET)
    user_agent: Mapped[str | None] = mapped_column(Text)

    __table_args__ = (
        Index("ix_sessao_auth_usuario_id", "usuario_id"),
        Index("ix_sessao_auth_familia_id", "familia_id"),
        Index(
            "ix_sessao_auth_vigentes",
            "expira_em",
            postgresql_where=revogada_em.is_(None),
        ),
    )


class TokenRecuperacao(Base, IdMixin, CriadoMixin):
    """Recuperação de senha. Uso único, 30 minutos."""

    __tablename__ = "token_recuperacao"

    usuario_id: Mapped[UUID] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("usuario.id"), nullable=False
    )
    token_hash: Mapped[str] = mapped_column(String(64), nullable=False, unique=True)
    expira_em: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    usado_em: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    __table_args__ = (Index("ix_token_recuperacao_usuario_id", "usuario_id"),)
