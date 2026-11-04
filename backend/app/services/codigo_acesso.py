"""WEB-11: código de acesso de uso único, sem e-mail e sem senha trafegando por terceiros.

Quem cadastra (gestor ou equipe ThermoTrace) recebe o código uma vez na tela e o
entrega à pessoa por outro canal. A pessoa informa e-mail + código e escolhe a
própria senha. Só o hash fica no banco; gerar outro ou usar invalida os anteriores.
O código nunca vai para URL, log ou tabela de idempotência.
"""
import secrets
from datetime import timedelta

from sqlalchemy import select, update

from app.core.errors import DadosInvalidos
from app.core.security import agora, hash_token, hasher
from app.models import Empresa, SessaoAuth, TokenRecuperacao, Usuario
from app.services.auditoria import auditar
from app.services.autenticacao import limitar_login

ALFABETO = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"  # sem 0/O, 1/I/L: ditado por telefone sem ambiguidade
VALIDADE = {"convite": timedelta(hours=72), "redefinicao": timedelta(hours=24)}
RECUSA = "Código inválido, vencido ou já usado. Peça um novo código a quem cadastrou o seu acesso."


def normalizar(codigo):
    return "".join(ch for ch in str(codigo).upper() if ch.isalnum())


def _hash(usuario_id, codigo):
    return hash_token(f"tt-codigo-acesso-v1:{usuario_id}:{normalizar(codigo)}")


def senha_inutilizavel():
    """Conta convidada nasce sem senha conhecida por ninguém, nem por quem convidou."""
    return hasher.hash(secrets.token_urlsafe(48))


def revogar_pendentes(db, usuario_id):
    db.execute(update(TokenRecuperacao).where(TokenRecuperacao.usuario_id == usuario_id,
        TokenRecuperacao.usado_em.is_(None), TokenRecuperacao.revogado_em.is_(None)).values(revogado_em=agora()))


def gerar(db, usuario, finalidade, autor, empresa_auditoria=None):
    revogar_pendentes(db, usuario.id)
    bruto = "".join(secrets.choice(ALFABETO) for _ in range(12))
    expira = agora() + VALIDADE[finalidade]
    db.add(TokenRecuperacao(usuario_id=usuario.id, token_hash=_hash(usuario.id, bruto), expira_em=expira,
                            finalidade=finalidade, criado_por=autor.id))
    db.flush()
    auditar(db, f"codigo_acesso_{finalidade}_gerado", "usuario", usuario.id, autor,
            empresa_id=empresa_auditoria or usuario.empresa_id,
            depois={"expira_em": expira.isoformat(), "papel": usuario.papel})
    return {"codigo_acesso": "-".join(bruto[i:i+4] for i in range(0, 12, 4)), "expira_em": expira,
            "finalidade": finalidade}


def ativar(db, email, codigo, nova_senha, ip):
    email = str(email).strip().lower()
    limitar_login(db, email, ip)  # Mesmo limite do login: o código não vira atalho de força bruta.
    user = db.scalar(select(Usuario).where(Usuario.email == email))
    token = None
    if user is not None:
        token = db.scalar(select(TokenRecuperacao).where(TokenRecuperacao.usuario_id == user.id,
            TokenRecuperacao.token_hash == _hash(user.id, codigo), TokenRecuperacao.usado_em.is_(None),
            TokenRecuperacao.revogado_em.is_(None), TokenRecuperacao.expira_em > agora()).with_for_update())
    empresa = db.get(Empresa, user.empresa_id) if user else None
    if token is None or not user.ativo or not empresa or not empresa.ativa:
        auditar(db, "codigo_acesso_recusado", "autenticacao")
        db.commit()  # A tentativa conta no limite mesmo com a resposta de erro.
        raise DadosInvalidos(RECUSA)
    user.senha_hash = hasher.hash(nova_senha)
    token.usado_em = agora()
    revogar_pendentes(db, user.id)
    # Senha nova encerra todas as sessões abertas com a senha anterior.
    db.execute(update(SessaoAuth).where(SessaoAuth.usuario_id == user.id, SessaoAuth.revogada_em.is_(None))
               .values(revogada_em=agora()))
    auditar(db, f"codigo_acesso_{token.finalidade}_usado", "usuario", user.id, user)
    db.commit()
