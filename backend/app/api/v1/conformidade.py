import csv
import hashlib
import io
from typing import Literal

from fastapi import APIRouter, Depends, Response
from sqlalchemy.orm import Session

from app.api.deps import Acesso, operador
from app.core.security import agora
from app.db.session import obter_sessao
from app.services import conformidade
from app.services.temperaturas import decimal_br
from app.services.auditoria import auditar

router = APIRouter(tags=['Conformidade térmica'])

ROTULOS = {'conforme': 'Conforme', 'excursao': 'Com excursão', 'divergente': 'Coletas divergentes',
           'em_andamento': 'Em andamento', 'sem_dados': 'Sem dados'}
COLUNAS = ['carga', 'volume', 'sessao_id', 'iniciada_em_utc', 'situacao', 'percentual_na_faixa',
           'tempo_fora_min', 'excursoes', 'pior_extremo_c', 'faixa_min_c', 'faixa_max_c',
           'leitura_final', 'origem', 'cadeia_integra', 'hash_projecao']


def _csv(p):
    s = io.StringIO(newline='')
    w = csv.writer(s, delimiter=';')
    w.writerow(['regra', p['versao'], 'viagem', p['regra_viagem'], 'gerado_em_utc', p['gerado_em']])
    w.writerow(['aviso', p['limites'][1]])
    w.writerow(COLUNAS)
    for v in p['viagens']:
        w.writerow([v['remessa_codigo'], v['volume'], v['sessao_id'], v['iniciada_em'], ROTULOS[v['situacao']],
                    decimal_br(v['percentual_na_faixa']), (v['tempo_fora_nominal_s'] or 0) // 60, v['excursoes'],
                    decimal_br(v['pior_extremo_c']), decimal_br(v['faixa']['min_c']), decimal_br(v['faixa']['max_c']),
                    'sim' if v['inclui_leitura_final'] else 'nao', v['origem'],
                    'sim' if v['integridade_da_cadeia'] else 'nao', v['hash_projecao']])
    # BOM e vírgula decimal: o Excel em português abre acentos, colunas e números sem importação manual.
    return '﻿' + s.getvalue()


@router.get('/conformidade')
def painel(response: Response, formato: Literal['json', 'csv'] = 'json',
           acesso: Acesso = Depends(operador), db: Session = Depends(obter_sessao)):
    """Viagens recentes das cargas da empresa: conformes, com excursão, divergentes, em andamento."""
    p = conformidade.painel(db, acesso.empresa.id, agora())
    evento = {'viagens': p['totais']['viagens'], 'versao': p['versao'], 'formato': formato}
    if formato == 'json':
        response.headers['Cache-Control'] = 'no-store'
        auditar(db, 'conformidade_consultada', 'empresa', acesso.empresa.id, acesso.usuario, depois=evento)
        db.commit()
        return p
    conteudo = _csv(p)
    evento['sha256_arquivo'] = hashlib.sha256(conteudo.encode('utf-8')).hexdigest()
    auditar(db, 'conformidade_exportada', 'empresa', acesso.empresa.id, acesso.usuario, depois=evento)
    db.commit()
    return Response(conteudo, media_type='text/csv', headers={
        'Content-Disposition': f'attachment; filename="ThermoTrace-conformidade-{agora():%Y%m%d-%H%M}.csv"',
        'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff'})
