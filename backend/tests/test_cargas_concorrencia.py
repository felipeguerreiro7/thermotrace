from concurrent.futures import ThreadPoolExecutor
from threading import Barrier
from types import SimpleNamespace
from uuid import uuid4
import json
import pytest
from sqlalchemy import select, func

from app.db.session import SessaoLocal
from app.models import Empresa,Usuario,PerfilTermico,Remessa,ChaveIdempotencia,LogAuditoria
from app.models.enums import TipoEmpresa,PapelUsuario
from app.schemas.cargas import ConfigurarProduto,NovaRemessa
from app.core.security import agora,hasher
from app.core.errors import Conflito
from app.services import cargas,idempotencia
from tests.test_acesso_perfis import PERFIL


@pytest.mark.parametrize('corpos_diferentes',[False,True])
def test_criacao_concorrente_com_mesma_chave(corpos_diferentes):
    with SessaoLocal.begin() as db:
        e=Empresa(razao_social='Concorrência de carga sintética',cnpj=str(uuid4().int)[:14],tipo=TipoEmpresa.EMBARCADOR)
        db.add(e);db.flush()
        u=Usuario(empresa_id=e.id,nome='Teste',email=f'{uuid4().hex}@example.org',
                  senha_hash=hasher.hash('Senha-somente-teste-42'),papel=PapelUsuario.GESTOR)
        db.add(u);db.flush()
        f=PerfilTermico(empresa_id=e.id,codigo='TESTE',versao=1,rotulo='Sintético',min_c=2,max_c=8,
                        evidencia=PERFIL['evidencia'],ativo=False)
        db.add(f);db.flush();f.aprovado_em=agora();f.aprovado_por=u.id;f.ativo=True;db.flush()
        p=cargas.configurar_produto(db,SimpleNamespace(usuario=u,empresa=e),
                                   ConfigurarProduto(codigo='SKU',perfil_termico_id=f.id))
        eid,uid,pid=e.id,u.id,p['id']
    chave=str(uuid4());barrier=Barrier(2)
    def executar(numero):
        with SessaoLocal() as db:
            acesso=SimpleNamespace(usuario=db.get(Usuario,uid),empresa=db.get(Empresa,eid))
            body=NovaRemessa(codigo='CARGA',produto_configuracao_id=pid,
                             destinatario_nome=f'Destino {numero if corpos_diferentes else 0}',
                             documentos=[{'tipo':'pedido','numero':'PEDIDO'}])
            barrier.wait(timeout=10)
            try:
                r=idempotencia.executar(db,acesso,chave,'/remessas',body,lambda:cargas.criar_remessa(db,acesso,body))
                return r.status_code,json.loads(r.body)
            except Conflito:
                db.rollback();return 409,None
    with ThreadPoolExecutor(max_workers=2) as pool:
        futures=[pool.submit(executar,i) for i in range(2)]
        resultados=[f.result(timeout=15) for f in futures]
    assert sorted(x[0] for x in resultados)==([201,409] if corpos_diferentes else [201,201])
    if not corpos_diferentes:assert resultados[0][1]==resultados[1][1]
    with SessaoLocal() as db:
        assert db.scalar(select(func.count()).select_from(Remessa).where(Remessa.empresa_embarcador_id==eid))==1
        assert db.scalar(select(func.count()).select_from(ChaveIdempotencia).where(ChaveIdempotencia.empresa_id==eid))==1
        assert db.scalar(select(func.count()).select_from(LogAuditoria).where(LogAuditoria.empresa_id==eid,LogAuditoria.acao=='remessa_criada'))==1
