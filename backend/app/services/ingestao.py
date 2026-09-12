"""Recepção transacional. A ordem da cadeia é a de chegada, nunca a do relógio do celular."""
from datetime import timezone
from decimal import Decimal
from uuid import uuid4
import hashlib
from sqlalchemy import select, text
from app.models import (Dispositivo, Etiqueta, Volume, VinculoEtiqueta, SessaoMonitoramento, LeituraEtiqueta)
from app.models.enums import EstadoEtiqueta, StatusRemessa, StatusVolume, StatusSessao, TipoLeitura, BaseDeTempo, Plataforma
from app.core.errors import NaoEncontrado, Conflito, RegraDeNegocio
from app.core.security import agora
from app.services.cargas import remessa_por_id
from app.services.idempotencia import hash_json
from app.services.auditoria import auditar

DOMINIO = b'ThermoTrace/evidencia/v1\x00'


def iso(d): return d.astimezone(timezone.utc).isoformat()


def aparelho(db, empresa, id_):
    d=db.scalar(select(Dispositivo).where(Dispositivo.id==id_, Dispositivo.empresa_id==empresa, Dispositivo.ativo.is_(True)))
    if not d: raise NaoEncontrado('Aparelho não registrado ou indisponível nesta empresa.')
    return d


def registrar_aparelho(db, acesso, body):
    chave=f'aparelho:{acesso.empresa.id}:{body.install_id}'
    db.execute(text('SELECT pg_advisory_xact_lock(hashtextextended(:c,0))'),{'c':chave})
    d=db.scalar(select(Dispositivo).where(Dispositivo.empresa_id==acesso.empresa.id,Dispositivo.install_id==str(body.install_id)))
    if d:
        if not d.ativo or (d.modelo,d.versao_so,d.versao_app)!=(body.modelo,body.versao_so,body.versao_app):
            raise Conflito('Instalação já registrada com outra configuração ou desativada.')
    else:
        d=Dispositivo(empresa_id=acesso.empresa.id,install_id=str(body.install_id),registrado_por=acesso.usuario.id,
                      modelo=body.modelo,versao_so=body.versao_so,versao_app=body.versao_app,primeiro_acesso_em=agora())
        db.add(d);db.flush()
        auditar(db,'aparelho_registrado','dispositivo',d.id,acesso.usuario)
    return {'id':d.id,'empresa_id':d.empresa_id,'install_id':d.install_id,'ativo':d.ativo,
            'identificacao':'declarada_pelo_aplicativo_sem_atestacao'}


def sessao_por_id(db, empresa, id_, escrita=False):
    s=db.scalar(select(SessaoMonitoramento).join(VinculoEtiqueta,VinculoEtiqueta.id==SessaoMonitoramento.vinculo_id)
        .join(Volume,Volume.id==VinculoEtiqueta.volume_id)
        .where(SessaoMonitoramento.id==id_,SessaoMonitoramento.contrato_ingestao=='1'))
    if not s: raise NaoEncontrado()
    # Filtro explícito além do RLS; mesma ordem de locks da abertura e cancelamento.
    r=remessa_por_id(db,empresa,s.remessa_id,escrita=escrita)
    v=db.scalar(select(Volume).join(VinculoEtiqueta,VinculoEtiqueta.volume_id==Volume.id)
                .where(VinculoEtiqueta.id==s.vinculo_id))
    if escrita:
        db.execute(select(Volume.id).where(Volume.id==v.id).with_for_update())
        s=db.scalar(select(SessaoMonitoramento).where(SessaoMonitoramento.id==id_).with_for_update().execution_options(populate_existing=True))
    return s,r,v


def recibo(l):
    e=l.envelope_integridade
    return {'leitura_id':l.id,'sessao_id':l.sessao_id,'evento_id':l.evento_id,'empresa_id':e['empresa_id'],
            'volume_id':e['volume_id'],'remessa_id':e['remessa_id'],'tipo':l.tipo_leitura,
            'ordem_recebimento':l.ordem_recebimento,'recebida_em_servidor':l.recebida_em_servidor,
            'hash_payload':l.hash_payload.hex(),'hash_anterior':l.hash_anterior.hex() if l.hash_anterior else None,
            'hash_encadeado':l.hash_encadeado.hex(),'origem':e['origem']}


