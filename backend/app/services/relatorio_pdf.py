"""TT-041: relatório em PDF e gráfico em PNG, gerados no servidor a partir dos mesmos dados do HTML.

O PDF sai com `invariant=1`: sem data de criação nem identificador aleatório, os
mesmos dados produzem os mesmos bytes. Assim o `sha256_arquivo` gravado na
auditoria confere com o arquivo que o cliente guardou, mesmo se baixado de novo.

Fonte Helvetica embutida no leitor de PDF (Latin-1): cobre acentos, ° e ·.
Sem dependência de sistema (cairo, navegador): roda no contêiner slim do Render.
"""
import io
from pathlib import Path

import reportlab
from PIL import Image, ImageDraw, ImageFont
from reportlab.graphics.barcode.qr import QrCodeWidget
from reportlab.graphics.shapes import Drawing, Line, PolyLine, Rect, String
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.platypus import KeepTogether, Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle
from xml.sax.saxutils import escape

TINTA, FAIXA, LINHA, EIXO, BORDA = '#163c4c', '#dbf5ed', '#007f94', '#718b98', '#c9d6dc'
AVISO_FISICO = ('Sem validação física: não comprova calibração, STOP físico nem libera a carga. '
                'Horários nominais pelo início e intervalo declarados, sem correção pelo relógio do servidor.')


def _escala(pontos, faixa, largura, altura, esq, topo, base):
    temps = [p['temperatura_c'] for p in pontos] + [faixa['min_c'], faixa['max_c']]
    low, high = min(temps), max(temps); pad = max((high - low) * .15, 1); low -= pad; high += pad
    n = len(pontos)
    x = lambda i: esq + (i / (n - 1) if n > 1 else .5) * (largura - esq - 20)
    y = lambda t: base + (t - low) / (high - low) * (altura - topo - base)  # origem embaixo (PDF)
    return low, high, x, y


def _grafico(d, largura=170 * mm, altura=70 * mm):
    pontos, faixa = d['pontos'], d['faixa']
    g = Drawing(largura, altura)
    if not pontos:
        g.add(String(10, altura / 2, 'Sem série térmica disponível.', fontName='Helvetica', fontSize=10, fillColor=colors.HexColor(TINTA)))
        return g
    esq, topo, base = 34, 8, 22
    low, high, x, y = _escala(pontos, faixa, largura, altura, esq, topo, base)
    g.add(Rect(esq, y(faixa['min_c']), largura - esq - 20, y(faixa['max_c']) - y(faixa['min_c']),
               fillColor=colors.HexColor(FAIXA), strokeColor=None))
    g.add(Line(esq, base, esq, altura - topo, strokeColor=colors.HexColor(EIXO)))
    g.add(Line(esq, base, largura - 20, base, strokeColor=colors.HexColor(EIXO)))
    for i in range(5):
        t = low + (high - low) * i / 4
        g.add(String(esq - 4, y(t) - 3, f'{t:.1f}'.replace('.', ','), fontName='Helvetica', fontSize=7,
                     textAnchor='end', fillColor=colors.HexColor(TINTA)))
    g.add(String(2, altura - topo - 4, '°C', fontName='Helvetica', fontSize=7, fillColor=colors.HexColor(TINTA)))
    coords = []
    for i, p in enumerate(pontos):
        coords += [x(i), y(p['temperatura_c'])]
    if len(pontos) == 1:
        coords += [x(0) + .5, y(pontos[0]['temperatura_c'])]
    g.add(PolyLine(coords, strokeColor=colors.HexColor(LINHA), strokeWidth=1.2))
    for texto, xx, ancora in ((pontos[0]['instante'], esq, 'start'), (pontos[-1]['instante'], largura - 20, 'end')):
        g.add(String(xx, 8, str(texto), fontName='Helvetica', fontSize=7, textAnchor=ancora, fillColor=colors.HexColor(TINTA)))
    return g


