import copy
from datetime import datetime, timezone
from uuid import UUID
import pytest
from sqlalchemy import select
from app.services.temperaturas import projetar, exportar
from app.models import LeituraEtiqueta, LogAuditoria
from tests.test_acesso_perfis import ambiente, headers
from tests.test_ingestao import preparar_ingestao, iniciar, enviar, leitura


def bruto(pontos=('4','5','9')):
    return ['1','1789214400','648',str(len(pontos)),'0','600','4','9','2','8','0','1',*pontos]


def projeto(b):
    return projetar(b,epoch=1789214400,delay=0,intervalo=600,minimo=2,maximo=8,
        lida_em=datetime(2026,9,12,13,tzinfo=timezone.utc))


def test_projecao_nominal_preserva_temperatura_e_indicador():
    r=projeto(bruto(('4:0','5:1','9')))
    assert r['conferencia']=='coerente' and r['resumo']['acima']==1
    assert r['pontos'][1]['indicador']==1
    assert r['pontos'][2]['instante']=='2026-09-12T12:20:00+00:00'


@pytest.mark.parametrize('indice,valor',[(3,'99'),(1,'1789214401'),(5,'601'),(4,'2'),(12,'NaN'),(12,'5:abc'),(12,'5:1:2'),(0,'99')])
def test_nao_inventa_serie_ou_horarios(indice,valor):
    b=bruto(); b[indice]=valor
    with pytest.raises(ValueError):projeto(b)


def test_divergencia_de_menos_29_8_permanece_visivel():
    b=bruto(('-29.8','5','9')); r=projeto(b)
    assert r['conferencia']=='divergente' and r['pontos'][0]['temperatura_c']==-29.8


def test_cabecalho_sem_extremos_nao_ganha_aprovacao():
    b=bruto(); b[6]='NaN'; r=projeto(b)
    assert r['conferencia']=='nao_avaliavel' and len(r['pontos'])==3


def preparar_grafico(ambiente,pontos=('4','5','9')):
    d=preparar_ingestao(ambiente); assert iniciar(d).status_code==201
    b=leitura(d[-1],minutos=30); b['resposta_bruta']=bruto(pontos); b['versao_decodificador']='fm13dt160-decoder-1.1'
    b['versao_sdk']='fmsh-nfcinstruct-sintetico'
    r=enviar(d,b);assert r.status_code==201,r.text
    path=f"/api/v1/sessoes/{d[-1]['sessao_id']}/leituras/{r.json()['leitura_id']}/temperaturas"
    return d,path,r.json()


def test_grafico_exportacoes_auditoria_e_isolamento(ambiente):
    d,path,recibo=preparar_grafico(ambiente)
    c,db,users,h,*_=d
    a=c.get(path,headers=h);assert a.status_code==200,a.text
    assert a.headers['cache-control']=='no-store'
    data=a.json();assert data['resumo']['quantidade']==3 and data['conferencia']=='coerente'
    assert c.get(path,headers=h).json()==data # mesma evidência, projeção determinística
    for formato,mime in [('csv','text/csv'),('svg','image/svg+xml'),('html','text/html')]:
        out=c.get(path+'?formato='+formato,headers=h)
        assert out.status_code==200 and mime in out.headers['content-type']
        assert 'attachment' in out.headers['content-disposition']
        assert c.get(path+'?formato='+formato,headers=headers(ambiente[3]('b'))).status_code==404
        assert c.get(path+'?formato='+formato,headers=headers(ambiente[3]('staff'))).status_code==403
        assert c.get(path+'?formato='+formato).status_code==401
    logs=list(db.scalars(select(LogAuditoria).where(LogAuditoria.acao=='temperaturas_exportadas',LogAuditoria.entidade_id==UUID(recibo['leitura_id']))))
    assert len(logs)==3
    assert db.get(LeituraEtiqueta,UUID(recibo['leitura_id'])).resposta_bruta==bruto()


def test_648_medicoes_chegam_sem_truncamento(ambiente):
    d,path,_=preparar_grafico(ambiente,tuple('5' for _ in range(648)))
    r=d[0].get(path,headers=d[3]); assert r.status_code==200
    assert len(r.json()['pontos'])==648


def test_exportacao_escapa_texto_e_preserva_avisos(ambiente):
    d,path,_=preparar_grafico(ambiente)
    data=d[0].get(path,headers=d[3]).json()
    data['codigo_carga']='<script>alert(1)</script>'
    html,mime=exportar(data,'html')
    assert '<script>' not in html and '&lt;script&gt;' in html
    assert data['hash_projecao'] in html


def test_ativacao_e_versao_desconhecida_sem_grafico(ambiente):
    d=preparar_ingestao(ambiente); a=iniciar(d).json()
    path=f"/api/v1/sessoes/{d[-1]['sessao_id']}/leituras/{a['leitura_id']}/temperaturas"
    assert d[0].get(path,headers=d[3]).json()['pontos']==[]
    b=enviar(d,leitura(d[-1])).json()
    path=path.replace(a['leitura_id'],b['leitura_id'])
    assert d[0].get(path,headers=d[3]).json()['pontos']==[]


def test_integridade_divergente_bloqueia_serie_e_suporta_envelope_incompleto(ambiente):
    from app.services.temperaturas import resultado
    from app.models import SessaoMonitoramento, Remessa
    d,path,recibo=preparar_grafico(ambiente)
    db=d[1]
    l=db.get(LeituraEtiqueta,UUID(recibo['leitura_id']))
    s=db.get(SessaoMonitoramento,l.sessao_id)
    r=db.get(Remessa,s.remessa_id)
    with db.no_autoflush:
        l.envelope_integridade={}
        data=resultado(s,l,r,False)
        assert data['pontos']==[] and data['resumo'] is None
        assert data['integridade_cadeia'] is False and data['origem']=='indisponivel'
        assert 'bloqueado' in data['avisos'][0]
        db.expire(l)
