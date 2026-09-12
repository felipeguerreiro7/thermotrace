"""Contrato público para integração do Android e portais."""
from datetime import datetime
from uuid import UUID
from pydantic import BaseModel
from app.models.enums import StatusRemessa, StatusVolume, TipoDocumento


class ProdutoSaida(BaseModel):
    id: UUID
    codigo: str
    versao: int
    ativo: bool
    perfil_termico_id: UUID
    intervalo_segundos: int
    disponivel_para_nova_carga: bool
    produto: str | None
    apresentacao: str | None
    condicao: str | None
    min_c: str | None
    max_c: str | None


class DocumentoSaida(BaseModel):
    id: UUID
    tipo: TipoDocumento
    numero: str
    serie: str | None
    chave_acesso: str | None
    cnpj_emitente: str | None
    conteudo_bruto: str | None
    digitado_manualmente: bool
    validado: bool
    observacao_validacao: str | None


class VolumeSaida(BaseModel):
    id: UUID
    sequencia: int
    identidade: str | None
    status: StatusVolume
    monitorado: bool


class RemessaSaida(BaseModel):
    id: UUID
    codigo: str
    status: StatusRemessa
    criado_em: datetime
    empresa_id: UUID
    destinatario_nome: str
    produto_configuracao_id: UUID | None
    perfil_termico_id: UUID
    intervalo_segundos: int


class DetalheRemessa(RemessaSaida):
    criterio: dict | None
    descricao_carga: str | None
    documentos: list[DocumentoSaida]
    volumes: list[VolumeSaida]


class LocalizacaoRemessa(BaseModel):
    candidatas: list[RemessaSaida]
    ha_mais: bool