def _qr(conteudo, lado=32 * mm):
    w = QrCodeWidget(conteudo, barLevel='M')
    x0, y0, x1, y1 = w.getBounds()
    g = Drawing(lado, lado, transform=[lado / (x1 - x0), 0, 0, lado / (y1 - y0), 0, 0])
    g.add(w)
    return g


def _estilos():
    s = getSampleStyleSheet()
    base = ParagraphStyle('base', parent=s['BodyText'], fontName='Helvetica', fontSize=9.5, leading=13, textColor=colors.HexColor(TINTA))
    return {
        'h1': ParagraphStyle('h1', parent=base, fontName='Helvetica-Bold', fontSize=16, leading=20, spaceAfter=4),
        'h2': ParagraphStyle('h2', parent=base, fontName='Helvetica-Bold', fontSize=11.5, leading=15, spaceBefore=8, spaceAfter=4),
        'p': base,
        'mono': ParagraphStyle('mono', parent=base, fontName='Courier', fontSize=8, leading=10),
        'alerta': ParagraphStyle('alerta', parent=base, fontName='Helvetica-Bold', textColor=colors.HexColor('#9a3412')),
        'pequeno': ParagraphStyle('pequeno', parent=base, fontSize=8, leading=10.5),
    }


def _tabela(cabecalho, linhas, larguras):
    t = Table([cabecalho] + linhas, colWidths=larguras, repeatRows=1)
    t.setStyle(TableStyle([
        ('FONT', (0, 0), (-1, 0), 'Helvetica-Bold', 8.5), ('FONT', (0, 1), (-1, -1), 'Helvetica', 8.5),
        ('TEXTCOLOR', (0, 0), (-1, -1), colors.HexColor(TINTA)),
        ('GRID', (0, 0), (-1, -1), .5, colors.HexColor(BORDA)), ('BACKGROUND', (0, 0), (-1, 0), colors.HexColor('#eef4f6')),
        ('VALIGN', (0, 0), (-1, -1), 'TOP')]))
    return t


def _n(v):
    return '—' if v is None else (f'{v:g}'.replace('.', ',') if isinstance(v, float) else str(v))


def _documento(titulo, d, corpo):
    buf = io.BytesIO()
    rodape = f"ThermoTrace · {d['codigo_carga']} · versão {d['versao']} · projeção {d['hash_projecao'][:16]}…"

    def pagina(c, doc):
        c.saveState(); c.setFont('Helvetica', 7); c.setFillColor(colors.HexColor(EIXO))
        c.drawString(20 * mm, 10 * mm, rodape); c.drawRightString(190 * mm, 10 * mm, f'Página {doc.page}')
        c.restoreState()

    doc = SimpleDocTemplate(buf, pagesize=A4, leftMargin=20 * mm, rightMargin=20 * mm, topMargin=18 * mm,
                            bottomMargin=18 * mm, title=titulo, author='ThermoTrace', subject=f"Sessão {d['sessao_id']}",
                            creator='ThermoTrace API', invariant=1)
    doc.build(corpo, onFirstPage=pagina, onLaterPages=pagina)
    return buf.getvalue()


