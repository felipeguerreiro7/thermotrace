"""Portal e site público servidos pelo mesmo serviço da API; nenhum dado privado no HTML."""
from pathlib import Path

from fastapi import APIRouter, Request
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

PASTA = Path(__file__).parent
router = APIRouter(include_in_schema=False)


@router.get("/portal")
@router.get("/portal/")
@router.get("/portal/conferir")
def portal():
    return FileResponse(PASTA / "index.html", media_type="text/html")


SITE = PASTA / "site"
PAGINAS_SITE = {"/privacidade": "privacidade.html", "/termos": "termos.html"}


def pagina_site(nome: str) -> FileResponse:
    return FileResponse(SITE / nome, media_type="text/html")


@router.get("/privacidade")
@router.get("/termos")
def site_institucional(request: Request):
    return pagina_site(PAGINAS_SITE[request.url.path])


@router.get("/site/site.css")
def site_css():
    return FileResponse(SITE / "site.css", media_type="text/css")


def registrar_portal(app):
    app.include_router(router)
    # Somente estes arquivos públicos; não expor routes.py nem outros módulos.
    app.mount("/portal/assets", StaticFiles(directory=PASTA / "assets"), name="portal-assets")
