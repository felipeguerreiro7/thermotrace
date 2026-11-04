import hashlib
import json

from fastapi import APIRouter, Body, Depends, Response
from sqlalchemy.orm import Session

from app.api.deps import Acesso, gestor
from app.core.security import agora
from app.db.session import obter_sessao
from app.services import manifesto
from app.services.auditoria import auditar

router = APIRouter(prefix='/integridade', tags=['Integridade das evidências'])


@router.get('/manifesto')
def emitir(acesso: Acesso = Depends(gestor), db: Session = Depends(obter_sessao)):
    """TT-060: arquivo para guardar fora da ThermoTrace e conferir depois."""
    m = manifesto.emitir(db, acesso.empresa.id, agora())
    auditar(db, 'manifesto_integridade_emitido', 'empresa', acesso.empresa.id, acesso.usuario,
            depois={'hash_manifesto': m['hash_manifesto'], 'total_sessoes': m['total_sessoes'],
                    'total_leituras': m['total_leituras'], 'versao': manifesto.VERSAO})
    db.commit()
    conteudo = json.dumps(m, ensure_ascii=False, indent=2) + '\n'
    nome = f"ThermoTrace-manifesto-{m['emitido_em'][:10]}-{m['hash_manifesto'][:12]}.json"
    return Response(conteudo, media_type='application/json', headers={
        'Content-Disposition': f'attachment; filename="{nome}"', 'Cache-Control': 'no-store',
        'X-Content-Type-Options': 'nosniff',
        'X-Sha256-Arquivo': hashlib.sha256(conteudo.encode('utf-8')).hexdigest()})


@router.post('/manifesto/conferir')
def conferir(arquivo: dict = Body(...), acesso: Acesso = Depends(gestor), db: Session = Depends(obter_sessao)):
    r = manifesto.conferir(db, acesso.empresa.id, arquivo)
    auditar(db, 'manifesto_integridade_conferido', 'empresa', acesso.empresa.id, acesso.usuario,
            depois={'hash_manifesto': r['hash_manifesto'], 'conferencia': r['conferencia'],
                    'arquivo_intacto': r['arquivo_intacto'], 'sessoes_com_problema': r['sessoes_com_problema']})
    db.commit()
    return r