def viagem(d, conferir_em=None):
    e = _estilos(); r = d['resumo'] or {}
    corpo = [Paragraph(escape(f"ThermoTrace · {d['codigo_carga']} · viagem"), e['h1'])]
    if d.get('origem') == 'simulacao':
        corpo.append(Paragraph('SIMULAÇÃO: dados fictícios, sem etiqueta real.', e['alerta']))
    corpo.append(Paragraph(escape(f"Origem: {d.get('origem')}. Conferência: {d['conferencia']}. "
                                  f"Faixa: {_n(float(d['faixa']['min_c']))} a {_n(float(d['faixa']['max_c']))} °C."), e['p']))
    if r:
        corpo.append(Paragraph(escape(
            f"{r.get('quantidade', 0)} medições de {r.get('primeiro_instante', '')} a {r.get('ultimo_instante', '')}. "
            f"Mínima {_n(r.get('minima_c'))} °C, máxima {_n(r.get('maxima_c'))} °C. {_n(r.get('percentual_na_faixa'))}% na faixa; "
            f"{r.get('excursoes', 0)} excursão(ões), {r.get('tempo_fora_nominal_s', 0) // 60} min fora da faixa."), e['p']))
    else:
        corpo.append(Paragraph('Sem série utilizável.', e['p']))
    for a in d['avisos']:
        corpo.append(Paragraph('• ' + escape(a), e['p']))
    corpo += [Spacer(1, 4 * mm), _grafico(d), Paragraph('Faixa verde: limites do perfil térmico. Horários nominais em UTC.', e['pequeno'])]
    corpo.append(Paragraph('Excursões', e['h2']))
    if d['excursoes']:
        corpo.append(_tabela(['Tipo', 'Início', 'Fim', 'Duração nominal', 'Extremo'],
                             [['Abaixo' if x['tipo'] == 'abaixo' else 'Acima', x['inicio'], x['fim'],
                               f"{x['duracao_nominal_s'] // 60} min", f"{_n(x['extremo_c'])} °C"] for x in d['excursoes']],
                             [22 * mm, 42 * mm, 42 * mm, 32 * mm, 32 * mm]))
    else:
        corpo.append(Paragraph('Nenhuma medição fora da faixa.', e['p']))
    corpo.append(Paragraph('Coletas consideradas', e['h2']))
    corpo.append(_tabela(['Ordem', 'Tipo', 'Lida em', 'Medições', 'Uso'],
                         [[str(c['ordem_recebimento']), c['tipo'], str(c['lida_em']), str(c['medicoes']),
                           'base' if c['leitura_id'] == d['base_leitura_id'] else ('conferida' if c['utilizavel'] else 'sem série')]
                          for c in d['coletas']], [16 * mm, 28 * mm, 60 * mm, 22 * mm, 44 * mm]))
    rastro = [Paragraph('Rastreabilidade', e['h2']),
              Paragraph(escape(f"Sessão: {d['sessao_id']}"), e['mono']), Paragraph(escape(f"Versão: {d['versao']}"), e['mono']),
              Paragraph(escape(f"Coleta base: {d['base_leitura_id']}"), e['mono']),
              Paragraph(escape(f"Hash da projeção: {d['hash_projecao']}"), e['mono'])]
    if conferir_em:
        texto = Paragraph('<b>Conferir este relatório:</b> aponte a câmera para o código ou abra o endereço abaixo e '
                          'confirme que o servidor emitiu esta sessão com este hash, sem precisar de conta.<br/><font name="Courier" size="7">'
                          + escape(conferir_em) + '</font>', e['p'])
        t = Table([[_qr(conferir_em), texto]], colWidths=[36 * mm, 134 * mm])
        t.setStyle(TableStyle([('VALIGN', (0, 0), (-1, -1), 'MIDDLE')]))
        rastro.append(t)
    corpo.append(KeepTogether(rastro))
    corpo.append(Paragraph('A viagem usa a coleta mais completa e confere as demais nos índices em comum; não soma históricos repetidos. '
                           + AVISO_FISICO, e['pequeno']))
    return _documento(f"Relatório da viagem {d['codigo_carga']}", d, corpo)


