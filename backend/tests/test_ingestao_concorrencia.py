from concurrent.futures import ThreadPoolExecutor
from threading import Barrier
from types import SimpleNamespace
from datetime import datetime,timezone,timedelta
from uuid import uuid4
import json
import pytest
from sqlalchemy import select
from app.db.session import SessaoLocal
from app.models import Empresa,Usuario,PerfilTermico,Etiqueta,LoteEtiqueta,Dispositivo,LeituraEtiqueta
from app.models.enums import TipoEmpresa,PapelUsuario
from app.core.security import hasher,agora
from app.core.errors import Conflito
from app.schemas.cargas import ConfigurarProduto,NovaRemessa
from app.schemas.ingestao import IniciarSessao,ReceberLeitura,ReciboLeitura
from app.services import cargas,ingestao,idempotencia
from tests.test_acesso_perfis import PERFIL


@pytest.mark.parametrize('cenario',['eventos_distintos','mesmo_evento','conteudo_divergente','dois_finais'])
def test_duas_conexoes_serializam_cadeia_e_reenvio(cenario):
    instante=datetime(2026,9,12,12,tzinfo=timezone.utc)
    with SessaoLocal.begin() as db:
        e=Empresa(razao_social='Concorrência ingestão sintética',cnpj=str(uuid4().int)[:14],tipo=TipoEmpresa.EMBARCADOR)
        db.add(e);db.flush()
        u=Usuario(empresa_id=e.id,nome='Teste',email=uuid4().hex+'@example.org',
                  senha_hash=hasher.hash('Senha-somente-teste-42'),papel=PapelUsuario.GESTOR)
        db.add(u);db.flush();acesso=SimpleNamespace(usuario=u,empresa=e)
        f=PerfilTermico(empresa_id=e.id,codigo='TESTE',versao=1,rotulo='Sintético',min_c=2,max_c=8,evidencia=PERFIL['evidencia'],ativo=False)
        db.add(f);db.flush();f.aprovado_em=agora();f.aprovado_por=u.id;f.ativo=True;db.flush()
        p=cargas.configurar_produto(db,acesso,ConfigurarProduto(codigo='SKU',perfil_termico_id=f.id))
        r=cargas.criar_remessa(db,acesso,NovaRemessa(codigo='CARGA',produto_configuracao_id=p['id'],destinatario_nome='Teste'))
        lote=LoteEtiqueta(fornecedor='Sintético',modelo_hardware='SIMULACAO',quantidade=1)
        db.add(lote);db.flush()
        tag=Etiqueta(empresa_id=e.id,lote_id=lote.id,serial=uuid4().hex,qr_payload=uuid4().hex,nfc_uid=uuid4().hex[:16].upper())
        dev=Dispositivo(empresa_id=e.id,registrado_por=u.id,install_id=str(uuid4()))
        db.add_all([tag,dev]);db.flush()
        sid=uuid4()
        inicio=IniciarSessao(sessao_id=sid,etiqueta_id=tag.id,epoch_inicio_etiqueta=int(instante.timestamp()),
            intervalo_segundos=600,quantidade_planejada=100,min_configurado_c='2.00',max_configurado_c='8.00',ativacao_confirmada=True,
            evidencia={'evento_id':uuid4(),'dispositivo_id':dev.id,'lida_em':instante,'uid_canonico':tag.nfc_uid,
                       'versao_sdk':'teste','versao_decodificador':'teste','resposta_bruta':['SIMULADO'],'origem':'simulacao'})
        ingestao.iniciar(db,acesso,r['volumes'][0]['id'],inicio)
        eid,uid=e.id,u.id
        comum={**inicio.evidencia.model_dump(),'evento_id':uuid4(),'tipo':'final' if cenario=='dois_finais' else 'checkpoint',
               'lida_em':instante+timedelta(minutes=10),'epoch_inicio_etiqueta':inicio.epoch_inicio_etiqueta,'intervalo_relatado_s':600}
    barreira=Barrier(2)
    def enviar(i):
        dados=dict(comum)
        if cenario in ('eventos_distintos','dois_finais'):dados['evento_id']=uuid4()
        if cenario=='conteudo_divergente':dados['resposta_bruta']=[f'SIMULADO {i}']
        body=ReceberLeitura(**dados)
        with SessaoLocal() as db:
            acesso=SimpleNamespace(usuario=db.get(Usuario,uid),empresa=db.get(Empresa,eid))
            barreira.wait(timeout=10)
            try:
                r=idempotencia.executar(db,acesso,str(uuid4()),f'/sessoes/{sid}/leituras',body,
                    lambda:ingestao.receber(db,acesso,sid,body),modelo_saida=ReciboLeitura)
                return r.status_code,json.loads(r.body)
            except Conflito:
                db.rollback();return 409,None
    with ThreadPoolExecutor(max_workers=2) as pool:
        fs=[pool.submit(enviar,i) for i in range(2)];resultados=[f.result(timeout=40) for f in fs]
    assert sorted(x[0] for x in resultados)==([201,409] if cenario in ('conteudo_divergente','dois_finais') else [201,201])
    if cenario=='mesmo_evento':assert resultados[0][1]==resultados[1][1]
    with SessaoLocal() as db:
        v=ingestao.verificar_integridade(db,eid,sid)
        assert v['integra'] and v['quantidade']==(3 if cenario=='eventos_distintos' else 2)
