"""Base declarativa, mixins e tipos comuns.

Duas convenções vivem aqui e valem para todo o schema:

1. **Nomes de restrição previsíveis.** `naming_convention` faz o Postgres
   gerar `uq_etiqueta_nfc_uid` em vez de um nome aleatório. Sem isso, o
   Alembic não consegue remover uma restrição numa migração futura — e o
   tratador de `IntegrityError` não consegue reconhecer qual restrição
   disparou para dar uma mensagem decente.

2. **Tabela de evidência não tem `atualizado_em`.** A ausência da coluna é
   documentação executável: `leitura_etiqueta` não muda, e quem for escrever
   um `UPDATE` nela não encontra onde registrar a alteração.
"""

from datetime import datetime
from uuid import UUID

from sqlalchemy import DateTime, MetaData, func, text
from sqlalchemy.dialects.postgresql import UUID as PgUUID
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

CONVENCAO = {
    "ix": "ix_%(table_name)s_%(column_0_N_name)s",
    "uq": "uq_%(table_name)s_%(column_0_N_name)s",
    "ck": "ck_%(table_name)s_%(constraint_name)s",
    "fk": "fk_%(table_name)s_%(column_0_name)s",
    "pk": "pk_%(table_name)s",
}


class Base(DeclarativeBase):
    metadata = MetaData(naming_convention=CONVENCAO)


def coluna_id() -> Mapped[UUID]:
    return mapped_column(
        PgUUID(as_uuid=True),
        primary_key=True,
        server_default=text("gen_random_uuid()"),
    )


class IdMixin:
    """Chave primária UUID gerada pelo banco.

    UUID e não serial: o app cria registros offline e sobe depois. Com id
    sequencial do servidor, o aparelho não teria como referenciar o que
    acabou de criar até sincronizar.
    """

    id: Mapped[UUID] = mapped_column(
        PgUUID(as_uuid=True),
        primary_key=True,
        server_default=text("gen_random_uuid()"),
    )


class CriadoMixin:
    """Só criação. Para tabelas de evidência, que não mudam."""

    criado_em: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )


class TimestampMixin(CriadoMixin):
    """Criação e alteração. Para tabelas mutáveis.

    `atualizado_em` também é o cursor do *pull* de sincronização: o app pede
    `?desde=<iso>` e recebe só o que mudou.
    """

    atualizado_em: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
        onupdate=func.now(),
    )
