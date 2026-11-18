"""Conformidade térmica: quais viagens recentes da empresa ficaram na faixa e quais não.

Responde a pergunta que o gestor fazia abrindo carga por carga. Cada linha é a
viagem consolidada da sessão (WEB-12), a mesma do relatório: nada é recalculado
de outro jeito. Só cargas da própria empresa; carga de outra empresa aparece para
o dono dela.

Situação da viagem, na ordem em que a primeira condição vale:
- sem_dados: nenhuma coleta com série utilizável;
- divergente: coletas discordam entre si nos índices em comum (precisa de análise);
- excursao: houve medição fora da faixa;
- em_andamento: dentro da faixa até agora, sem leitura final;
- conforme: leitura final recebida e tudo dentro da faixa.

"Conforme" aqui é só sobre a série recebida; não comprova calibração, STOP físico
nem libera a carga.
"""
from collections import defaultdict

from sqlalchemy import select

from app.models import LeituraEtiqueta, Remessa, SessaoMonitoramento, VinculoEtiqueta, Volume
from app.services import ingestao, viagem

VERSAO = 'tt-conformidade-1'
LIMITE = 100
SITUACOES = ('conforme', 'excursao', 'divergente', 'em_andamento', 'sem_dados')


def situacao(v):
    r = v['resumo']
    if not r:
        return 'sem_dados'
    if v['conferencia'] == 'divergente':
        return 'divergente'
    if r['excursoes']:
        return 'excursao'
    if not r['inclui_leitura_final']:
        return 'em_andamento'
    return 'conforme'


def painel(db, empresa_id, agora):
    linhas = db.execute(
        select(SessaoMonitoramento, Remessa, Volume)
        .join(Remessa, Remessa.id == SessaoMonitoramento.remessa_id)
        .join(VinculoEtiqueta, VinculoEtiqueta.id == SessaoMonitoramento.vinculo_id)
        .join(Volume, Volume.id == VinculoEtiqueta.volume_id)
        .where(Remessa.empresa_embarcador_id == empresa_id, SessaoMonitoramento.contrato_ingestao == '1')
        .order_by(SessaoMonitoramento.inicio_servidor_em.desc()).limit(LIMITE + 1)).all()
    ha_mais = len(linhas) > LIMITE
    linhas = linhas[:LIMITE]
    leituras = defaultdict(list)
    if linhas:
        for l in db.scalars(select(LeituraEtiqueta).where(
                LeituraEtiqueta.sessao_id.in_([s.id for s, _, _ in linhas]), LeituraEtiqueta.contrato_ingestao == '1')
                .order_by(LeituraEtiqueta.sessao_id, LeituraEtiqueta.ordem_recebimento)):
            leituras[l.sessao_id].append(l)
    viagens, totais = [], dict.fromkeys(SITUACOES, 0)
    for s, r, vol in linhas:
        integra = ingestao.verificar_integridade(db, empresa_id, s.id)['integra'] if leituras[s.id] else False
        v = viagem.consolidar(s, leituras[s.id], r, integra)
        sit = situacao(v)
        totais[sit] += 1
        res = v['resumo'] or {}
        extremos = [e['extremo_c'] for e in v['excursoes']]
        viagens.append({
            'sessao_id': str(s.id), 'remessa_id': str(r.id), 'remessa_codigo': r.codigo,
            'volume': vol.codigo_externo or f'Volume {vol.sequencia}',  # mesmo rótulo do acompanhamento
            'iniciada_em': s.inicio_servidor_em.isoformat(), 'situacao': sit,
            'percentual_na_faixa': res.get('percentual_na_faixa'),
            'tempo_fora_nominal_s': res.get('tempo_fora_nominal_s', 0),
            'excursoes': len(v['excursoes']),
            'pior_extremo_c': (max(extremos, key=lambda t: max(t - v['faixa']['max_c'], v['faixa']['min_c'] - t))
                               if extremos else None),
            'faixa': v['faixa'], 'inclui_leitura_final': res.get('inclui_leitura_final', False),
            'origem': v.get('origem'), 'integridade_da_cadeia': integra, 'hash_projecao': v['hash_projecao'],
        })
    avaliadas = totais['conforme'] + totais['excursao']
    return {
        'versao': VERSAO, 'regra_viagem': viagem.VERSAO, 'gerado_em': agora.isoformat(),
        'totais': {**totais, 'viagens': len(viagens),
                   # Taxa só entre viagens concluídas ou já com excursão; em andamento ainda pode mudar.
                   'taxa_conformidade': round(100 * totais['conforme'] / avaliadas, 1) if avaliadas else None},
        'viagens': viagens, 'ha_mais': ha_mais,
        'limites': [f'Mostra as {LIMITE} sessões mais recentes da empresa.',
                    'Conforme se refere à série recebida; não comprova calibração, STOP físico nem libera a carga.'],
    }
