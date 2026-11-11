"""Acompanhamento da operação: o que está em campo e o que pode estar parado no celular.

Responde a duas perguntas do gestor, só com dados que já chegaram ao servidor:

1. Quais etiquetas seguem registrando sem leitura final, e quando a memória programada
   acaba? Depois desse instante a etiqueta para de gravar; a leitura final precisa
   acontecer, ou a viagem fica sem a parte final.
2. Algum aparelho parou de enviar, ou envia com muito atraso? Atraso grande entre a
   leitura no celular e a chegada ao servidor indica fila presa ou celular sem rede.

Nada aqui altera dados nem conclui sobre a temperatura. O fim previsto é nominal
(início + atraso programado + intervalo × quantidade); não corrige o relógio da etiqueta.
"""
from datetime import timedelta

from sqlalchemy import func, or_, select

from app.models import (Dispositivo, Empresa, LeituraEtiqueta, Remessa, SessaoMonitoramento, Usuario,
                        VinculoEtiqueta, Volume)
from app.models.enums import StatusSessao
from app.services.compartilhamentos import operaveis_por

VERSAO = 'tt-acompanhamento-1'
JANELA_APARELHOS = timedelta(days=7)
LIMITE_SESSOES = 200


def fim_previsto(s):
    return s.inicio_dispositivo_em + timedelta(minutes=s.delay_minutos,
                                               seconds=s.intervalo_segundos * s.quantidade_planejada)


def situacao_sessao(fim, ultima_coleta, agora, sem_coleta):
    if agora >= fim:
        return 'registro_encerrado'
    if agora - ultima_coleta >= sem_coleta:
        return 'sem_coleta_recente'
    return 'em_andamento'


ORDEM = {'registro_encerrado': 0, 'sem_coleta_recente': 1, 'em_andamento': 2}


def sessoes_abertas(db, empresa_id, agora, sem_coleta):
    ultimas = (select(LeituraEtiqueta.sessao_id,
                      func.count().label('coletas'),
                      func.max(LeituraEtiqueta.lida_em_dispositivo).label('ultima_coleta'),
                      func.max(LeituraEtiqueta.recebida_em_servidor).label('ultima_recepcao'))
               .where(LeituraEtiqueta.contrato_ingestao == '1')
               .group_by(LeituraEtiqueta.sessao_id).subquery())
    rows = db.execute(
        select(SessaoMonitoramento, Remessa.id, Remessa.codigo, Remessa.empresa_embarcador_id, Volume.id, Volume.codigo_externo, Volume.sequencia,
               ultimas.c.coletas, ultimas.c.ultima_coleta, ultimas.c.ultima_recepcao)
        .join(VinculoEtiqueta, VinculoEtiqueta.id == SessaoMonitoramento.vinculo_id)
        .join(Volume, Volume.id == VinculoEtiqueta.volume_id)
        .join(Remessa, Remessa.id == SessaoMonitoramento.remessa_id)
        .join(ultimas, ultimas.c.sessao_id == SessaoMonitoramento.id)
        # Filtro explícito além do RLS: cargas da própria empresa e as que ela pode operar agora.
        # Carga compartilhada só para consulta fica fora: a leitura final não cabe a esta empresa.
        .where(or_(Remessa.empresa_embarcador_id == empresa_id, Remessa.id.in_(operaveis_por(empresa_id))),
               SessaoMonitoramento.contrato_ingestao == '1',
               SessaoMonitoramento.status == StatusSessao.ATIVA)
        .order_by(SessaoMonitoramento.inicio_dispositivo_em, SessaoMonitoramento.id)
        .limit(LIMITE_SESSOES + 1)).all()
    alheias = {dona for _, _, _, dona, *_ in rows if dona != empresa_id}
    donas = ({e.id: e.razao_social for e in db.scalars(select(Empresa).where(Empresa.id.in_(alheias)))}
             if alheias else {})
    saida = []
    for s, rid, codigo, dona, vid, externo, seq, coletas, ultima, recepcao in rows[:LIMITE_SESSOES]:
        fim = fim_previsto(s)
        saida.append({
            'sessao_id': s.id, 'remessa_id': rid, 'remessa_codigo': codigo,
            'acesso': 'propria' if dona == empresa_id else 'compartilhada',
            'empresa_dona_id': None if dona == empresa_id else dona,
            'empresa_dona': None if dona == empresa_id else donas.get(dona),
            'volume_id': vid, 'volume': externo or f'Volume {seq}',
            'iniciada_em': s.inicio_dispositivo_em, 'coletas': coletas,
            'ultima_coleta_em': ultima, 'ultima_recepcao_em': recepcao,
            'fim_previsto_memoria': fim,
            'horas_ate_fim_previsto': round((fim - agora).total_seconds() / 3600, 1),
            'situacao': situacao_sessao(fim, ultima, agora, sem_coleta),
        })
    saida.sort(key=lambda x: (ORDEM[x['situacao']], x['fim_previsto_memoria']))
    return saida, len(rows) > LIMITE_SESSOES


