#!/usr/bin/env python3
"""
ThermoTrace — exportador de laudo em Excel.

Lê o banco de auditoria e gera uma pasta de trabalho .xlsx com cinco abas:

    Resumo      uma linha por volume, com MKT e tempo fora de faixa
    Medições    a série completa, ponto a ponto, marcando o que saiu da faixa
    Gráficos    temperatura × tempo por volume, com as linhas de limite
    Excursões   cada desvio contínuo, com duração, pico e gravidade
    Custódia    quando aconteceu × quando foi lançado
    Auditoria   quem leu, com qual aparelho, e o hash do dado bruto

Por que este arquivo existe em vez de exportar do celular:
o app de referência do fabricante gera .xls pelo Android (biblioteca `jxl`,
formato de 1997) e .xlsx pelo iOS (libxlsxwriter) — dois arquivos
incompatíveis para o mesmo dado, nenhum reproduzível depois. Gerando do
banco, a planilha pode ser regerada anos depois a partir da mesma evidência.

Uso:
    pip install psycopg[binary] xlsxwriter
    python exportar_excel.py --dsn postgresql://user:senha@host/thermotrace \\
                             --remessa REM-2026-00184 \\
                             --saida laudo.xlsx
"""

from __future__ import annotations

import argparse
import datetime as dt
import sys
from typing import Any

try:
    import psycopg
    import xlsxwriter
except ImportError:
    sys.exit("Faltam dependências. Rode: pip install 'psycopg[binary]' xlsxwriter")


# ---------------------------------------------------------------------
# Leitura
# ---------------------------------------------------------------------

ABAS = [
    ("Resumo",    "v_export_resumo"),
    ("Medições",  "v_export_medicoes"),
    ("Excursões", "v_export_excursoes"),
    ("Custódia",  "v_export_custodia"),
    ("Auditoria", "v_export_auditoria"),
]


def buscar(cur, view: str, shipment_id: str) -> tuple[list[str], list[tuple]]:
    """Colunas que começam com _ são internas: usadas para juntar, não exibidas."""
    cur.execute(
        f'SELECT * FROM tt.{view} WHERE _shipment_id = %s',  # noqa: S608 - nome de view é constante
        (shipment_id,),
    )
    todas = [d.name for d in cur.description]
    visiveis = [i for i, nome in enumerate(todas) if not nome.startswith("_")]
    cabecalho = [todas[i] for i in visiveis]
    linhas = [tuple(linha[i] for i in visiveis) for linha in cur.fetchall()]
    return cabecalho, linhas


def resolver_remessa(cur, code: str) -> tuple[str, str]:
    cur.execute("SELECT id, code FROM tt.shipment WHERE code = %s", (code,))
    linha = cur.fetchone()
    if not linha:
        sys.exit(f"Remessa '{code}' não encontrada.")
    return str(linha[0]), linha[1]


# ---------------------------------------------------------------------
# Escrita
# ---------------------------------------------------------------------

class Estilos:
    def __init__(self, wb: xlsxwriter.Workbook):
        self.titulo = wb.add_format({
            "bold": True, "font_size": 14, "font_color": "#1F3864"})
        self.subtitulo = wb.add_format({"font_size": 9, "font_color": "#666666"})
        self.cabecalho = wb.add_format({
            "bold": True, "bg_color": "#1F3864", "font_color": "white",
            "border": 1, "text_wrap": True, "valign": "vcenter"})
        self.data = wb.add_format({"num_format": "dd/mm/yyyy hh:mm"})
        self.numero = wb.add_format({"num_format": "0.00"})
        self.fora = wb.add_format({"bg_color": "#FFC7CE", "font_color": "#9C0006"})
        self.ok = wb.add_format({"bg_color": "#C6EFCE", "font_color": "#006100"})
        self.alerta = wb.add_format({"bg_color": "#FFEB9C", "font_color": "#9C5700"})
        self.mono = wb.add_format({"font_name": "Consolas", "font_size": 8})


def largura(cabecalho: str, valores: list[Any]) -> int:
    maior = max([len(str(cabecalho))] + [len(str(v)) for v in valores[:200]] or [10])
    return min(max(maior + 2, 10), 42)


