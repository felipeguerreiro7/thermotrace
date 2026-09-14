"""Projeção reproduzível por coleta; não mescla snapshots nem altera evidência recebida."""
import csv
import io
import math
import re
from datetime import datetime, timezone
from html import escape
from app.services.idempotencia import hash_json

VERSAO = 'tt-temperaturas-1'
DECODIFICADOR_ANDROID = 'fm13dt160-decoder-1.1'


def projetar(bruto, *, epoch, delay, intervalo, minimo, maximo, lida_em):
    def inteiro(i):
        if not re.fullmatch(r'[+-]?\d+', bruto[i]): raise ValueError('Campo inteiro inválido no cabeçalho.')
        return int(bruto[i])
    def numero(texto):
        n = float(texto)
        if not math.isfinite(n): raise ValueError('Temperatura ou limite inválido.')
        return n
    if not isinstance(bruto, list) or not 12 <= len(bruto) <= 65547:
        raise ValueError('Histórico ausente ou incompleto.')
    inicio, planejada, medida, atraso, passo = [inteiro(i) for i in range(1, 6)]
    if bruto[0] not in ('0', '1', '2', '3'): raise ValueError('Estado da etiqueta não reconhecido.')
    if not (1 <= planejada <= 65535 and 0 <= medida <= planejada and len(bruto)-12 == medida):
        raise ValueError('Quantidade de medições não confere com o cabeçalho.')
    if (inicio, atraso, passo) != (epoch, delay, intervalo):
        raise ValueError('Base de tempo difere da sessão registrada. Não é seguro posicionar os pontos.')
    inferior, superior = numero(bruto[8]), numero(bruto[9])
    if inferior >= superior: raise ValueError('Faixa térmica inválida no cabeçalho.')
    abaixo, acima = inteiro(10), inteiro(11)
    if min(abaixo, acima) < 0: raise ValueError('Contagem negativa no cabeçalho.')
    avisos = []
    if (inferior, superior) != (minimo, maximo): avisos.append('A faixa do cabeçalho difere da configuração preservada da carga.')
    pontos = []
    for i, texto in enumerate(bruto[12:]):
        partes = texto.split(':')
        if len(partes) > 2 or (len(partes) == 2 and not re.fullmatch(r'[+-]?\d+', partes[1])):
            raise ValueError('Indicador inválido em uma medição. Nenhum ponto foi descartado silenciosamente.')
        temperatura = numero(partes[0])
        segundos = inicio + atraso*60 + i*passo
        instante = datetime.fromtimestamp(segundos, timezone.utc)
        if not 2000 <= instante.year <= 2100: raise ValueError('Horário calculado fora do intervalo suportado.')
        pontos.append({'indice': i, 'instante': instante.isoformat(), 'temperatura_c': temperatura,
                       'indicador': int(partes[1]) if len(partes) == 2 else None})
    serie = [p['temperatura_c'] for p in pontos]
    conf = 'nao_avaliavel'
    if not serie:
        avisos.append('Esta coleta não contém medições de temperatura.')
    else:
        try:
            hmin, hmax = numero(bruto[6]), numero(bruto[7])
            if hmin > hmax or abaixo+acima > medida: raise ValueError()
            divergente = (abs(min(serie)-hmin) > .150000001 or abs(max(serie)-hmax) > .150000001 or
                sum(t < inferior for t in serie) != abaixo or sum(t > superior for t in serie) != acima)
            conf = 'divergente' if divergente else 'coerente'
            if divergente: avisos.append('Cabeçalho e série divergem. Os valores originais foram preservados para conferência.')
        except ValueError:
            avisos.append('Extremos ou contagens do cabeçalho não permitem conferência.')
        if datetime.fromisoformat(pontos[-1]['instante']) > lida_em:
            avisos.append('Existem pontos nominais posteriores ao horário declarado da coleta. Confira o relógio e a base de tempo.')
    return {'pontos': pontos, 'conferencia': conf, 'avisos': avisos,
            'resumo': {'quantidade': len(serie), 'minima_c': min(serie) if serie else None,
                       'maxima_c': max(serie) if serie else None,
                       'abaixo': sum(t < minimo for t in serie), 'acima': sum(t > maximo for t in serie)}}


def resultado(sessao, leitura, carga, integra):
    d = {'versao': VERSAO, 'sessao_id': str(sessao.id), 'leitura_id': str(leitura.id),
         'remessa_id': str(carga.id), 'codigo_carga': carga.codigo, 'tipo': leitura.tipo_leitura.value,
         'origem': (leitura.envelope_integridade or {}).get('origem', 'indisponivel'), 'lida_em': leitura.lida_em_dispositivo.isoformat(),
         'recebida_em': leitura.recebida_em_servidor.isoformat(), 'versao_sdk': leitura.versao_sdk,
         'decodificador_origem': leitura.versao_decodificador, 'hash_evidencia': leitura.hash_encadeado.hex(),
         'faixa': {'min_c': float(sessao.min_configurado_c), 'max_c': float(sessao.max_configurado_c)},
         'integridade_cadeia': integra, 'horarios_corrigidos': False, 'validacao_fisica': False,
         'pontos': [], 'resumo': None, 'conferencia': 'nao_avaliavel', 'avisos': []}
    if not integra:
        d['avisos'] = ['A cadeia de evidências apresenta divergência. Gráfico bloqueado para conferência.']
    elif leitura.tipo_leitura.value == 'ativacao':
        d['avisos'] = ['O comprovante de início não contém uma série térmica. Escolha um checkpoint ou leitura final.']
    elif leitura.versao_decodificador != DECODIFICADOR_ANDROID:
        d['avisos'] = ['Versão de origem ainda não suportada para gráfico. A evidência continua preservada.']
    else:
        try:
            d.update(projetar(leitura.resposta_bruta, epoch=sessao.epoch_inicio_etiqueta,
                delay=sessao.delay_minutos, intervalo=sessao.intervalo_segundos,
                minimo=d['faixa']['min_c'], maximo=d['faixa']['max_c'], lida_em=leitura.lida_em_dispositivo))
        except (ValueError, TypeError, OverflowError, IndexError, OSError):
            d['avisos'] = ['Histórico incompleto, inválido ou incompatível com a sessão. Gráfico indisponível; preserve a evidência para conferência.']
    d['hash_projecao'] = hash_json(d)
    return d