def coleta(d):
    e = _estilos()
    corpo = [Paragraph(escape(f"ThermoTrace · {d['codigo_carga']} · coleta {d['tipo']}"), e['h1'])]
    if d.get('origem') == 'simulacao':
        corpo.append(Paragraph('SIMULAÇÃO: dados fictícios, sem etiqueta real.', e['alerta']))
    corpo += [Paragraph(escape(f"Origem: {d['origem']}. Coletada em {d['lida_em']}; recebida no servidor em {d['recebida_em']}."), e['p']),
              Paragraph(escape(f"Conferência: {d['conferencia']}. {len(d['pontos'])} medições. "
                               f"Faixa: {_n(float(d['faixa']['min_c']))} a {_n(float(d['faixa']['max_c']))} °C."), e['p'])]
    for a in d['avisos']:
        corpo.append(Paragraph('• ' + escape(a), e['p']))
    corpo += [Spacer(1, 4 * mm), _grafico(d), Paragraph('Faixa verde: limites do perfil térmico. Horários nominais em UTC.', e['pequeno']),
              KeepTogether([Paragraph('Rastreabilidade', e['h2'])] + [Paragraph(escape(t), e['mono']) for t in (
                  f"Coleta: {d['leitura_id']}", f"Sessão: {d['sessao_id']}", f"Versão: {d['versao']}",
                  f"Decodificador de origem: {d['decodificador_origem']}", f"Hash da evidência: {d['hash_evidencia']}",
                  f"Hash da projeção: {d['hash_projecao']}")]),
              Paragraph('Esta é uma coleta individual; não soma históricos repetidos. O CSV traz todas as medições. ' + AVISO_FISICO, e['pequeno'])]
    return _documento(f"Relatório da coleta {d['codigo_carga']}", d, corpo)


def _fonte(tamanho):
    # Bitstream Vera vem dentro do reportlab e tem os acentos; a fonte padrão do Pillow não tem.
    caminho = Path(reportlab.__file__).parent / 'fonts' / 'Vera.ttf'
    return ImageFont.truetype(str(caminho), tamanho)


def png(d, titulo):
    """Gráfico em PNG (1800 × 820) para colar em e-mail ou mensagem. Mesmos pontos e faixa do SVG."""
    W, H = 1800, 820
    img = Image.new('RGB', (W, H), 'white'); dr = ImageDraw.Draw(img)
    fonte, pequena = _fonte(28), _fonte(22)
    pontos, faixa = d['pontos'], d['faixa']
    dr.text((150, 24), f"{d['codigo_carga']} · {titulo} · {d['conferencia']}", fill=TINTA, font=fonte)
    if not pontos:
        dr.text((150, 380), 'Sem série térmica disponível. Consulte o relatório.', fill=TINTA, font=fonte)
    else:
        esq, dir_, topo, base = 150, W - 60, 90, H - 150
        temps = [p['temperatura_c'] for p in pontos] + [faixa['min_c'], faixa['max_c']]
        low, high = min(temps), max(temps); pad = max((high - low) * .15, 1); low -= pad; high += pad
        n = len(pontos)
        x = lambda i: esq + (i / (n - 1) if n > 1 else .5) * (dir_ - esq)
        y = lambda t: base - (t - low) / (high - low) * (base - topo)
        dr.rectangle([esq, y(faixa['max_c']), dir_, y(faixa['min_c'])], fill=FAIXA)
        dr.line([(esq, topo), (esq, base), (dir_, base)], fill=EIXO, width=2)
        for i in range(5):
            t = low + (high - low) * i / 4
            dr.text((esq - 14, y(t)), f'{t:.1f}'.replace('.', ','), fill=TINTA, font=pequena, anchor='rm')
        dr.text((40, topo - 10), '°C', fill=TINTA, font=pequena)
        coords = [(x(i), y(p['temperatura_c'])) for i, p in enumerate(pontos)]
        if n == 1:
            cx, cy = coords[0]; dr.ellipse([cx - 6, cy - 6, cx + 6, cy + 6], fill=LINHA)
        else:
            dr.line(coords, fill=LINHA, width=4, joint='curve')
        dr.text((esq, base + 20), str(pontos[0]['instante']), fill=TINTA, font=pequena)
        dr.text((dir_, base + 20), str(pontos[-1]['instante']), fill=TINTA, font=pequena, anchor='ra')
    dr.text((150, H - 70), f"Horários nominais UTC · faixa verde: {_n(float(faixa['min_c']))} a {_n(float(faixa['max_c']))} °C · "
                           f"projeção {d['hash_projecao'][:16]}… · sem validação física", fill=TINTA, font=pequena)
    buf = io.BytesIO(); img.save(buf, format='PNG', optimize=True)
    return buf.getvalue()