def replay_evento(db, acesso, sid, evento, body):
    l=db.scalar(select(LeituraEtiqueta).where(LeituraEtiqueta.sessao_id==sid,LeituraEtiqueta.evento_id==evento))
    if l:
        if l.lida_por!=acesso.usuario.id or l.envelope_integridade['requisicao_sha256']!=hash_json(body.model_dump(mode='json')):
            raise Conflito('Identificador de evento já recebido com outro conteúdo ou operador.')
        return recibo(l)


def gravar(db, acesso, s, v, tipo, evidencia, body):
    anterior=db.scalar(select(LeituraEtiqueta).where(LeituraEtiqueta.sessao_id==s.id)
                       .order_by(LeituraEtiqueta.ordem_recebimento.desc()).limit(1))
    ordem=anterior.ordem_recebimento+1 if anterior else 1
    recebido=agora(); pedido=body.model_dump(mode='json')
    envelope={'contrato':'1','algoritmo':'tt-evidencia-1','empresa_id':str(acesso.empresa.id),
        'usuario_id':str(acesso.usuario.id),'sessao_id':str(s.id),'volume_id':str(v.id),'remessa_id':str(s.remessa_id),
        'etiqueta_id':str(s.etiqueta_id),'dispositivo_id':str(evidencia.dispositivo_id),'evento_id':str(evidencia.evento_id),
        'tipo':tipo,'ordem':ordem,'recebida_em':iso(recebido),'lida_em':iso(evidencia.lida_em),
        'origem':evidencia.origem,'requisicao_sha256':hash_json(pedido),'requisicao':pedido}
    hp=bytes.fromhex(hash_json(envelope)); ha=anterior.hash_encadeado if anterior else None
    hc=hashlib.sha256(DOMINIO+(ha or b'')+hp).digest()
    l=LeituraEtiqueta(id=uuid4(),sessao_id=s.id,etiqueta_id=s.etiqueta_id,contrato_ingestao='1',
        evento_id=evidencia.evento_id,ordem_recebimento=ordem,envelope_integridade=envelope,
        chave_idempotencia=f'v1:{s.id}:{evidencia.evento_id}',tipo_leitura=TipoLeitura(tipo),
        dispositivo_id=evidencia.dispositivo_id,lida_por=acesso.usuario.id,
        lida_em_dispositivo=evidencia.lida_em,recebida_em_servidor=recebido,
        resposta_bruta=evidencia.resposta_bruta,versao_sdk=evidencia.versao_sdk,versao_decodificador=evidencia.versao_decodificador,
        hash_payload=hp,hash_anterior=ha,hash_encadeado=hc,horarios_corrigidos=False)
    db.add(l);db.flush()
    auditar(db,'evidencia_recebida','leitura_etiqueta',l.id,acesso.usuario,
        depois={'sessao_id':str(s.id),'evento_id':str(evidencia.evento_id),'tipo':tipo,'origem':evidencia.origem,
                'ordem':ordem,'hash_encadeado':hc.hex()})
    return recibo(l)


