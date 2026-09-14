"""Recepção de declarações do aparelho; não certifica sensor nem decodifica temperaturas."""
from datetime import datetime, timezone
from decimal import Decimal
from typing import Annotated, Literal
from uuid import UUID
from pydantic import BaseModel, Field, StringConstraints, AwareDatetime, field_validator
from app.schemas.acesso import Entrada

UID = Annotated[str, StringConstraints(pattern=r'^(?:[0-9A-F]{2}){4,16}$')]
Bruto = Annotated[str, StringConstraints(strip_whitespace=False, max_length=131072)]


class RegistrarDispositivo(Entrada):
    install_id: UUID
    modelo: str = Field(min_length=1, max_length=128)
    versao_so: str = Field(min_length=1, max_length=64)
    versao_app: str = Field(min_length=1, max_length=64)


class Evidencia(Entrada):
    evento_id: UUID
    dispositivo_id: UUID
    lida_em: AwareDatetime
    uid_canonico: UID
    versao_sdk: str = Field(min_length=1, max_length=64)
    versao_decodificador: str = Field(min_length=1, max_length=64)
    resposta_bruta: list[Bruto] = Field(min_length=1, max_length=65547)
    origem: Literal['declaracao_android', 'simulacao']

    @field_validator('lida_em')
    @classmethod
    def data_utc(cls, v):
        if not 2000 <= v.year <= 2100:
            raise ValueError('Instante fora do intervalo do contrato.')
        return v.astimezone(timezone.utc)

    @field_validator('resposta_bruta')
    @classmethod
    def tamanho_total(cls, v):
        if sum(len(s.encode('utf-8')) for s in v) > 262144:
            raise ValueError('Evidência excede 256 KiB.')
        return v


class IniciarSessao(Entrada):
    sessao_id: UUID
    etiqueta_id: UUID
    evidencia: Evidencia
    epoch_inicio_etiqueta: int = Field(ge=946684800, le=4133980800, strict=True)
    delay_minutos: int = Field(default=0, ge=0, le=65535, strict=True)
    intervalo_segundos: int = Field(ge=1, le=65535, strict=True)
    quantidade_planejada: int = Field(ge=1, le=65535, strict=True)
    min_configurado_c: Decimal = Field(allow_inf_nan=False, max_digits=6, decimal_places=2)
    max_configurado_c: Decimal = Field(allow_inf_nan=False, max_digits=6, decimal_places=2)
    ativacao_confirmada: Literal[True]

    @field_validator('ativacao_confirmada', mode='before')
    @classmethod
    def confirmacao_booleana(cls, v):
        if v is not True: raise ValueError('Exige confirmação explícita de ativação pelo aparelho.')
        return v


class ReceberLeitura(Evidencia):
    tipo: Literal['checkpoint', 'final']
    epoch_inicio_etiqueta: int = Field(ge=946684800, le=4133980800, strict=True)
    intervalo_relatado_s: int = Field(ge=1, le=65535, strict=True)


class ReciboLeitura(BaseModel):
    leitura_id: UUID
    sessao_id: UUID
    evento_id: UUID
    empresa_id: UUID
    volume_id: UUID
    remessa_id: UUID
    tipo: str
    origem: Literal['declaracao_android', 'simulacao']
    ordem_recebimento: int
    recebida_em_servidor: datetime
    hash_payload: str
    hash_anterior: str | None
    hash_encadeado: str
    algoritmo_integridade: Literal['tt-evidencia-1'] = 'tt-evidencia-1'
    situacao: Literal['evidencia_recebida_sem_validacao_fisica'] = 'evidencia_recebida_sem_validacao_fisica'
    stop_fisico: Literal['nao_confirmado_pelo_servidor'] = 'nao_confirmado_pelo_servidor'
