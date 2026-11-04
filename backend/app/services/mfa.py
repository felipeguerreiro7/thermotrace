"""TOTP (RFC 6238: SHA-1, 6 dígitos, passo de 30 s), só com a biblioteca padrão.

Compatível com Google Authenticator, Microsoft Authenticator e similares.
Aceita o passo anterior e o seguinte para tolerar relógio de celular, e nunca
o mesmo passo duas vezes: um código visto por cima do ombro não serve de novo.

Limite conhecido: o segredo fica no banco sem cifra adicional (não há biblioteca
de criptografia nas dependências). Quem lê o banco já tem acesso amplo; registrar
essa decisão e rever antes de dados reais.
"""
import base64
import hashlib
import hmac
import secrets
import struct
import time
from urllib.parse import quote

PASSO = 30
JANELA = 1


def novo_segredo():
    return base64.b32encode(secrets.token_bytes(20)).decode().rstrip("=")


def _codigo(segredo, passo):
    chave = base64.b32decode(segredo + "=" * (-len(segredo) % 8))
    digest = hmac.new(chave, struct.pack(">Q", passo), hashlib.sha1).digest()
    o = digest[-1] & 0x0F
    return f"{(struct.unpack('>I', digest[o:o+4])[0] & 0x7FFFFFFF) % 1_000_000:06d}"


def codigo_atual(segredo, agora=None, deslocamento=0):
    """Para testes e diagnóstico local; a API nunca devolve um código."""
    return _codigo(segredo, int((agora or time.time()) // PASSO) + deslocamento)


def verificar(segredo, codigo, ultimo_passo, agora=None):
    """Retorna o passo aceito, ou None. Comparação em tempo constante."""
    codigo = "".join(ch for ch in str(codigo or "") if ch.isdigit())
    if not segredo or len(codigo) != 6:
        return None
    atual = int((agora or time.time()) // PASSO)
    aceito = None
    for passo in range(atual - JANELA, atual + JANELA + 1):
        if hmac.compare_digest(_codigo(segredo, passo), codigo) and (ultimo_passo is None or passo > ultimo_passo):
            aceito = passo
    return aceito


def uri(segredo, email):
    rotulo = quote(f"ThermoTrace:{email}")
    return f"otpauth://totp/{rotulo}?secret={segredo}&issuer=ThermoTrace&algorithm=SHA1&digits=6&period={PASSO}"
