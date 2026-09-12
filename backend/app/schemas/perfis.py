from datetime import date
from decimal import Decimal
from pydantic import Field, HttpUrl, model_validator
from app.schemas.acesso import Entrada


class EvidenciaPerfil(Entrada):
    produto: str = Field(min_length=2, max_length=240)
    apresentacao: str = Field(min_length=2, max_length=240)
    condicao: str = Field(min_length=2, max_length=500)
    fonte_url: HttpUrl
    fonte_titulo: str = Field(min_length=3, max_length=500)
    fonte_versao: str = Field(min_length=1, max_length=120)
    verificada_em: date
    justificativa: str = Field(min_length=10, max_length=2000)

    @model_validator(mode="after")
    def data_passada(self):
        if self.verificada_em > date.today():
            raise ValueError("A verificação não pode estar no futuro.")
        return self


class NovoPerfil(Entrada):
    codigo: str = Field(pattern=r"^[A-Z0-9][A-Z0-9_-]{1,47}$")
    rotulo: str = Field(min_length=2, max_length=160)
    min_c: Decimal = Field(ge=-200, le=150, max_digits=5, decimal_places=2, allow_inf_nan=False)
    max_c: Decimal = Field(ge=-200, le=150, max_digits=5, decimal_places=2, allow_inf_nan=False)
    evidencia: EvidenciaPerfil

    @model_validator(mode="after")
    def faixa(self):
        if self.min_c >= self.max_c:
            raise ValueError("O limite mínimo deve ser menor que o máximo.")
        return self


class AprovarPerfil(Entrada):
    declaracao_responsavel: str = Field(min_length=20, max_length=2000)
