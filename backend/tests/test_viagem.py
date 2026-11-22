"""WEB-12: viagem consolidada não soma coletas, confere as anteriores e mede excursões."""
import json
import os
from pathlib import Path
from uuid import UUID

from sqlalchemy import select

from app.models import LogAuditoria
from tests.test_acesso_perfis import ambiente, headers
from tests.test_ingestao import preparar_ingestao, iniciar, enviar, leitura

PONTOS = [4, 5, 9, 9.5, 6, 1.5, 5, 5, 5]


def bruto(pts, epoch):
    acima = sum(p > 8 for p in pts); abaixo = sum(p < 2 for p in pts)
    return ['1', str(epoch), '648', str(len(pts)), '0', '600', str(min(pts)), str(max(pts)), '2', '8',
            str(abaixo), str(acima), *[str(p) for p in pts]]


def coletar(d, pts, minutos, tipo='checkpoint'):
    b = leitura(d[-1], tipo, minutos)
    b.update(resposta_bruta=bruto(pts, d[-1]['epoch_inicio_etiqueta']), versao_decodificador='fm13dt160-decoder-1.1',
             versao_sdk='fmsh-nfcinstruct-sintetico')
    r = enviar(d, b); assert r.status_code == 201, r.text
    return r.json()['leitura_id']


def test_viagem_usa_coleta_mais_completa_sem_somar_e_mede_excursoes(ambiente):
    d = preparar_ingestao(ambiente); assert iniciar(d).status_code == 201
    c, db, users, h = d[0], d[1], d[2], d[3]
    coletar(d, PONTOS[:3], 30); coletar(d, PONTOS[:6], 60)
    path = f"/api/v1/sessoes/{d[-1]['sessao_id']}/viagem"
    parcial = c.get(path, headers=h).json()
    assert parcial['resumo']['quantidade'] == 6 and not parcial['resumo']['inclui_leitura_final']
    assert any('parcial' in a for a in parcial['avisos'])
    final = coletar(d, PONTOS, 90, 'final')
    v = c.get(path, headers=h).json()
    assert v['base_leitura_id'] == final and v['conferencia'] == 'coerente' and v['divergencias'] == []
    assert v['resumo']['quantidade'] == 9  # 3 + 6 + 9 coletadas, mas a viagem tem 9 medições: nada somado
    assert v['resumo']['coletas_consideradas'] == 3 and v['resumo']['inclui_leitura_final']
    assert [(e['tipo'], e['pontos'], e['duracao_nominal_s'], e['extremo_c']) for e in v['excursoes']] == [('acima', 2, 1200, 9.5), ('abaixo', 1, 600, 1.5)]
    assert v['resumo']['tempo_fora_nominal_s'] == 1800 and v['resumo']['percentual_na_faixa'] == 66.67
    assert c.get(path, headers=h).json()['hash_projecao'] == v['hash_projecao']  # determinística
    csv = c.get(path + '?formato=csv', headers=h)
    assert csv.status_code == 200 and 'attachment' in csv.headers['content-disposition']
    assert csv.text.count('\n') >= 9 and csv.text.count(';9,5;') == 1 and ';9.5;' not in csv.text
    html = c.get(path + '?formato=html', headers=h).text
    assert 'não soma históricos repetidos' in html and 'Excursões' in html
    acoes = {l.acao for l in db.scalars(select(LogAuditoria).where(LogAuditoria.entidade_id == UUID(d[-1]['sessao_id'])))}
    assert {'viagem_consultada', 'viagem_exportada'} <= acoes


def test_coletas_que_discordam_ficam_visiveis(ambiente):
    d = preparar_ingestao(ambiente); assert iniciar(d).status_code == 201
    c, h = d[0], d[3]
    alterado = list(PONTOS[:6]); alterado[1] = 7.25
    coletar(d, PONTOS[:3], 30); coletar(d, alterado, 60); coletar(d, PONTOS, 90, 'final')
    v = c.get(f"/api/v1/sessoes/{d[-1]['sessao_id']}/viagem", headers=h).json()
    assert v['conferencia'] == 'divergente'
    assert [(x['indice'], x['base_c'], x['coleta_c']) for x in v['divergencias']] == [(1, 5.0, 7.25)]
    assert 'discordam' in v['avisos'][0]


def test_sem_serie_e_isolamento(ambiente):
    d = preparar_ingestao(ambiente); assert iniciar(d).status_code == 201
    c, h = d[0], d[3]
    path = f"/api/v1/sessoes/{d[-1]['sessao_id']}/viagem"
    v = c.get(path, headers=h).json()
    assert v['pontos'] == [] and v['conferencia'] == 'nao_avaliavel' and v['resumo'] is None
    assert c.get(path, headers=headers(ambiente[3]('b'))).status_code == 404
    assert c.get(path, headers=headers(ambiente[3]('staff'))).status_code == 404  # sem liberação de suporte
    assert c.get(path).status_code == 401


FIXTURE_ANDROID = Path(__file__).resolve().parents[1] / 'docs/viagem-android-0.16.json'


def _forma(v):
    """Estrutura sem valores: chaves e tipos, para o contrato não depender de UUIDs e horários."""
    if isinstance(v, dict):
        return {k: _forma(x) for k, x in sorted(v.items())}
    if isinstance(v, list):
        return [_forma(v[0])] if v else []
    if isinstance(v, bool) or v is None:
        return type(v).__name__
    return 'numero' if isinstance(v, (int, float)) else type(v).__name__


def test_contrato_android_da_viagem(ambiente):
    """TT-040: o app lê a viagem desta resposta. Mudou a forma, o Android precisa mudar junto.

    Regenerar a fixture: THERMOTRACE_GRAVAR_FIXTURE=1 (só com dados sintéticos do banco descartável).
    """
    d = preparar_ingestao(ambiente, codigo='CONTRATO-VIAGEM-SINTETICO'); assert iniciar(d).status_code == 201
    c, h = d[0], d[3]
    coletar(d, PONTOS[:3], 30); coletar(d, PONTOS, 90, 'final')
    v = c.get(f"/api/v1/sessoes/{d[-1]['sessao_id']}/viagem", headers=h).json()
    assert v['excursoes'] and v['divergencias'] == [] and v['resumo']['inclui_leitura_final']
    if os.environ.get('THERMOTRACE_GRAVAR_FIXTURE') == '1':
        gravar = {'origem_fixture': 'API 0.16 / PostgreSQL 17 / dados sintéticos de tests/test_viagem.py', **v}
        FIXTURE_ANDROID.write_text(json.dumps(gravar, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    # O app compartilha este HTML e tira dele o endereço de conferência (com &amp; escapado).
    html = c.get(f"/api/v1/sessoes/{d[-1]['sessao_id']}/viagem?formato=html", headers=h).text
    assert f"/portal/conferir?sessao={v['sessao_id']}&amp;hash={v['hash_projecao']}" in html
    if os.environ.get('THERMOTRACE_GRAVAR_FIXTURE') == '1':
        FIXTURE_ANDROID.with_suffix('.html').write_text(html, encoding='utf-8', newline='\n')
    gravada = json.loads(FIXTURE_ANDROID.read_text(encoding='utf-8'))
    gravada.pop('origem_fixture')
    # Divergência é lista vazia na fixture; a forma do item vem do teste de coletas que discordam.
    assert _forma(v) == _forma(gravada)
