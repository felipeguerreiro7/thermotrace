from uuid import UUID
from fastapi import APIRouter, Depends, Query
from sqlalchemy import select, and_
from sqlalchemy.orm import Session
from app.api.deps import Acesso, operador
from app.api.v1.produtos import Chave
from app.db.session import obter_sessao
from app.models import Etiqueta, LeituraEtiqueta, Volume, VinculoEtiqueta, SessaoMonitoramento
from app.core.errors import NaoEncontrado
from app.schemas.ingestao import RegistrarDispositivo, IniciarSessao, ReceberLeitura, ReciboLeitura
from app.services import ingestao, idempotencia
from app.services.auditoria import auditar
from app.services.cargas import remessa_por_id
from typing import Literal
from fastapi import Response
from app.services import temperaturas
import hashlib

router=APIRouter(tags=['Recepção de evidências'])


@router.get('/sessoes/{sessao_id}/leituras/{leitura_id}/temperaturas')
def temperaturas_da_coleta(sessao_id:UUID, leitura_id:UUID,
        formato:Literal['json','csv','svg','html']='json',
        acesso:Acesso=Depends(operador), db:Session=Depends(obter_sessao)):
    s,r,v=ingestao.sessao_por_id(db,acesso.empresa.id,sessao_id)
    l=db.scalar(select(LeituraEtiqueta).where(LeituraEtiqueta.id==leitura_id,
        LeituraEtiqueta.sessao_id==sessao_id,LeituraEtiqueta.contrato_ingestao=='1'))
    if not l: raise NaoEncontrado()
    integra=ingestao.verificar_integridade(db,acesso.empresa.id,sessao_id)['integra']
    dados=temperaturas.resultado(s,l,r,integra)
    evento={'sessao_id':str(sessao_id),'formato':formato,'versao':temperaturas.VERSAO,
            'hash_projecao':dados['hash_projecao'],'conferencia':dados['conferencia']}
    if formato=='json':
        auditar(db,'temperaturas_consultadas','leitura_etiqueta',l.id,acesso.usuario,depois=evento)
        return dados
    conteudo,mime=temperaturas.exportar(dados,formato)
    evento['sha256_arquivo']=hashlib.sha256(conteudo.encode('utf-8')).hexdigest()
    auditar(db,'temperaturas_exportadas','leitura_etiqueta',l.id,acesso.usuario,depois=evento)
    return Response(conteudo,media_type=mime,headers={
        'Content-Disposition':f'attachment; filename="ThermoTrace-{leitura_id}.{formato}"',
        'Cache-Control':'no-store','X-Content-Type-Options':'nosniff',
        'Content-Security-Policy':"default-src 'none'; style-src 'unsafe-inline'; sandbox"})


@router.get('/volumes/{volume_id}/sessoes')
def sessoes_do_volume(volume_id:UUID,limite:int=Query(50,ge=1,le=100),offset:int=Query(0,ge=0),
                      acesso:Acesso=Depends(operador),db:Session=Depends(obter_sessao)):
    v=db.scalar(select(Volume).where(Volume.id==volume_id))
    if not v:raise NaoEncontrado()
    remessa_por_id(db,acesso.empresa.id,v.remessa_id)
    rows=db.execute(select(SessaoMonitoramento,LeituraEtiqueta.envelope_integridade)
        .join(VinculoEtiqueta,VinculoEtiqueta.id==SessaoMonitoramento.vinculo_id)
        .join(LeituraEtiqueta,and_(LeituraEtiqueta.sessao_id==SessaoMonitoramento.id,LeituraEtiqueta.ordem_recebimento==1))
        .where(VinculoEtiqueta.volume_id==v.id,SessaoMonitoramento.contrato_ingestao=='1')
        .order_by(SessaoMonitoramento.criado_em.desc(),SessaoMonitoramento.id).limit(limite).offset(offset))
    resultado=[{'id':s.id,'volume_id':v.id,'remessa_id':s.remessa_id,'empresa_id':acesso.empresa.id,
        'etiqueta_id':s.etiqueta_id,'estado_logico':s.status,'encerrada_em':s.encerrada_em,'origem':e['origem'],
        'epoch_inicio_etiqueta':s.epoch_inicio_etiqueta,'intervalo_segundos':s.intervalo_segundos,
        'min_configurado_c':str(s.min_configurado_c),'max_configurado_c':str(s.max_configurado_c),
        'stop_fisico':'nao_confirmado_pelo_servidor'} for s,e in rows]
    auditar(db,'sessoes_consultadas','volume',v.id,acesso.usuario,depois={'quantidade':len(resultado)})
    return resultado