def aparelhos(db, empresa_id, agora):
    desde = agora - JANELA_APARELHOS
    atraso = func.extract('epoch', LeituraEtiqueta.recebida_em_servidor - LeituraEtiqueta.lida_em_dispositivo)
    resumo = (select(LeituraEtiqueta.dispositivo_id,
                     func.max(LeituraEtiqueta.recebida_em_servidor).label('ultima_recepcao'))
              .where(LeituraEtiqueta.contrato_ingestao == '1')
              .group_by(LeituraEtiqueta.dispositivo_id).subquery())
    recentes = (select(LeituraEtiqueta.dispositivo_id,
                       func.count().label('coletas'),
                       func.max(atraso).label('maior_atraso_s'))
                .where(LeituraEtiqueta.contrato_ingestao == '1', LeituraEtiqueta.recebida_em_servidor >= desde)
                .group_by(LeituraEtiqueta.dispositivo_id).subquery())
    ultimo_operador = (select(Usuario.nome)
                       .join(LeituraEtiqueta, LeituraEtiqueta.lida_por == Usuario.id)
                       .where(LeituraEtiqueta.dispositivo_id == Dispositivo.id)
                       .order_by(LeituraEtiqueta.recebida_em_servidor.desc()).limit(1)
                       .correlate(Dispositivo).scalar_subquery())
    rows = db.execute(
        select(Dispositivo, resumo.c.ultima_recepcao, recentes.c.coletas, recentes.c.maior_atraso_s,
               ultimo_operador.label('ultimo_operador'))
        .outerjoin(resumo, resumo.c.dispositivo_id == Dispositivo.id)
        .outerjoin(recentes, recentes.c.dispositivo_id == Dispositivo.id)
        .where(Dispositivo.empresa_id == empresa_id)
        .order_by(resumo.c.ultima_recepcao.desc().nulls_last(), Dispositivo.id)).all()
    return [{
        'id': d.id, 'modelo': d.modelo, 'versao_app': d.versao_app, 'ativo': d.ativo,
        'ultima_recepcao_em': ultima, 'coletas_7_dias': coletas or 0,
        'maior_atraso_envio_7_dias_s': int(maior) if maior is not None else None,
        'ultimo_operador': operador,
    } for d, ultima, coletas, maior, operador in rows]


def painel(db, empresa_id, agora, horas_sem_coleta=24):
    sem_coleta = timedelta(hours=horas_sem_coleta)
    sessoes, ha_mais = sessoes_abertas(db, empresa_id, agora, sem_coleta)
    contagem = {k: sum(1 for s in sessoes if s['situacao'] == k) for k in ORDEM}
    return {
        'versao': VERSAO, 'gerado_em': agora, 'horas_sem_coleta': horas_sem_coleta,
        'totais': {'sessoes_abertas': len(sessoes), **contagem},
        'sessoes_abertas': sessoes, 'ha_mais_sessoes': ha_mais,
        'aparelhos': aparelhos(db, empresa_id, agora),
        'limites': ['Mostra só o que já chegou ao servidor; coletas ainda no celular não aparecem.',
                    'Fim previsto nominal: início + atraso programado + intervalo × quantidade.'],
    }