def escrever_aba(wb, est: Estilos, nome: str, cabecalho: list[str], linhas: list[tuple]):
    ws = wb.add_worksheet(nome)
    ws.freeze_panes(1, 0)

    if not cabecalho:
        ws.write(0, 0, "Sem dados.")
        return ws

    for c, titulo in enumerate(cabecalho):
        ws.write(0, c, titulo, est.cabecalho)
        coluna = [linha[c] for linha in linhas]
        ws.set_column(c, c, largura(titulo, coluna))

    for r, linha in enumerate(linhas, start=1):
        for c, valor in enumerate(linha):
            if isinstance(valor, (dt.datetime, dt.date)):
                ws.write_datetime(r, c, valor, est.data)
            elif isinstance(valor, float):
                ws.write_number(r, c, valor, est.numero)
            elif valor is None:
                ws.write_blank(r, c, None)
            elif isinstance(valor, str) and len(valor) > 40 and nome == "Auditoria":
                ws.write_string(r, c, valor, est.mono)
            else:
                ws.write(r, c, valor)

    ws.autofilter(0, 0, max(len(linhas), 1), len(cabecalho) - 1)

    # Realce das colunas de veredito — o auditor tem que ver na primeira olhada.
    for alvo, regras in {
        "Situação": [("Acima", est.fora), ("Abaixo", est.fora), ("Na faixa", est.ok)],
        "Resultado": [("Excursão relevante", est.fora), ("Alerta", est.alerta),
                      ("Conforme", est.ok), ("Sem leitura", est.alerta)],
        "Gravidade": [("Crítica", est.fora), ("Requer ação", est.fora), ("Alerta", est.alerta)],
        "Ativação confirmada": [("NÃO", est.fora), ("Sim", est.ok)],
    }.items():
        if alvo in cabecalho:
            c = cabecalho.index(alvo)
            for texto, fmt in regras:
                ws.conditional_format(1, c, max(len(linhas), 1), c, {
                    "type": "cell", "criteria": "==", "value": f'"{texto}"', "format": fmt})

    return ws


def escrever_graficos(wb, est: Estilos, medicoes_cab, medicoes, resumo_cab, resumo):
    """Um gráfico por volume, com as linhas de limite mínimo e máximo."""
    if not medicoes:
        return

    ws = wb.add_worksheet("Gráficos")
    i_volume = medicoes_cab.index("Volume")
    i_data = medicoes_cab.index("Data/hora")
    i_temp = medicoes_cab.index("Temperatura (°C)")
    i_min = medicoes_cab.index("Mínimo (°C)")
    i_max = medicoes_cab.index("Máximo (°C)")

    volumes: dict[Any, list[int]] = {}
    for pos, linha in enumerate(medicoes, start=1):   # +1 por causa do cabeçalho
        volumes.setdefault(linha[i_volume], []).append(pos)

    linha_grafico = 0
    for volume, posicoes in sorted(volumes.items(), key=lambda kv: str(kv[0])):
        primeira, ultima = posicoes[0], posicoes[-1]
        rotulo = next((r for r in resumo if r[resumo_cab.index("Volume")] == volume), None)
        etiqueta = rotulo[resumo_cab.index("Etiqueta")] if rotulo else ""

        ws.write(linha_grafico, 0, f"Volume {volume} — etiqueta {etiqueta}", est.titulo)

        grafico = wb.add_chart({"type": "line"})
        grafico.add_series({
            "name":       f"Temperatura — volume {volume}",
            "categories": ["Medições", primeira, i_data, ultima, i_data],
            "values":     ["Medições", primeira, i_temp, ultima, i_temp],
            "line":       {"width": 1.5, "color": "#1F3864"},
        })
        # As linhas de limite são colunas constantes na aba Medições —
        # por isso viram duas retas horizontais sem precisar de célula extra.
        grafico.add_series({
            "name":       "Limite mínimo",
            "categories": ["Medições", primeira, i_data, ultima, i_data],
            "values":     ["Medições", primeira, i_min, ultima, i_min],
            "line":       {"width": 1.0, "color": "#C00000", "dash_type": "dash"},
        })
        grafico.add_series({
            "name":       "Limite máximo",
            "categories": ["Medições", primeira, i_data, ultima, i_data],
            "values":     ["Medições", primeira, i_max, ultima, i_max],
            "line":       {"width": 1.0, "color": "#C00000", "dash_type": "dash"},
        })
        grafico.set_title({"name": f"Volume {volume} — temperatura × tempo"})
        grafico.set_x_axis({"name": "Data/hora", "num_font": {"rotation": -45, "size": 8}})
        grafico.set_y_axis({"name": "°C", "major_gridlines": {"visible": True}})
        grafico.set_size({"width": 900, "height": 380})
        grafico.set_legend({"position": "bottom"})

        ws.insert_chart(linha_grafico + 1, 0, grafico)
        linha_grafico += 22