def iniciar(db, acesso, volume_id, body):
    v=db.scalar(select(Volume).where(Volume.id==volume_id))
    if not v: raise NaoEncontrado()
    r=remessa_por_id(db,acesso.empresa.id,v.remessa_id,escrita=True)
    db.execute(select(Volume.id).where(Volume.id==v.id).with_for_update())
    e=db.scalar(select(Etiqueta).where(Etiqueta.id==body.etiqueta_id,Etiqueta.empresa_id==acesso.empresa.id).with_for_update())
    if not e: raise NaoEncontrado('Etiqueta não disponível no catálogo desta empresa.')
    db.execute(text('SELECT pg_advisory_xact_lock(hashtextextended(:c,0))'),{'c':'sessao:'+str(body.sessao_id)})
    existente=db.scalar(select(SessaoMonitoramento).where(SessaoMonitoramento.id==body.sessao_id))
    if existente:
        ve=db.get(VinculoEtiqueta,existente.vinculo_id)
        if existente.remessa_id==r.id and ve.volume_id==v.id and existente.contrato_ingestao=='1':
            repetido=replay_evento(db,acesso,existente.id,body.evidencia.evento_id,body)
            if repetido:return repetido
        raise Conflito('Sessão já registrada. Não substituir a ativação anterior.')
    if r.status in (StatusRemessa.CANCELADA,StatusRemessa.CONCLUIDA): raise RegraDeNegocio('Carga fechada ou cancelada.')
    if not r.criterio_snapshot: raise RegraDeNegocio('Carga legada sem critério preservado: revisar antes de iniciar.')
    f=r.criterio_snapshot['perfil']
    if (body.intervalo_segundos!=r.intervalo_segundos or body.min_configurado_c!=Decimal(f['min_c'])
        or body.max_configurado_c!=Decimal(f['max_c'])):
        raise Conflito('A configuração declarada difere do critério preservado na carga.')
    aparelho(db,acesso.empresa.id,body.evidencia.dispositivo_id)
    if e.nfc_uid!=body.evidencia.uid_canonico: raise Conflito('UID declarado não confere com a etiqueta cadastrada.')
    if e.estado in (EstadoEtiqueta.APOSENTADA,EstadoEtiqueta.MANUTENCAO): raise RegraDeNegocio('Etiqueta indisponível.')
    if db.scalar(select(VinculoEtiqueta.id).where(VinculoEtiqueta.liberada_em.is_(None),
                            (VinculoEtiqueta.etiqueta_id==e.id)|(VinculoEtiqueta.volume_id==v.id))):
        raise Conflito('Etiqueta ou volume ainda possui vínculo ativo.')
    ve=VinculoEtiqueta(volume_id=v.id,etiqueta_id=e.id,vinculada_por=acesso.usuario.id,
                       dispositivo_id=body.evidencia.dispositivo_id,qr_escaneado=False,uid_conferiu=True)
    db.add(ve);db.flush()
    s=SessaoMonitoramento(id=body.sessao_id,contrato_ingestao='1',etiqueta_id=e.id,vinculo_id=ve.id,remessa_id=r.id,
        perfil_termico_id=r.perfil_termico_id,epoch_inicio_etiqueta=body.epoch_inicio_etiqueta,
        inicio_dispositivo_em=body.evidencia.lida_em,inicio_servidor_em=agora(),base_de_tempo=BaseDeTempo.INSTANTE_START,
        plataforma_ativacao=Plataforma.ANDROID,delay_minutos=body.delay_minutos,intervalo_segundos=body.intervalo_segundos,
        quantidade_planejada=body.quantidade_planejada,min_configurado_c=body.min_configurado_c,max_configurado_c=body.max_configurado_c,
        ativacao_confirmada=True,ativacao_confirmada_em=body.evidencia.lida_em,ativada_por=acesso.usuario.id,
        dispositivo_id=body.evidencia.dispositivo_id)
    db.add(s);db.flush()
    v.monitorado=True;v.status=StatusVolume.MONITORANDO
    e.estado=EstadoEtiqueta.MONITORANDO;e.ciclos_ativacao+=1
    resultado=gravar(db,acesso,s,v,'ativacao',body.evidencia,body)
    auditar(db,'sessao_declarada_iniciada','sessao_monitoramento',s.id,acesso.usuario,
        depois={'volume_id':str(v.id),'origem':body.evidencia.origem,'confirmacao':'declarada_pelo_aparelho'})
    db.flush();return resultado


