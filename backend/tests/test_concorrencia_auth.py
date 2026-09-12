"""Estas provas usam conexões separadas no banco descartável explícito da suíte."""
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier
from uuid import uuid4

from sqlalchemy import select
from app.db.session import SessaoLocal
from app.models import Empresa, Usuario, SessaoAuth
from app.models.enums import PapelUsuario, TipoEmpresa
from app.services.autenticacao import emitir, renovar
from app.core.errors import NaoAutenticado
from app.core.security import hasher


def test_refresh_simultaneo_recusa_reuso_e_revoga_vencedor():
    with SessaoLocal.begin() as db:
        e = Empresa(cnpj=str(uuid4().int)[:14], razao_social="Concorrência sintética", tipo=TipoEmpresa.EMBARCADOR)
        db.add(e); db.flush()
        u = Usuario(empresa_id=e.id, nome="Teste", email=f"{uuid4().hex}@example.org",
                    senha_hash=hasher.hash("Senha somente para teste-42"), papel=PapelUsuario.GESTOR)
        db.add(u); db.flush()
        token = emitir(db, u)
        uid = u.id
    barrier = Barrier(2)
    def worker():
        with SessaoLocal() as db:
            barrier.wait(timeout=5)
            try:
                return renovar(db, token["refresh_token"])
            except NaoAutenticado:
                return None
    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(worker) for _ in range(2)]
        resultados = [f.result(timeout=10) for f in futures]
    assert sum(r is not None for r in resultados) == 1
    with SessaoLocal() as db:
        assert not db.scalars(select(SessaoAuth).where(SessaoAuth.usuario_id == uid,
                                                       SessaoAuth.revogada_em.is_(None))).all()
