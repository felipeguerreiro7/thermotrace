"""WEB-12: viagem consolidada de uma sessão, a partir das coletas recebidas.

Cada checkpoint ou leitura final traz o histórico inteiro da etiqueta até aquele
momento. A viagem NÃO soma coletas: usa a mais completa como base e confere, ponto
a ponto, que as anteriores dizem o mesmo nos índices em comum. Divergência fica
visível e marca a viagem como divergente; nada é corrigido ou descartado.

Excursões são trechos contínuos fora da faixa preservada na sessão, com duração
nominal (pontos × intervalo). Sem validação física: não libera carga.
"""
from html import escape
import csv
import io

from app.services.idempotencia import hash_json
from app.services.temperaturas import resultado, svg

# 2: a viagem passa a declarar a origem das evidências (simulação ou aplicativo). Regra de consolidação igual à 1.
VERSAO = 'tt-viagem-2'


def _excursoes(pontos, minimo, maximo, intervalo):
    trechos, atual = [], None
    for p in pontos:
        t = p['temperatura_c']
        lado = 'abaixo' if t < minimo else 'acima' if t > maximo else None
        if lado and atual and atual['tipo'] == lado:
            atual['fim'] = p['instante']; atual['pontos'] += 1
            atual['extremo_c'] = min(atual['extremo_c'], t) if lado == 'abaixo' else max(atual['extremo_c'], t)
        else:
            if atual: trechos.append(atual)
            atual = {'tipo': lado, 'inicio': p['instante'], 'fim': p['instante'], 'pontos': 1, 'extremo_c': t} if lado else None
    if atual: trechos.append(atual)
    for e in trechos:
        e['duracao_nominal_s'] = e['pontos'] * intervalo
    return trechos


def consolidar(sessao, leituras, carga, integra):
    faixa = {'min_c': float(sessao.min_configurado_c), 'max_c': float(sessao.max_configurado_c)}
    # A sessão não mistura origens (a recepção recusa); a ativação diz qual é.
    origem = next(((l.envelope_integridade or {}).get('origem') for l in leituras), None) or 'indisponivel'
    d = {'versao': VERSAO, 'sessao_id': str(sessao.id), 'remessa_id': str(carga.id), 'codigo_carga': carga.codigo,
         'tipo': 'viagem', 'origem': origem, 'faixa': faixa, 'intervalo_segundos': sessao.intervalo_segundos,
         'integridade_cadeia': integra, 'validacao_fisica': False, 'stop_fisico': 'nao_confirmado_pelo_servidor',
         'coletas': [], 'base_leitura_id': None, 'pontos': [], 'excursoes': [], 'divergencias': [],
         'resumo': None, 'conferencia': 'nao_avaliavel', 'avisos': []}
    projecoes = [resultado(sessao, l, carga, integra) for l in leituras if l.tipo_leitura.value != 'ativacao']
    for l, p in zip([l for l in leituras if l.tipo_leitura.value != 'ativacao'], projecoes):
        d['coletas'].append({'leitura_id': p['leitura_id'], 'tipo': p['tipo'], 'ordem_recebimento': l.ordem_recebimento,
                             'lida_em': p['lida_em'], 'medicoes': len(p['pontos']), 'conferencia': p['conferencia'],
                             'utilizavel': bool(p['pontos'])})
    if not integra:
        d['avisos'].append('A cadeia de evidências apresenta divergência. Viagem bloqueada para conferência.')
        d['coletas'] = [{**c, 'utilizavel': False} for c in d['coletas']]
        d['hash_projecao'] = hash_json(d); return d
    uteis = [p for p in projecoes if p['pontos']]
    if not uteis:
        d['avisos'].append('Nenhum checkpoint ou leitura final com série térmica utilizável nesta sessão.')
        d['hash_projecao'] = hash_json(d); return d

    # Base: a coleta com mais medições; empate fica com a mais recente (lista vem na ordem de recebimento).
    base = max(enumerate(uteis), key=lambda x: (len(x[1]['pontos']), x[0]))[1]
    d['base_leitura_id'] = base['leitura_id']; d['pontos'] = base['pontos']
    por_indice = {p['indice']: p for p in base['pontos']}
    maior_ate_agora = 0
    for u in uteis:
        if len(u['pontos']) < maior_ate_agora:
            d['avisos'].append(f"A coleta {u['leitura_id']} chegou depois com menos medições que uma anterior. Confira se a etiqueta foi reativada.")
        maior_ate_agora = max(maior_ate_agora, len(u['pontos']))
        if u is base: continue
        for p in u['pontos']:
            b = por_indice.get(p['indice'])
            if b is None or b['temperatura_c'] != p['temperatura_c'] or b['instante'] != p['instante']:
                if len(d['divergencias']) < 200:
                    d['divergencias'].append({'leitura_id': u['leitura_id'], 'indice': p['indice'], 'instante': p['instante'],
                        'base_c': b['temperatura_c'] if b else None, 'coleta_c': p['temperatura_c']})
    for u in uteis:
        d['avisos'].extend(a for a in u['avisos'] if a not in d['avisos'])
    if d['divergencias']:
        d['avisos'].insert(0, 'Coletas da mesma sessão discordam em medições comuns. Valores preservados; não use a viagem como conferida.')

    d['excursoes'] = _excursoes(d['pontos'], faixa['min_c'], faixa['max_c'], sessao.intervalo_segundos)
    serie = [p['temperatura_c'] for p in d['pontos']]
    fora = sum(e['pontos'] for e in d['excursoes'])
    d['resumo'] = {'quantidade': len(serie), 'minima_c': min(serie), 'maxima_c': max(serie),
                   'primeiro_instante': d['pontos'][0]['instante'], 'ultimo_instante': d['pontos'][-1]['instante'],
                   'pontos_fora_da_faixa': fora, 'tempo_fora_nominal_s': fora * sessao.intervalo_segundos,
                   'percentual_na_faixa': round(100 * (len(serie) - fora) / len(serie), 2),
                   'excursoes': len(d['excursoes']), 'coletas_consideradas': len(uteis),
                   'inclui_leitura_final': any(u['tipo'] == 'final' for u in uteis)}
    if not d['resumo']['inclui_leitura_final']:
        d['avisos'].append('Viagem parcial: ainda não há leitura final recebida para esta sessão.')
    d['conferencia'] = 'divergente' if d['divergencias'] or base['conferencia'] == 'divergente' else base['conferencia']
    d['hash_projecao'] = hash_json(d)
    return d


