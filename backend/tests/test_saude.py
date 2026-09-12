"""A API sobe, responde e enxerga o banco."""


def test_raiz_responde(cliente):
    r = cliente.get("/")
    assert r.status_code == 200
    assert r.json()["nome"] == "ThermoTrace API"


def test_saude_e_barata(cliente):
    r = cliente.get("/api/v1/saude")
    assert r.status_code == 200
    assert r.json()["status"] == "ok"


def test_saude_completa_enxerga_o_banco(cliente):
    r = cliente.get("/api/v1/saude/completa")
    assert r.status_code == 200
    assert r.json()["dependencias"]["banco"] == "ok"


def test_toda_resposta_carrega_request_id(cliente):
    """Sem o request_id na resposta, o suporte não consegue correlacionar o
    relato do operador com o log do servidor."""
    r = cliente.get("/api/v1/saude")
    assert r.headers.get("X-Request-Id")


def test_rota_inexistente_devolve_404(cliente):
    r = cliente.get("/api/v1/rota-que-nao-existe")
    assert r.status_code == 404


def test_openapi_e_gerado(cliente):
    """Swagger funcionando é requisito do projeto, não enfeite."""
    r = cliente.get("/openapi.json")
    assert r.status_code == 200
    assert "/api/v1/saude" in r.json()["paths"]


def test_404_usa_o_formato_de_erro_do_projeto(cliente):
    """FastAPI devolve {"detail": ...} por padrão. O app espera `erro` e
    `mensagem` — sem isto ele leria nulo e mostraria tela em branco."""
    r = cliente.get("/api/v1/rota-que-nao-existe")
    corpo = r.json()
    assert r.status_code == 404
    assert corpo["erro"] == "nao_encontrado"
    assert corpo["mensagem"]
    assert corpo["request_id"]
