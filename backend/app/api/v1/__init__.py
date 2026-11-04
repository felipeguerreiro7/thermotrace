"""Rotas v1: acesso, clientes, perfis, produtos, cargas e documentos."""

from fastapi import APIRouter

from app.api.v1 import saude, auth, clientes, perfis, produtos, remessas, ingestao, compartilhamentos, acompanhamento

router_v1 = APIRouter()
router_v1.include_router(saude.router)
router_v1.include_router(auth.router)
router_v1.include_router(clientes.router)
router_v1.include_router(perfis.router)
router_v1.include_router(produtos.router)
router_v1.include_router(remessas.router)
router_v1.include_router(ingestao.router)
router_v1.include_router(compartilhamentos.router)
router_v1.include_router(acompanhamento.router)

__all__ = ["router_v1"]
