"""Conexão com o PostgreSQL.

Síncrono de propósito. O volume é de 30 remessas/mês — a complexidade de
async/await no acesso a banco (sessão por task, pool separado, driver
assíncrono) não compra nada aqui e cobra em depuração. FastAPI roda handler
síncrono numa threadpool sem bloquear o loop.

Se um dia o perfil mudar, trocar para `create_async_engine` é localizado
neste arquivo e em `obter_sessao`.
"""

from collections.abc import Generator

from sqlalchemy import create_engine, event, text
from sqlalchemy.engine import Engine
from sqlalchemy.orm import Session, sessionmaker

from app.core.config import obter_config
from app.core.logging import obter_logger

log = obter_logger("banco")
cfg = obter_config()

engine: Engine = create_engine(
    cfg.database_url_sync,
    pool_size=cfg.DB_POOL_SIZE,
    max_overflow=cfg.DB_MAX_OVERFLOW,
    # Recicla conexão antes que o Postgres a derrube por ociosidade; sem
    # isto o primeiro acesso da manhã falha com "server closed connection".
    pool_pre_ping=True,
    pool_recycle=1800,
    echo=cfg.DB_ECHO,
    connect_args={"application_name": "thermotrace-api"},
)

SessaoLocal = sessionmaker(
    bind=engine,
    autocommit=False,
    autoflush=False,
    expire_on_commit=False,
)


@event.listens_for(engine, "connect")
def _configurar_conexao(dbapi_conn, _):
    """Cada conexão nasce com o fuso e o timeout definidos.

    `statement_timeout` é rede de segurança: uma consulta acidental sobre a
    série inteira de cinco anos não pode segurar uma conexão do pool
    indefinidamente.
    """
    # SET deve sobreviver ao rollback de inicialização/checkout do pool.
    # Executá-lo numa transação implícita fazia a configuração ser desfeita.
    anterior = dbapi_conn.autocommit
    dbapi_conn.autocommit = True
    try:
        with dbapi_conn.cursor() as cur:
            cur.execute("SET TIME ZONE 'UTC'")
            cur.execute("SET statement_timeout = '30s'")
    finally:
        dbapi_conn.autocommit = anterior


def obter_sessao() -> Generator[Session, None, None]:
    """Dependência do FastAPI: uma sessão por requisição.

    Commit no sucesso, rollback em qualquer exceção. Deixar isso para cada
    endpoint é como se esquece transação aberta em produção.
    """
    sessao = SessaoLocal()
    try:
        yield sessao
        sessao.commit()
    except Exception:
        sessao.rollback()
        raise
    finally:
        sessao.close()


def verificar_banco() -> bool:
    """Ping usado pelo endpoint de saúde e pelo healthcheck do Docker."""
    try:
        with engine.connect() as conexao:
            conexao.execute(text("SELECT 1"))
        return True
    except Exception as e:
        log.error("banco_indisponivel", erro=str(e))
        return False


def runtime_sem_privilegios_administrativos() -> bool:
    """O processo público não pode desligar gatilhos/políticas como dono do schema."""
    with engine.connect() as conexao:
        return not conexao.execute(text("""
            SELECT r.rolsuper OR r.rolbypassrls OR EXISTS (
                SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname='public' AND c.relkind='r'
                  AND pg_has_role(current_user, c.relowner, 'MEMBER')
            ) FROM pg_roles r WHERE r.rolname=current_user
        """)).scalar_one()
