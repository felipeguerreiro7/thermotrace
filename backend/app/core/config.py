"""Configuração da aplicação.

Tudo que muda entre a sua máquina e o servidor vem de variável de ambiente.
Nada de segredo no código — nem em comentário, nem em valor padrão.

A validação acontece na importação: se faltar uma variável obrigatória ou se
o segredo for fraco, o processo morre no start com mensagem clara, em vez de
subir e falhar meia hora depois numa requisição qualquer.
"""

from functools import lru_cache
from typing import Literal

from pydantic import Field, PostgresDsn, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Config(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )

    # ---- aplicação -------------------------------------------------
    NOME_APP: str = "ThermoTrace API"
    VERSAO: str = "0.5.0"
    AMBIENTE: Literal["local", "homologacao", "producao"] = "local"
    DEBUG: bool = False

    # Prefixo de todas as rotas. O celular em campo não atualiza rápido:
    # a API precisa evoluir sem quebrar o aparelho que está na doca.
    PREFIXO_API: str = "/api/v1"

    # ---- banco -----------------------------------------------------
    DATABASE_URL: PostgresDsn
    DB_POOL_SIZE: int = 5
    DB_MAX_OVERFLOW: int = 10
    DB_ECHO: bool = False

    # ---- segurança (usado a partir da Fase 4) ----------------------
    SECRET_KEY: str = Field(min_length=32)
    ACCESS_TOKEN_MINUTOS: int = Field(default=15, ge=1, le=60)
    REFRESH_TOKEN_DIAS: int = Field(default=30, ge=1, le=90)

    # ---- CORS ------------------------------------------------------
    # O app Android não usa CORS (não é navegador). Isto existe para o
    # futuro painel web. Lista vazia = nenhuma origem liberada.
    ORIGENS_PERMITIDAS: list[str] = []

    # ---- armazenamento de arquivos ---------------------------------
    DIRETORIO_ARQUIVOS: str = "./dados/arquivos"
    TAMANHO_MAXIMO_MB: int = 25

    # ---- e-mail (usado a partir da Fase 5) -------------------------
    RESEND_API_KEY: str | None = None
    EMAIL_REMETENTE: str = "alertas@exemplo.com.br"
    EMAIL_NOME_REMETENTE: str = "ThermoTrace"

    # ---- logs ------------------------------------------------------
    NIVEL_LOG: Literal["DEBUG", "INFO", "WARNING", "ERROR"] = "INFO"
    LOG_JSON: bool = True

    @field_validator("SECRET_KEY")
    @classmethod
    def _segredo_precisa_ser_real(cls, v: str) -> str:
        proibidos = {
            "troque-me", "changeme", "secret", "sua-chave-secreta-aqui",
            "mude-esta-chave", "development", "test",
        }
        if v.lower() in proibidos or len(set(v)) < 8:
            raise ValueError(
                "SECRET_KEY é o valor de exemplo ou é fraca demais. "
                "Gere uma real com:  python -c \"import secrets; print(secrets.token_urlsafe(48))\""
            )
        return v

    @property
    def database_url_sync(self) -> str:
        """URL no formato que o SQLAlchemy/psycopg espera.

        Provedores gerenciados (Render, Heroku, Neon) entregam a string como
        ``postgresql://`` ou ``postgres://``, sem driver. Sem o ``+psycopg``, o
        SQLAlchemy escolhe psycopg2, que nao esta no requirements — e o erro so
        aparece no start do contêiner, ja em deploy. Normalizar aqui evita
        depender de alguem lembrar de editar a URL a mao no painel.
        """
        url = str(self.DATABASE_URL)
        if "+" in url.split("://", 1)[0]:
            return url
        esquema, _, resto = url.partition("://")
        return f"postgresql+psycopg://{resto}"

    @property
    def em_producao(self) -> bool:
        return self.AMBIENTE == "producao"


@lru_cache
def obter_config() -> Config:
    """Instância única.

    O cache existe para não reler o .env a cada requisição — e para que
    o teste consiga trocar a configuração com `obter_config.cache_clear()`.
    """
    return Config()  # type: ignore[call-arg]
