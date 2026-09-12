"""Fixtures compartilhadas.

Cada teste roda numa transação que sofre rollback no fim. Assim os testes não
sujam o banco nem dependem da ordem de execução — dependência de ordem é o
jeito mais rápido de ter uma suíte que passa na sua máquina e falha no CI.
"""

import os
from urllib.parse import urlparse

# Nunca usar o .env de desenvolvimento/produção para executar a suíte.
test_url = os.environ.get("THERMOTRACE_TEST_DATABASE_URL", "")
if not test_url or not urlparse(test_url).path.endswith("_test"):
    raise RuntimeError("Defina THERMOTRACE_TEST_DATABASE_URL para um banco descartável cujo nome termine em _test.")
os.environ["DATABASE_URL"] = test_url
os.environ["SECRET_KEY"] = "synthetic-only-test-key-0a1b2c3d4e5f6g7h8i9j"
os.environ["AMBIENTE"] = "local"

import pytest
from fastapi.testclient import TestClient
from sqlalchemy.orm import Session

from app.db.session import SessaoLocal, engine
from app.main import app


@pytest.fixture(scope="session")
def cliente() -> TestClient:
    return TestClient(app)


@pytest.fixture
def sessao() -> Session:
    conexao = engine.connect()
    transacao = conexao.begin()
    s = SessaoLocal(bind=conexao)
    try:
        yield s
    finally:
        s.close()
        transacao.rollback()
        conexao.close()
