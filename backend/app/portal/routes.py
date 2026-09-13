"""Portal servido pelo mesmo serviço da API; nenhum dado privado no HTML."""
from pathlib import Path

from fastapi import APIRouter
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

PASTA = Path(__file__).parent
router = APIRouter(include_in_schema=False)


@router.get("/portal")
@router.get("/portal/")
def portal():
    return FileResponse(PASTA / "index.html", media_type="text/html")


def registrar_portal(app):
    app.include_router(router)
    # Somente estes arquivos públicos; não expor routes.py nem outros módulos.
    app.mount("/portal/assets", StaticFiles(directory=PASTA / "assets"), name="portal-assets")
