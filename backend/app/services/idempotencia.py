"""Recibo atômico por empresa, usuário, rota e chave; nenhum sucesso sem commit."""
import hashlib
import json
from fastapi.encoders import jsonable_encoder
from fastapi.responses import JSONResponse
from sqlalchemy import select, text
from app.core.errors import Conflito
from app.models import ChaveIdempotencia


def hash_json(value):
    raw = json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":"), allow_nan=False)
    return hashlib.sha256(raw.encode()).hexdigest()


def executar(db, acesso, chave, endpoint, corpo, operacao, status=201, modelo_saida=None):
    escopo = f"{acesso.empresa.id}:{acesso.usuario.id}:{endpoint}:{chave}"
    db.execute(text("SELECT pg_advisory_xact_lock(hashtextextended(:chave,0))"), {"chave": "idem:"+escopo})
    digest = hash_json(corpo.model_dump(mode="json"))
    row = db.scalar(select(ChaveIdempotencia).where(
        ChaveIdempotencia.empresa_id == acesso.empresa.id,
        ChaveIdempotencia.usuario_id == acesso.usuario.id,
        ChaveIdempotencia.endpoint == endpoint, ChaveIdempotencia.chave == chave))
    if row:
        if row.requisicao_sha256 != digest:
            raise Conflito("Esta chave já foi usada com outro conteúdo. Não reenviar uma operação diferente com a mesma chave.")
        return JSONResponse(row.resposta_corpo, status_code=row.resposta_status,
                            headers={"Idempotency-Replayed": "true", "Cache-Control": "no-store"})
    bruto = operacao()
    resultado = modelo_saida.model_validate(bruto).model_dump(mode="json") if modelo_saida else jsonable_encoder(bruto)
    db.add(ChaveIdempotencia(empresa_id=acesso.empresa.id, usuario_id=acesso.usuario.id,
                           chave=chave, endpoint=endpoint, requisicao_sha256=digest,
                           resposta_status=status, resposta_corpo=resultado))
    db.commit()
    return JSONResponse(resultado, status_code=status,
                        headers={"Idempotency-Replayed": "false", "Cache-Control": "no-store"})
