"""Manifesto de integridade: âncora externa da cadeia de evidências (TT-060).

A cadeia local (`ingestao.verificar_integridade`) detecta alteração de uma leitura, mas
não detecta quem tem acesso de dono ao banco e **recalcula a cadeia inteira** depois de
alterar. Para isso a referência precisa estar fora do servidor.

O manifesto lista, por sessão, quantas leituras havia e o hash encadeado da última, e é
identificado por um hash do próprio conteúdo. O gestor baixa o arquivo e o guarda fora da
ThermoTrace (e-mail para si, pasta da qualidade, impressão). Na conferência, o servidor
verifica se cada sessão ainda tem, naquela posição, exatamente aquele hash. Leituras que
chegaram depois não atrapalham: a cadeia só cresce, e o ponto ancorado não muda.

O registro de emissão na auditoria guarda o hash do manifesto, para comprovar que aquele
arquivo saiu do servidor naquela data. O manifesto não contém temperaturas nem dados de
carga; só identificadores e hashes da própria empresa.
"""
from uuid import UUID

from sqlalchemy import func, select

from app.core.errors import RegraDeNegocio
from app.models import LeituraEtiqueta, Remessa, SessaoMonitoramento
from app.services import ingestao
from app.services.idempotencia import hash_json

TIPO = 'thermotrace-manifesto-integridade'
VERSAO = 'tt-manifesto-1'


def _sessoes(db, empresa_id):
    return list(db.scalars(select(SessaoMonitoramento.id)
        .join(Remessa, Remessa.id == SessaoMonitoramento.remessa_id)
        .where(Remessa.empresa_embarcador_id == empresa_id, SessaoMonitoramento.contrato_ingestao == '1')
        .order_by(SessaoMonitoramento.id)))


def _corpo(m):
    return {k: v for k, v in m.items() if k != 'hash_manifesto'}


def emitir(db, empresa_id, agora):
    entradas = []
    for sid in _sessoes(db, empresa_id):
        r = ingestao.verificar_integridade(db, empresa_id, sid)
        if not r['quantidade']:
            continue
        entradas.append({'sessao_id': str(sid), 'leituras': r['quantidade'], 'hash_final': r['hash_final'],
                         'cadeia_integra_na_emissao': r['integra']})
    m = {'tipo': TIPO, 'versao': VERSAO, 'empresa_id': str(empresa_id), 'emitido_em': agora.isoformat(),
         'total_sessoes': len(entradas), 'total_leituras': sum(e['leituras'] for e in entradas),
         'sessoes': entradas,
         'como_usar': 'Guarde este arquivo fora da ThermoTrace. Para conferir, envie-o de volta em '
                      'Integridade > Conferir manifesto. O arquivo não pode ser editado.'}
    m['hash_manifesto'] = hash_json(m)
    return m


def conferir(db, empresa_id, m):
    if not isinstance(m, dict) or m.get('tipo') != TIPO or m.get('versao') != VERSAO:
        raise RegraDeNegocio('Arquivo não é um manifesto de integridade ThermoTrace reconhecido.')
    if m.get('empresa_id') != str(empresa_id):
        raise RegraDeNegocio('Este manifesto foi emitido para outra empresa.')
    arquivo_intacto = hash_json(_corpo(m)) == m.get('hash_manifesto')
    sessoes = m.get('sessoes')
    if not isinstance(sessoes, list) or len(sessoes) > 100_000:
        raise RegraDeNegocio('Lista de sessões do manifesto inválida.')
    proprias = {str(s) for s in _sessoes(db, empresa_id)}
    resultado = []
    for e in sessoes:
        sid, n, h = (e.get('sessao_id'), e.get('leituras'), e.get('hash_final')) if isinstance(e, dict) else (None,) * 3
        if sid not in proprias or not isinstance(n, int) or n < 1:
            resultado.append({'sessao_id': sid, 'situacao': 'sessao_ausente'})
            continue
        sid_uuid = UUID(sid)
        atual = db.scalar(select(LeituraEtiqueta.hash_encadeado).where(
            LeituraEtiqueta.sessao_id == sid_uuid, LeituraEtiqueta.ordem_recebimento == n))
        agora_total = db.scalar(select(func.count()).where(LeituraEtiqueta.sessao_id == sid_uuid))
        cadeia = ingestao.verificar_integridade(db, empresa_id, sid_uuid)
        if atual is None or atual.hex() != h:
            situacao = 'divergente'
        elif not cadeia['integra']:
            situacao = 'cadeia_divergente_depois'
        else:
            situacao = 'confere'
        resultado.append({'sessao_id': sid, 'situacao': situacao, 'leituras_no_manifesto': n,
                          'leituras_agora': agora_total})
    problemas = [r for r in resultado if r['situacao'] != 'confere']
    return {'versao': VERSAO, 'hash_manifesto': m.get('hash_manifesto'), 'emitido_em': m.get('emitido_em'),
            'arquivo_intacto': arquivo_intacto,
            'conferencia': 'confere' if arquivo_intacto and not problemas else 'divergente',
            'sessoes_conferidas': len(resultado), 'sessoes_com_problema': len(problemas),
            'problemas': problemas[:200],
            'limites': ['Comprova que as evidências ancoradas não mudaram desde a emissão; não certifica '
                        'temperatura, sensor nem leituras recebidas depois do manifesto.']}
