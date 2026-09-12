"""Modelos SQLAlchemy.

Importar tudo aqui nao e estilo - e necessidade. O Alembic descobre as
tabelas por `Base.metadata`, e uma tabela cujo modulo nunca foi importado
simplesmente nao existe para o autogenerate. Ja vi migracao "vazia" por
causa disso.
"""

from app.db.base import Base
from app.models.seguranca import LimiteLogin
from app.models.produto import ProdutoConfiguracao
from app.models.catalogo import Etiqueta, LoteEtiqueta, PerfilTermico
from app.models.identidade import (
    Dispositivo,
    Empresa,
    SessaoAuth,
    TokenRecuperacao,
    Usuario,
)
from app.models.monitoramento import (
    Excursao,
    LeituraEtiqueta,
    SerieMedicao,
    SessaoMonitoramento,
)
from app.models.operacao import (
    AcaoCorretiva,
    DestinatarioAlerta,
    EnvioAlerta,
    EventoCustodia,
    Ocorrencia,
)
from app.models.remessa import Documento, Remessa, VinculoEtiqueta, Volume
from app.models.transversais import Arquivo, ChaveIdempotencia, Laudo, LogAuditoria

__all__ = [
    "Base",
    "Empresa", "Usuario", "Dispositivo", "SessaoAuth", "TokenRecuperacao",
    "LoteEtiqueta", "Etiqueta", "PerfilTermico", "ProdutoConfiguracao", "LimiteLogin",
    "Remessa", "Documento", "Volume", "VinculoEtiqueta",
    "SessaoMonitoramento", "LeituraEtiqueta", "SerieMedicao", "Excursao",
    "EventoCustodia", "Ocorrencia", "AcaoCorretiva",
    "DestinatarioAlerta", "EnvioAlerta",
    "Arquivo", "Laudo", "ChaveIdempotencia", "LogAuditoria",
]