def escrever_capa(wb, est: Estilos, code: str, resumo_cab, resumo, gerado_em: dt.datetime):
    ws = wb.add_worksheet("Laudo")
    ws.set_column(0, 0, 34)
    ws.set_column(1, 1, 52)

    ws.write(0, 0, "Laudo de monitoramento térmico", est.titulo)
    ws.write(1, 0, "Gerado a partir do banco de auditoria ThermoTrace.", est.subtitulo)

    def par(linha, rotulo, valor):
        ws.write(linha, 0, rotulo, est.cabecalho)
        ws.write(linha, 1, valor if valor is not None else "—")

    primeiro = resumo[0] if resumo else None
    def col(nome):
        return primeiro[resumo_cab.index(nome)] if primeiro and nome in resumo_cab else None

    par(3, "Remessa", code)
    par(4, "Embarcador", col("Embarcador"))
    par(5, "Transportadora", col("Transportadora"))
    par(6, "Destinatário", col("Destinatário"))
    par(7, "Documentos", col("Documentos"))
    par(8, "Perfil térmico", col("Perfil térmico"))
    par(9, "Volumes monitorados", len(resumo))
    par(10, "Certificado de calibração", col("Certificado de calibração"))
    par(11, "Gerado em", gerado_em.strftime("%d/%m/%Y %H:%M"))

    i_res = resumo_cab.index("Resultado") if "Resultado" in resumo_cab else None
    if i_res is not None:
        resultados = {linha[i_res] for linha in resumo}
        if "Excursão relevante" in resultados:
            veredito, fmt = "EXCURSÃO TÉRMICA DETECTADA", est.fora
        elif "Sem leitura" in resultados:
            veredito, fmt = "INCONCLUSIVO — há volume sem leitura final", est.alerta
        elif "Alerta" in resultados:
            veredito, fmt = "DESVIO EM NÍVEL DE ALERTA", est.alerta
        else:
            veredito, fmt = "CONFORME", est.ok
        ws.write(13, 0, "Resultado geral", est.cabecalho)
        ws.write(13, 1, veredito, fmt)

    ws.write(15, 0, "Como conferir", est.cabecalho)
    ws.write(15, 1, "A aba Auditoria traz o SHA-256 do dado bruto lido de cada etiqueta. "
                    "Rode tt.verify_chain(session_id) no banco para reconferir a cadeia.")
    ws.write(16, 1, "Os horários derivam do relógio do celular que ativou a etiqueta. "
                    "O desvio medido e a deriva do RTC estão na aba Auditoria.", est.subtitulo)


# ---------------------------------------------------------------------

def main() -> int:
    p = argparse.ArgumentParser(description="Exporta o laudo térmico de uma remessa para Excel.")
    p.add_argument("--dsn", required=True, help="DSN do PostgreSQL")
    p.add_argument("--remessa", required=True, help="Código da remessa, ex.: REM-2026-00184")
    p.add_argument("--saida", default=None, help="Arquivo .xlsx de saída")
    args = p.parse_args()

    gerado_em = dt.datetime.now()
    saida = args.saida or f"laudo_{args.remessa}_{gerado_em:%Y%m%d_%H%M}.xlsx"

    with psycopg.connect(args.dsn) as conn, conn.cursor() as cur:
        shipment_id, code = resolver_remessa(cur, args.remessa)
        dados = {nome: buscar(cur, view, shipment_id) for nome, view in ABAS}

    wb = xlsxwriter.Workbook(saida, {"default_date_format": "dd/mm/yyyy hh:mm",
                                     "remove_timezone": True})
    est = Estilos(wb)

    resumo_cab, resumo = dados["Resumo"]
    escrever_capa(wb, est, code, resumo_cab, resumo, gerado_em)

    for nome, _ in ABAS:
        cabecalho, linhas = dados[nome]
        escrever_aba(wb, est, nome, cabecalho, linhas)

    medicoes_cab, medicoes = dados["Medições"]
    escrever_graficos(wb, est, medicoes_cab, medicoes, resumo_cab, resumo)

    wb.close()

    total = len(medicoes)
    print(f"Gerado: {saida}")
    print(f"  {len(resumo)} volume(s), {total} medição(ões), "
          f"{len(dados['Excursões'][1])} excursão(ões).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