@router.post('/dispositivos',status_code=201)
def dispositivo(body:RegistrarDispositivo,chave:Chave,acesso:Acesso=Depends(operador),db:Session=Depends(obter_sessao)):
    return idempotencia.executar(db,acesso,chave,'/dispositivos',body,lambda:ingestao.registrar_aparelho(db,acesso,body))


@router.get('/etiquetas/localizar')
def etiqueta(uid: str=Query(pattern=r'^(?:[0-9A-F]{2}){4,16}$'),acesso:Acesso=Depends(operador),db:Session=Depends(obter_sessao)):
    e=db.scalar(select(Etiqueta).where(Etiqueta.empresa_id==acesso.empresa.id,Etiqueta.nfc_uid==uid))
    if not e:raise NaoEncontrado()
    return {'id':e.id,'serial':e.serial,'uid_canonico':e.nfc_uid,'estado':e.estado,'empresa_id':e.empresa_id}


@router.post('/volumes/{volume_id}/sessoes',status_code=201,response_model=ReciboLeitura)
def iniciar(volume_id:UUID,body:IniciarSessao,chave:Chave,acesso:Acesso=Depends(operador),db:Session=Depends(obter_sessao)):
    return idempotencia.executar(db,acesso,chave,f'/volumes/{volume_id}/sessoes',body,
        lambda:ingestao.iniciar(db,acesso,volume_id,body),modelo_saida=ReciboLeitura)


@router.post('/sessoes/{sessao_id}/leituras',status_code=201,response_model=ReciboLeitura)
def leitura(sessao_id:UUID,body:ReceberLeitura,chave:Chave,acesso:Acesso=Depends(operador),db:Session=Depends(obter_sessao)):
    return idempotencia.executar(db,acesso,chave,f'/sessoes/{sessao_id}/leituras',body,
        lambda:ingestao.receber(db,acesso,sessao_id,body),modelo_saida=ReciboLeitura)


@router.get('/sessoes/{sessao_id}/leituras',response_model=list[ReciboLeitura])
def listar(sessao_id:UUID,apos_ordem:int=Query(0,ge=0),limite:int=Query(50,ge=1,le=100),
           acesso:Acesso=Depends(operador),db:Session=Depends(obter_sessao)):
    ingestao.sessao_por_id(db,acesso.empresa.id,sessao_id)
    ls=db.scalars(select(LeituraEtiqueta).where(LeituraEtiqueta.sessao_id==sessao_id,
        LeituraEtiqueta.ordem_recebimento>apos_ordem).order_by(LeituraEtiqueta.ordem_recebimento).limit(limite))
    resultados=[ingestao.recibo(l) for l in ls]
    auditar(db,'leituras_consultadas','sessao_monitoramento',sessao_id,acesso.usuario,
            depois={'apos_ordem':apos_ordem,'quantidade':len(resultados)})
    return resultados


@router.get('/sessoes/{sessao_id}/integridade')
def integridade(sessao_id:UUID,acesso:Acesso=Depends(operador),db:Session=Depends(obter_sessao)):
    resultado=ingestao.verificar_integridade(db,acesso.empresa.id,sessao_id)
    auditar(db,'integridade_verificada','sessao_monitoramento',sessao_id,acesso.usuario,
            depois={'integra':resultado['integra'],'quantidade':resultado['quantidade'],'hash_final':resultado['hash_final']})
    return resultado


@router.get('/sessoes/{sessao_id}/leituras/{leitura_id}')
def evidencia(sessao_id:UUID,leitura_id:UUID,acesso:Acesso=Depends(operador),db:Session=Depends(obter_sessao)):
    ingestao.sessao_por_id(db,acesso.empresa.id,sessao_id)
    l=db.scalar(select(LeituraEtiqueta).where(LeituraEtiqueta.id==leitura_id,LeituraEtiqueta.sessao_id==sessao_id))
    if not l:raise NaoEncontrado()
    auditar(db,'evidencia_consultada','leitura_etiqueta',l.id,acesso.usuario,
            depois={'sessao_id':str(sessao_id),'hash_encadeado':l.hash_encadeado.hex()})
    return {'recibo':ReciboLeitura.model_validate(ingestao.recibo(l)),
            'envelope_integridade':l.envelope_integridade,'resposta_bruta':l.resposta_bruta}