def exportar(d, formato):
    if formato == 'svg': return svg(d), 'image/svg+xml'
    if formato == 'csv':
        s = io.StringIO(newline=''); w = csv.writer(s, delimiter=';')
        for k in ('sessao_id', 'versao', 'hash_projecao', 'base_leitura_id', 'conferencia'): w.writerow([k, d[k]])
        w.writerow(['validacao_fisica', 'nao'])
        for a in d['avisos']: w.writerow(['aviso', a])
        for e in d['excursoes']: w.writerow(['excursao', e['tipo'], e['inicio'], e['fim'], e['pontos'], e['duracao_nominal_s'], e['extremo_c']])
        w.writerow(['indice', 'instante_utc', 'temperatura_c', 'indicador', 'limite_min_c', 'limite_max_c'])
        for p in d['pontos']: w.writerow([p['indice'], p['instante'], p['temperatura_c'], p['indicador'], d['faixa']['min_c'], d['faixa']['max_c']])
        return '﻿' + s.getvalue(), 'text/csv'
    r = d['resumo'] or {}
    avisos = ''.join('<li>' + escape(a) + '</li>' for a in d['avisos'])
    exc = ''.join(f"<tr><td>{'Abaixo' if e['tipo']=='abaixo' else 'Acima'}</td><td>{escape(e['inicio'])}</td><td>{escape(e['fim'])}</td>"
                  f"<td>{e['duracao_nominal_s']//60} min</td><td>{e['extremo_c']:g} °C</td></tr>" for e in d['excursoes'])
    col = ''.join(f"<tr><td>{c['ordem_recebimento']}</td><td>{escape(c['tipo'])}</td><td>{escape(c['lida_em'])}</td><td>{c['medicoes']}</td>"
                  f"<td>{'base' if c['leitura_id']==d['base_leitura_id'] else ('conferida' if c['utilizavel'] else 'sem série')}</td></tr>" for c in d['coletas'])
    resumo = (f"<p>{r.get('quantidade',0)} medições de {escape(str(r.get('primeiro_instante','')))} a {escape(str(r.get('ultimo_instante','')))}. "
              f"Mínima {r.get('minima_c','—')} °C, máxima {r.get('maxima_c','—')} °C. {r.get('percentual_na_faixa','—')}% na faixa; "
              f"{r.get('excursoes',0)} excursão(ões), {r.get('tempo_fora_nominal_s',0)//60} min fora da faixa.</p>") if r else '<p>Sem série utilizável.</p>'
    return f'''<!doctype html><html lang="pt-BR"><meta charset="utf-8"><title>Relatório da viagem ThermoTrace</title>
<style>body{{font:16px system-ui;max-width:1050px;margin:32px auto;padding:24px;color:#17394b}}svg{{width:100%}}table{{border-collapse:collapse;width:100%;margin:12px 0}}td,th{{border:1px solid #c9d6dc;padding:6px;text-align:left}}code{{overflow-wrap:anywhere}}@media print{{body{{margin:0}}}}</style>
<h1>ThermoTrace · {escape(d['codigo_carga'])} · viagem</h1>{'<p><strong>SIMULAÇÃO: dados fictícios, sem etiqueta real.</strong></p>' if d.get('origem') == 'simulacao' else ''}<p>Origem: {escape(str(d.get('origem')))}. Conferência: {escape(d['conferencia'])}. Faixa: {d['faixa']['min_c']:g} a {d['faixa']['max_c']:g} °C.</p>
{resumo}<ul>{avisos}</ul>{svg(d)}
<h2>Excursões</h2>{f'<table><tr><th>Tipo</th><th>Início</th><th>Fim</th><th>Duração nominal</th><th>Extremo</th></tr>{exc}</table>' if exc else '<p>Nenhuma medição fora da faixa.</p>'}
<h2>Coletas consideradas</h2><table><tr><th>Ordem</th><th>Tipo</th><th>Lida em</th><th>Medições</th><th>Uso</th></tr>{col}</table>
<h2>Rastreabilidade</h2><p>Sessão: {d['sessao_id']}<br>Versão: {d['versao']}<br>Coleta base: {d['base_leitura_id']}<br>Hash da projeção: <code>{d['hash_projecao']}</code></p>
<p>A viagem usa a coleta mais completa e confere as demais nos índices em comum; não soma históricos repetidos. Horários nominais pelo início e intervalo declarados. Sem validação física: não comprova calibração, STOP físico nem libera a carga.</p></html>''', 'text/html'
