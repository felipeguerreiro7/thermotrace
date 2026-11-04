from datetime import datetime
from typing import Literal
from uuid import UUID

from pydantic import BaseModel, Field, field_validator

from app.schemas.acesso import Entrada
from app.schemas.cargas_saida import RemessaSaida


class NovoCompartilhamento(Entrada):
    # Localiza a transportadora cadastrada; não valida dígitos porque só serve de chave exata.
    cnpj_parceira: str = Field(pattern=r"^[A-Z0-9]{12}[0-9]{2}$")
    pode_exportar: bool = False
    expira_em: datetime | None = None

    @field_validator("cnpj_parceira", mode="before")
    @classmethod
    def normalizar(cls, v):
        if isinstance(v, str):
            return v.strip().upper().replace(".", "").replace("/", "").replace("-", "")
        return v

    @field_validator("expira_em")
    @classmethod
    def com_fuso(cls, v):
        if v is not None and v.tzinfo is None:
            raise ValueError("Informe o prazo com fuso horário.")
        return v


class CompartilhamentoSaida(BaseModel):
    id: UUID
    remessa_id: UUID
    remessa_codigo: str
    empresa_dona_id: UUID
    empresa_dona: str | None
    empresa_parceira_id: UUID
    empresa_parceira: str | None
    papel: str
    escopo: list[Literal["consultar", "exportar"]]
    pode_exportar: bool
    situacao: Literal["pendente", "ativo", "recusado", "revogado", "expirado"]
    minha_parte: Literal["dona", "parceira"]
    criado_em: datetime
    expira_em: datetime | None
    aceito_em: datetime | None
    recusado_em: datetime | None
    revogado_em: datetime | None
    motivo_revogacao: str | None
    remessa: RemessaSaida | None


class Decisao(Entrada):
    """Corpo vazio explícito: a chave de idempotência protege o reenvio."""
