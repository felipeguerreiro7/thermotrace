"""Rotação serializada por família; bloqueio de tentativas compartilhado no Postgres."""
import hashlib
import hmac
import secrets
from datetime import timedelta
from uuid import uuid4

from fastapi import HTTPException
from sqlalchemy import select, text, update

from app.core.config import obter_config
from app.core.errors import NaoAutenticado
from app.core.security import agora, hash_token, token_acesso, verificar_senha, hasher
from app.models import Usuario, Empresa, SessaoAuth
from app.services.auditoria import auditar


def limitar_login(db, email, ip):
    # Contabiliza também sucessos. Chaves HMAC não expõem e-mails na tabela.
    # IP primeiro: cliente já bloqueado não pode criar infinitas chaves de e-mail.
    buckets = [("ip:" + ip, 50), ("email:" + email, 10)]
    for chave, limite in buckets:
        digest = hmac.new(obter_config().SECRET_KEY.encode(), chave.encode(), hashlib.sha256).hexdigest()
        qtd = db.execute(text("""
            INSERT INTO limite_login (chave, inicio, tentativas) VALUES (:chave, now(), 1)
            ON CONFLICT (chave) DO UPDATE SET
              tentativas = CASE WHEN limite_login.inicio <= now() - interval '5 minutes'
                                THEN 1 ELSE limite_login.tentativas + 1 END,
              inicio = CASE WHEN limite_login.inicio <= now() - interval '5 minutes'
                            THEN now() ELSE limite_login.inicio END
            RETURNING tentativas
        """), {"chave": digest}).scalar_one()
        if qtd > limite:
            db.commit()  # Deve sobreviver ao rollback da resposta 429.
            raise HTTPException(429)


def emitir(db, usuario, familia_id=None, expira_em=None):
    refresh = secrets.token_urlsafe(48)
    sessao = SessaoAuth(id=uuid4(), usuario_id=usuario.id, token_hash=hash_token(refresh),
                       familia_id=familia_id or uuid4(),
                       expira_em=expira_em or agora()+timedelta(days=obter_config().REFRESH_TOKEN_DIAS))
    db.add(sessao)
    db.flush()
    return {"access_token": token_acesso(usuario.id, sessao.id), "refresh_token": refresh,
            "token_type": "bearer", "expires_in": obter_config().ACCESS_TOKEN_MINUTOS*60}


def login(db, email, senha, ip):
    email = str(email).strip().lower()
    limitar_login(db, email, ip)
    user = db.scalar(select(Usuario).where(Usuario.email == email))
    valido = verificar_senha(senha, user.senha_hash if user else None)
    empresa = db.get(Empresa, user.empresa_id) if user else None
    if not valido or not user.ativo or not empresa or not empresa.ativa:
        auditar(db, "login_recusado", "autenticacao")
        db.commit()
        raise NaoAutenticado()
    if hasher.check_needs_rehash(user.senha_hash):
        user.senha_hash = hasher.hash(senha)
    user.ultimo_login_em = agora()
    resposta = emitir(db, user)
    auditar(db, "login", "usuario", user.id, user)
    db.commit()
    return resposta


def bloquear_familia(db, familia_id):
    # A mesma trava é usada por refresh e logout; vale entre processos da API.
    db.execute(text("SELECT pg_advisory_xact_lock(hashtextextended(:chave, 0))"),
               {"chave": "auth:" + str(familia_id)})


def revogar_familia(db, familia_id):
    db.execute(update(SessaoAuth).where(SessaoAuth.familia_id == familia_id,
                                       SessaoAuth.revogada_em.is_(None)).values(revogada_em=agora()))


def renovar(db, refresh):
    digest = hash_token(refresh)
    familia = db.scalar(select(SessaoAuth.familia_id).where(SessaoAuth.token_hash == digest))
    if familia is None:
        raise NaoAutenticado()
    bloquear_familia(db, familia)
    sessao = db.scalar(select(SessaoAuth).where(SessaoAuth.token_hash == digest)
                       .execution_options(populate_existing=True).with_for_update())
    user = db.get(Usuario, sessao.usuario_id)
    empresa = db.get(Empresa, user.empresa_id)
    if sessao.revogada_em or sessao.expira_em <= agora() or not user.ativo or not empresa.ativa:
        revogar_familia(db, familia)
        auditar(db, "refresh_recusado_familia_revogada", "sessao_auth", sessao.id, user)
        db.commit()  # Reuso não pode desfazer a própria revogação ao responder 401.
        raise NaoAutenticado()
    sessao.revogada_em = agora()
    resposta = emitir(db, user, familia, sessao.expira_em)
    auditar(db, "refresh_rotacionado", "sessao_auth", sessao.id, user)
    db.commit()
    return resposta