def svg(d):
    pontos = d['pontos']; faixa = d['faixa']
    if not pontos: return '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 900 300"><text x="30" y="60">Sem série térmica disponível. Consulte o relatório.</text></svg>'
    baixos = [p['temperatura_c'] for p in pontos] + [faixa['min_c'], faixa['max_c']]
    low, high = min(baixos), max(baixos); pad = max((high-low)*.15, 1); low -= pad; high += pad
    x = lambda i: 75 + (i/(len(pontos)-1) if len(pontos)>1 else .5)*790
    y = lambda t: 320-(t-low)/(high-low)*260
    coords = ' '.join(f'{x(i):.2f},{y(p["temperatura_c"]):.2f}' for i,p in enumerate(pontos))
    ticks = ''.join(f'<text x="65" y="{y(low+(high-low)*i/4):.1f}" text-anchor="end">{low+(high-low)*i/4:.1f}</text>' for i in range(5))
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 900 410" role="img" aria-label="Temperaturas nominais da coleta">
<rect width="900" height="410" fill="white"/><g font-family="sans-serif" font-size="14" fill="#163c4c">
<text x="75" y="25">{escape(d['codigo_carga'])} · {escape(d['tipo'])} · {escape(d['conferencia'])}</text>
<text x="20" y="45">°C</text><rect x="75" y="{y(faixa['max_c']):.2f}" width="790" height="{y(faixa['min_c'])-y(faixa['max_c']):.2f}" fill="#dbf5ed"/>
<path d="M75 50V320H865" fill="none" stroke="#718b98"/>{ticks}
<polyline points="{coords}" fill="none" stroke="#007f94" stroke-width="2"/>
{f'<circle cx="{x(0):.2f}" cy="{y(pontos[0]["temperatura_c"]):.2f}" r="4" fill="#007f94"/>' if len(pontos)==1 else ''}
<text x="75" y="345">{escape(pontos[0]['instante'])}</text><text x="865" y="365" text-anchor="end">{escape(pontos[-1]['instante'])}</text>
<text x="75" y="395">Horários nominais UTC · faixa verde: {faixa['min_c']:g} a {faixa['max_c']:g} °C · sem validação física</text></g></svg>'''


def exportar(d, formato):
    if formato == 'svg': return svg(d), 'image/svg+xml'
    if formato == 'csv':
        s = io.StringIO(newline=''); w = csv.writer(s, delimiter=';')
        w.writerow(['leitura_id', d['leitura_id']]); w.writerow(['versao', d['versao']])
        w.writerow(['hash_projecao', d['hash_projecao']]); w.writerow(['hash_evidencia', d['hash_evidencia']])
        w.writerow(['conferencia', d['conferencia']]); w.writerow(['validacao_fisica', 'nao'])
        for aviso in d['avisos']: w.writerow(['aviso', aviso])
        w.writerow(['indice','instante_utc','temperatura_c','indicador','limite_min_c','limite_max_c'])
        for p in d['pontos']: w.writerow([p['indice'],p['instante'],p['temperatura_c'],p['indicador'],d['faixa']['min_c'],d['faixa']['max_c']])
        return '\ufeff'+s.getvalue(), 'text/csv'
    avisos = ''.join('<li>'+escape(a)+'</li>' for a in d['avisos'])
    return f'''<!doctype html><html lang="pt-BR"><meta charset="utf-8"><title>Relatório ThermoTrace</title>
<style>body{{font:16px system-ui;max-width:1050px;margin:32px auto;padding:24px;color:#17394b}}svg{{width:100%}}code{{overflow-wrap:anywhere}}li{{margin:12px 0}}@media print{{body{{margin:0}}}}</style>
<h1>ThermoTrace · {escape(d['codigo_carga'])}</h1><p>Relatório da coleta {escape(d['tipo'])}. Origem: {escape(d['origem'])}.</p>
<p>Coletada em {escape(d['lida_em'])}; recebida no servidor em {escape(d['recebida_em'])}.</p>
<p>Conferência: {escape(d['conferencia'])}. {len(d['pontos'])} medições. Faixa: {d['faixa']['min_c']:g} a {d['faixa']['max_c']:g} °C.</p>
<ul>{avisos}</ul>{svg(d)}<h2>Rastreabilidade</h2><p>Coleta: {d['leitura_id']}<br>Sessão: {d['sessao_id']}<br>Versão: {d['versao']}<br>Decodificador de origem: {escape(d['decodificador_origem'])}</p>
<p>Hash da evidência: <code>{d['hash_evidencia']}</code><br>Hash da projeção: <code>{d['hash_projecao']}</code></p>
<p>Esta é uma coleta individual; não soma históricos repetidos. Horários nominais calculados a partir do início e intervalo declarados, sem correção pelo relógio do servidor. Integridade local não comprova calibração, STOP físico ou liberação da carga.</p>
<p>Use a impressão do navegador para salvar este relatório em PDF. O CSV disponibiliza todas as medições desta coleta.</p></html>''', 'text/html'
