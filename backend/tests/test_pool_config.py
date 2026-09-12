from sqlalchemy import text
from app.db.session import engine


def test_timezone_e_timeout_sobrevivem_rollback_do_pool():
    with engine.connect() as conn:
        assert conn.scalar(text('SHOW timezone')) == 'UTC'
        assert conn.scalar(text('SHOW statement_timeout')) == '30s'
        conn.rollback()
        assert conn.scalar(text('SHOW timezone')) == 'UTC'
    with engine.connect() as conn:
        assert conn.scalar(text('SHOW statement_timeout')) == '30s'
