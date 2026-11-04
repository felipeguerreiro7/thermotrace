"""TT-060: manifesto guardado fora do servidor detecta reescrita completa da cadeia."""
import copy
import hashlib
import json
from uuid import UUID

from sqlalchemy import select, text

from app.models import LeituraEtiqueta, LogAuditoria
from app.services.idempotencia import hash_json
from app.services.ingestao import DOMINIO, verificar_integridade
from tests.test_acesso_perfis import ambiente, headers  # noqa: F401 (fixture)
from tests.test_ingestao import preparar_ingestao, iniciar, enviar, leitura

URL = '/api/v1/integridade/manifesto'


def emitir(c, h):
    r = c.get(URL, headers=h)
    assert r.status_code == 200, r.text
    assert 'attachment' in r.headers['content-disposition']
    assert r.headers['x-sha256-arquivo'] == hashlib.sha256(r.content).hexdigest()
    return json.loads(r.content)


def test_manifesto_confere_e_aceita_leituras_posteriores(ambiente):
    d = preparar_ingestao(ambiente); assert iniciar(d).status_code == 201
    c, db, users, h, r, tag, body = d
    assert enviar(d, leitura(body, 'checkpoint', 30)).status_code == 201
    m = emitir(c, h)
    assert m['total_sessoes'] == 1 and m['total_leituras'] == 2
    assert m['sessoes'][0]['sessao_id'] == body['sessao_id'] and m['sessoes'][0]['cadeia_integra_na_emissao']
    assert 'resposta_bruta' not in json.dumps(m)  # só identificadores e hashes
    # A cadeia cresce depois da emissão; o ponto ancorado continua valendo.
    assert enviar(d, leitura(body, 'final', 60)).status_code == 201
    v = c.post(URL + '/conferir', headers=h, json=m).json()
    assert v['conferencia'] == 'confere' and v['arquivo_intacto'] and v['sessoes_com_problema'] == 0
    acoes = [l.acao for l in db.scalars(select(LogAuditoria).where(LogAuditoria.empresa_id == users['a'].empresa_id))]
    assert 'manifesto_integridade_emitido' in acoes and 'manifesto_integridade_conferido' in acoes
    emitido = db.scalar(select(LogAuditoria).where(LogAuditoria.acao == 'manifesto_integridade_emitido',
                                                   LogAuditoria.empresa_id == users['a'].empresa_id))
    assert emitido.depois['hash_manifesto'] == m['hash_manifesto']


def test_arquivo_editado_e_percebido(ambiente):
    d = preparar_ingestao(ambiente); assert iniciar(d).status_code == 201
    c, h = d[0], d[3]
    m = emitir(c, h)
    editado = copy.deepcopy(m); editado['total_leituras'] = 99
    v = c.post(URL + '/conferir', headers=h, json=editado).json()
    assert not v['arquivo_intacto'] and v['conferencia'] == 'divergente'


def test_cadeia_recalculada_passa_no_local_mas_nao_no_manifesto(ambiente):
    d = preparar_ingestao(ambiente); assert iniciar(d).status_code == 201
    c, db, users, h = d[0], d[1], d[2], d[3]
    m = emitir(c, h)
    sid = UUID(d[-1]['sessao_id'])
    l = db.scalar(select(LeituraEtiqueta).where(LeituraEtiqueta.sessao_id == sid))
    # Quem tem acesso de dono ao banco altera a evidência E recalcula todos os hashes.
    e = copy.deepcopy(l.envelope_integridade)
    e['requisicao']['evidencia']['resposta_bruta'] = ['ADULTERADA']
    e['requisicao_sha256'] = hash_json(e['requisicao'])
    hp = bytes.fromhex(hash_json(e)); hc = hashlib.sha256(DOMINIO + hp).digest()
    db.execute(text('ALTER TABLE leitura_etiqueta DISABLE TRIGGER leitura_etiqueta_imutavel'))
    db.execute(text("UPDATE leitura_etiqueta SET resposta_bruta='[\"ADULTERADA\"]'::jsonb, envelope_integridade=:e, "
                    "hash_payload=:hp, hash_encadeado=:hc WHERE id=:id"),
               {'e': json.dumps(e), 'hp': hp, 'hc': hc, 'id': l.id})
    db.execute(text('ALTER TABLE leitura_etiqueta ENABLE TRIGGER leitura_etiqueta_imutavel'))
    db.expire_all()
    assert verificar_integridade(db, users['a'].empresa_id, sid)['integra']  # a cadeia local não percebe
    v = c.post(URL + '/conferir', headers=h, json=m).json()
    assert v['conferencia'] == 'divergente' and v['arquivo_intacto']
    assert v['problemas'] == [{'sessao_id': str(sid), 'situacao': 'divergente', 'leituras_no_manifesto': 1,
                               'leituras_agora': 1}]


def test_escopo_e_permissoes(ambiente):
    d = preparar_ingestao(ambiente); assert iniciar(d).status_code == 201
    c, h, entrar = d[0], d[3], ambiente[3]
    m = emitir(c, h)
    outra = c.post(URL + '/conferir', headers=headers(entrar('b')), json=m)
    assert outra.status_code == 422 and 'outra empresa' in outra.text
    assert c.post(URL + '/conferir', headers=h, json={'tipo': 'qualquer'}).status_code == 422
    assert c.get(URL, headers=headers(entrar('op'))).status_code == 403
    assert c.get(URL, headers=headers(entrar('staff'))).status_code == 403
    assert c.get(URL).status_code == 401
    # Empresa B emite o próprio manifesto sem ver a sessão de A.
    assert emitir(c, headers(entrar('b')))['sessoes'] == []
