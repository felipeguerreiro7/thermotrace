"""Senhas Argon2id, tokens curtos e refresh opaco armazenado apenas por hash."""
import hashlib
import secrets
from datetime import datetime, timedelta, timezone
from uuid import uuid4

import jwt
from argon2 import PasswordHasher
from argon2.exceptions import VerificationError, InvalidHashError

from app.core.config import obter_config

hasher = PasswordHasher()
_dummy_hash = hasher.hash(secrets.token_urlsafe(32))
ISSUER = "thermotrace-api"
AUDIENCE = "thermotrace-app"


def agora():
    return datetime.now(timezone.utc)


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def verificar_senha(senha: str, senha_hash: str | None) -> bool:
    try:
        return hasher.verify(senha_hash or _dummy_hash, senha) and senha_hash is not None
    except (VerificationError, InvalidHashError):
        return False


def token_acesso(usuario_id, sessao_id) -> str:
    cfg = obter_config()
    now = agora()
    return jwt.encode({
        "sub": str(usuario_id), "sid": str(sessao_id), "jti": str(uuid4()),
        "iss": ISSUER, "aud": AUDIENCE, "iat": now, "nbf": now,
        "exp": now + timedelta(minutes=cfg.ACCESS_TOKEN_MINUTOS),
    }, cfg.SECRET_KEY, algorithm="HS256")


def ler_token(token: str) -> dict:
    return jwt.decode(token, obter_config().SECRET_KEY, algorithms=["HS256"],
                      issuer=ISSUER, audience=AUDIENCE,
                      options={"require": ["sub", "sid", "jti", "iss", "aud", "iat", "nbf", "exp"]})
