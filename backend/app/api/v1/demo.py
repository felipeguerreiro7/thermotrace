"""Entrada na demonstração do portal, sem senha, só nas contas fictícias.

Só responde com `DEMO_PUBLICA=true` e fora de produção (a configuração recusa
a combinação). A conta é escolhida pelo servidor entre as criadas por
`python -m app.demo_cli`: o navegador manda apenas "dono" ou "transportadora",
nunca um e-mail, e a empresa precisa ter o CNPJ fictício da demonstração.
Assim a rota não abre nenhuma conta real, mesmo que alguém crie um e-mail
parecido.
"""
from datetime import timedelta
from typing import Literal

from fastapi import APIRouter, Depends, Request, Response
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.config import obter_config
from app.core.errors import NaoEncontrado
from app.core.security import agora
from app.db.session import obter_sessao
from app.demo_cli import DONA, TRANSPORTADORA, email
from app.models import Empresa, Usuario
from app.models.enums import PapelUsuario
from app.services import autenticacao as service
from app.services.auditoria import auditar
from app.core.rede import ip_do_cliente

router = APIRouter(prefix="/demo", tags=["Demonstração"])

PERFIS = {
    "dono": (DONA, "Dono da carga", "Laboratório fictício que despacha as cargas e confere as viagens."),
    "transportadora": (TRANSPORTADORA, "Transportadora",
                       "Transportadora fictícia que recebeu uma carga compartilhada e faz as coletas."),
}
# Sessão de demonstração dura pouco: não é acesso de trabalho.
DURACAO = timedelta(hours=2)


class EntrarDemo(BaseModel):
    perfil: Literal["dono", "transportadora"]


def _ligada() -> bool:
    cfg = obter_config()
    return cfg.DEMO_PUBLICA and not cfg.em_producao


def _conta(db: Session, perfil: str, bloquear: bool = False) -> Usuario | None:
    (cnpj, _, _, prefixo), _, _ = PERFIS[perfil]
    consulta = (select(Usuario).join(Empresa, Empresa.id == Usuario.empresa_id)
                .where(Usuario.email == email(prefixo, PapelUsuario.GESTOR.value), Empresa.cnpj == cnpj,
                       Usuario.ativo.is_(True), Empresa.ativa.is_(True), Usuario.mfa_ativado_em.is_(None)))
    return db.scalar(consulta.with_for_update(of=Usuario) if bloquear else consulta)


@router.get("")
def situacao(response: Response, db: Session = Depends(obter_sessao)):
    """O portal pergunta se mostra a área de demonstração."""
    response.headers["Cache-Control"] = "no-store"
    if not _ligada():
        return {"habilitada": False, "perfis": []}
    perfis = [{"perfil": chave, "rotulo": rotulo, "descricao": descricao}
              for chave, (_, rotulo, descricao) in PERFIS.items() if _conta(db, chave)]
    return {"habilitada": bool(perfis), "perfis": perfis}


@router.post("/entrar")
def entrar(body: EntrarDemo, request: Request, response: Response, db: Session = Depends(obter_sessao)):
    response.headers["Cache-Control"] = "no-store"
    if not _ligada():
        raise NaoEncontrado("Demonstração indisponível neste ambiente.")
    ip = ip_do_cliente(request)
    service.limitar_login(db, "demo:" + body.perfil, ip)
    user = _conta(db, body.perfil, bloquear=True)
    if not user:
        raise NaoEncontrado("As contas de demonstração não foram criadas neste banco.")
    user.ultimo_login_em = agora()
    resposta = service.emitir(db, user, expira_em=agora() + DURACAO)
    auditar(db, "login_demonstracao", "usuario", user.id, user)
    db.commit()
    return resposta
