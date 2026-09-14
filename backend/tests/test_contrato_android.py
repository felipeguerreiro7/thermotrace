"""Executa pedidos da fixture conferida pelo construtor Kotlin contra a API e PostgreSQL de teste."""
import json
from pathlib import Path
from uuid import uuid4
from tests.test_acesso_perfis import ambiente
from tests.test_ingestao import preparar_ingestao
from tests.test_cargas import post


def test_contrato_android_inicio_checkpoint_final_e_reenvio(ambiente):
    fixture = json.loads((Path(__file__).resolve().parents[1] / 'docs/contrato-android-0.8.6.json').read_text(encoding='utf-8'))
    c, db, users, h, r, tag, exemplo = preparar_ingestao(ambiente, codigo='CONTRATO-ANDROID-SINTETICO')
    sessao = str(uuid4())
    recibos = []
    for tipo in ('inicio', 'checkpoint', 'final'):
        body = fixture[tipo]
        ev = body['evidencia'] if tipo == 'inicio' else body
        # Só substitui identidades das entidades criadas no banco descartável.
        ev['dispositivo_id'] = exemplo['evidencia']['dispositivo_id']
        ev['uid_canonico'] = tag.nfc_uid
        ev['evento_id'] = str(uuid4())
        if tipo == 'inicio':
            body['sessao_id'] = sessao
            body['etiqueta_id'] = str(tag.id)
            caminho = f"/volumes/{r['volumes'][0]['id']}/sessoes"
        else:
            caminho = f'/sessoes/{sessao}/leituras'
        chave = 'tt-envio-' + ev['evento_id']
        resposta = post(c, caminho, h, body, chave)
        assert resposta.status_code == 201, resposta.text
        replay = post(c, caminho, h, body, chave)
        assert replay.status_code == 201 and replay.json() == resposta.json()
        recibo = resposta.json()
        assert recibo['evento_id'] == ev['evento_id']
        detalhe = c.get(f"/api/v1/sessoes/{sessao}/leituras/{recibo['leitura_id']}", headers=h)
        assert detalhe.status_code == 200
        assert detalhe.json()['resposta_bruta'] == ev['resposta_bruta']
        assert detalhe.json()['envelope_integridade']['usuario_id'] == str(users['a'].id)
        recibos.append(recibo)
    assert [r['ordem_recebimento'] for r in recibos] == [1, 2, 3]
    integridade = c.get(f'/api/v1/sessoes/{sessao}/integridade', headers=h)
    assert integridade.status_code == 200
    assert integridade.json()['integra'] and integridade.json()['quantidade'] == 3