def receber(db, acesso, sid, body):
    s,r,v=sessao_por_id(db,acesso.empresa.id,sid,escrita=True)
    repetido=replay_evento(db,acesso,s.id,body.evento_id,body)
    if repetido:return repetido
    aparelho(db,acesso.empresa.id,body.dispositivo_id)
    e=db.get(Etiqueta,s.etiqueta_id)
    if body.uid_canonico!=e.nfc_uid or body.epoch_inicio_etiqueta!=s.epoch_inicio_etiqueta or body.intervalo_relatado_s!=s.intervalo_segundos:
        raise Conflito('Identidade ou configuração declarada não pertence a esta sessão.')
    inicial=db.scalar(select(LeituraEtiqueta).where(LeituraEtiqueta.sessao_id==sid,LeituraEtiqueta.ordem_recebimento==1))
    if body.origem!=inicial.envelope_integridade['origem']: raise Conflito('Simulação e declaração de campo não podem se misturar numa sessão.')
    if body.lida_em < s.inicio_dispositivo_em: raise Conflito('Instante declarado anterior à ativação; revisar relógio ou sessão.')
    final=db.scalar(select(LeituraEtiqueta).where(LeituraEtiqueta.sessao_id==sid,LeituraEtiqueta.tipo_leitura==TipoLeitura.FINAL))
    if final and (body.tipo=='final' or body.lida_em>final.lida_em_dispositivo):
        raise Conflito('Sessão já encerrada; apenas checkpoints anteriores ao fechamento podem chegar atrasados.')
    if body.tipo=='final':
        mais_recente=db.scalar(select(LeituraEtiqueta.lida_em_dispositivo).where(LeituraEtiqueta.sessao_id==sid)
            .order_by(LeituraEtiqueta.lida_em_dispositivo.desc()).limit(1))
        if body.lida_em<mais_recente: raise Conflito('Fechamento anterior a uma leitura já recebida; revisar relógio.')
        s.status=StatusSessao.ENCERRADA_NORMAL;s.encerrada_em=body.lida_em;v.status=StatusVolume.ENCERRADO
        # Não liberar vínculo nem inventar STOP físico após recebimento de leitura final.
    resultado=gravar(db,acesso,s,v,body.tipo,body,body)
    db.flush();return resultado


def verificar_integridade(db, empresa, sid):
    s,r,v=sessao_por_id(db,empresa,sid)
    anterior=None;quantidade=0;problemas=[]
    rows=db.scalars(select(LeituraEtiqueta).where(LeituraEtiqueta.sessao_id==sid)
                    .order_by(LeituraEtiqueta.ordem_recebimento).execution_options(yield_per=50))
    for l in rows:
        quantidade+=1;e=l.envelope_integridade
        try:
            hp=bytes.fromhex(hash_json(e));pedido=e['requisicao'];ev=pedido.get('evidencia',pedido)
            esperado={'empresa_id':str(empresa),'sessao_id':str(sid),'remessa_id':str(r.id),'volume_id':str(v.id),
                'usuario_id':str(l.lida_por),'etiqueta_id':str(l.etiqueta_id),'dispositivo_id':str(l.dispositivo_id),
                'evento_id':str(l.evento_id),'ordem':l.ordem_recebimento,'tipo':l.tipo_leitura.value,
                'lida_em':iso(l.lida_em_dispositivo),'recebida_em':iso(l.recebida_em_servidor)}
            valido=(all(e[k]==value for k,value in esperado.items()) and l.ordem_recebimento==quantidade
                and e['requisicao_sha256']==hash_json(pedido) and ev['resposta_bruta']==l.resposta_bruta
                and ev['versao_sdk']==l.versao_sdk and ev['versao_decodificador']==l.versao_decodificador
                and hp==l.hash_payload and l.hash_anterior==anterior
                and l.hash_encadeado==hashlib.sha256(DOMINIO+(anterior or b'')+hp).digest())
        except (TypeError,KeyError,ValueError): valido=False
        if not valido and len(problemas)<100: problemas.append(str(l.id))
        anterior=l.hash_encadeado
    return {'sessao_id':sid,'integra':not problemas and quantidade>0,'quantidade':quantidade,
        'leituras_com_problema':problemas,'hash_final':anterior.hex() if anterior else None,
        'escopo':'cadeia_local_sem_ancora_externa','validacao_fisica':False}
