"""TT-041: PDF e PNG gerados no servidor, com os mesmos dados e a mesma autorização dos outros formatos."""
import hashlib
import io
import os
from pathlib import Path
from uuid import UUID

from PIL import Image
from sqlalchemy import select

from app.models import LogAuditoria
from tests.test_acesso_perfis import ambiente, headers  # noqa: F401
from tests.test_temperaturas import preparar_grafico
from tests.test_viagem import PONTOS, coletar
from tests.test_ingestao import preparar_ingestao, iniciar


def _salvar(nome, conteudo):
    # THERMOTRACE_SALVAR_RELATORIOS=pasta guarda os arquivos para olhar à mão.
    pasta = os.environ.get('THERMOTRACE_SALVAR_RELATORIOS')
    if pasta:
        Path(pasta, nome).write_bytes(conteudo)


def _viagem(ambiente):  # noqa: F811
    d = preparar_ingestao(ambiente); assert iniciar(d).status_code == 201
    coletar(d, PONTOS[:5], 60); coletar(d, PONTOS, 100, 'final')
    return d, f"/api/v1/sessoes/{d[-1]['sessao_id']}/viagem"


def test_pdf_da_viagem_traz_os_dados_e_o_link_de_conferencia(ambiente):  # noqa: F811
    d, path = _viagem(ambiente)
    c, db, _, h = d[0], d[1], d[2], d[3]
    j = c.get(path, headers=h).json()
    r = c.get(path + '?formato=pdf', headers=h)
    assert r.status_code == 200, r.text
    assert r.headers['content-type'] == 'application/pdf'
    assert r.headers['content-disposition'].endswith('.pdf"') and 'attachment' in r.headers['content-disposition']
    pdf = r.content
    assert pdf.startswith(b'%PDF-') and pdf.rstrip().endswith(b'%%EOF')
    _salvar('viagem.pdf', pdf)
    # Determinístico: baixar de novo dá o mesmo arquivo, e o hash da auditoria confere.
    assert c.get(path + '?formato=pdf', headers=h).content == pdf
    log = db.scalars(select(LogAuditoria).where(LogAuditoria.acao == 'viagem_exportada',
                                                LogAuditoria.entidade_id == UUID(d[-1]['sessao_id']))).all()[-1]
    assert log.depois['formato'] == 'pdf' and log.depois['sha256_arquivo'] == hashlib.sha256(pdf).hexdigest()
    assert log.depois['hash_projecao'] == j['hash_projecao']
    assert b'/Title' in pdf and j['codigo_carga'].encode() in pdf


def test_png_da_viagem(ambiente):  # noqa: F811
    d, path = _viagem(ambiente)
    c, h = d[0], d[3]
    r = c.get(path + '?formato=png', headers=h)
    assert r.status_code == 200 and r.headers['content-type'] == 'image/png'
    img = Image.open(io.BytesIO(r.content)); assert img.format == 'PNG' and img.size == (1800, 820)
    _salvar('viagem.png', r.content)
    assert c.get(path + '?formato=png', headers=h).content == r.content


def test_pdf_e_png_da_coleta(ambiente):  # noqa: F811
    d2, coleta, _ = preparar_grafico(ambiente)
    for formato, mime in (('pdf', 'application/pdf'), ('png', 'image/png')):
        r = d2[0].get(f'{coleta}?formato={formato}', headers=d2[3])
        assert r.status_code == 200 and r.headers['content-type'] == mime, r.text
        _salvar(f'coleta.{formato}', r.content)
    log = d2[1].scalars(select(LogAuditoria).where(LogAuditoria.acao == 'temperaturas_exportadas')).all()
    assert {l.depois['formato'] for l in log} >= {'pdf', 'png'}


def test_pdf_segue_a_mesma_autorizacao(ambiente):  # noqa: F811
    d, path = _viagem(ambiente)
    c = d[0]
    assert c.get(path + '?formato=pdf').status_code == 401
    assert c.get(path + '?formato=pdf', headers=headers(ambiente[3]('b'))).status_code == 404
    assert c.get(path + '?formato=png', headers=headers(ambiente[3]('staff'))).status_code == 404


def test_viagem_sem_serie_ainda_gera_pdf_legivel():
    from app.services import relatorio_pdf
    d = {'codigo_carga': 'SEM-SERIE', 'versao': 'v', 'hash_projecao': '0' * 64, 'sessao_id': 's', 'origem': 'declaracao',
         'conferencia': 'nao_avaliavel', 'faixa': {'min_c': 2.0, 'max_c': 8.0}, 'resumo': None, 'avisos': ['Sem coleta.'],
         'pontos': [], 'excursoes': [], 'coletas': [], 'base_leitura_id': None}
    assert relatorio_pdf.viagem(d).startswith(b'%PDF-')
    assert relatorio_pdf.png(d, 'viagem').startswith(b'\x89PNG')
