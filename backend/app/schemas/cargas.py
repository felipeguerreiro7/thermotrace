from typing import Annotated
from uuid import UUID
from pydantic import Field, StringConstraints, field_validator, model_validator
from app.schemas.acesso import Entrada
from app.models.enums import TipoDocumento

Codigo = Annotated[str, StringConstraints(strip_whitespace=True, to_upper=True,
                                         pattern=r"^[A-Za-z0-9][A-Za-z0-9._/-]{0,63}$")]


class ConfigurarProduto(Entrada):
    codigo: Codigo
    perfil_termico_id: UUID
    intervalo_segundos: int = Field(default=600, ge=1, le=86400, strict=True)
    versao_anterior: int | None = Field(default=None, ge=1, strict=True)


class NovoDocumento(Entrada):
    tipo: TipoDocumento
    numero: str = Field(min_length=1, max_length=64)
    chave_acesso: str | None = Field(default=None, pattern=r"^[A-Z0-9]{44}$")
    serie: str | None = Field(default=None, min_length=1, max_length=8)
    cnpj_emitente: str | None = Field(default=None, pattern=r"^[A-Z0-9]{12}[0-9]{2}$")
    conteudo_bruto: Annotated[str, StringConstraints(strip_whitespace=False, max_length=8192)] | None = None
    digitado_manualmente: bool = True

    @field_validator("numero", "serie", "cnpj_emitente", "chave_acesso", mode="before")
    @classmethod
    def normalizar(cls, value):
        return value.strip().upper() if isinstance(value, str) else value

    @field_validator("tipo")
    @classmethod
    def identificador_real(cls, value):
        if value == TipoDocumento.SEM_DOCUMENTO:
            raise ValueError("Sem documento não é um identificador; informe o código da remessa.")
        return value


class NovaRemessa(Entrada):
    codigo: Codigo | None = None
    produto_configuracao_id: UUID
    destinatario_nome: str = Field(min_length=2, max_length=240)
    descricao_carga: str | None = Field(default=None, max_length=1000)
    quantidade_volumes: int = Field(default=1, ge=1, le=200, strict=True)
    documentos: list[NovoDocumento] = Field(default_factory=list, max_length=30)

    @model_validator(mode="after")
    def identificada(self):
        if not self.codigo and not self.documentos:
            raise ValueError("Informe o código da carga ou pelo menos um documento.")
        return self


class MotivoAcao(Entrada):
    motivo: str = Field(min_length=10, max_length=2000)
