from uuid import uuid4
import pytest
from sqlalchemy import text
from sqlalchemy.exc import DBAPIError
from tests.test_acesso_perfis import ambiente, headers
from tests.test_cargas import preparar, remessa, post, payload


def test_rls_cargas_filhos_produtos_recibos_e_vinculos(ambiente):
    c,db,users,entrar=ambiente
    a,b=headers(entrar()),headers(entrar('b'))
    fa,pa=preparar(c,a);fb,pb=preparar(c,b)
    ra=remessa(c,a,pa);rb=remessa(c,b,pb)
    role='tt_carga_'+uuid4().hex
    db.execute(text(f'CREATE ROLE {role} NOLOGIN NOSUPERUSER NOBYPASSRLS'))
    db.execute(text(f'GRANT SELECT,INSERT,UPDATE ON ALL TABLES IN SCHEMA public TO {role}'))
    db.execute(text(f'SET LOCAL ROLE {role}'))
    db.execute(text("SELECT set_config('app.empresa_id',:e,true),set_config('app.usuario_id',:u,true)"),
               {'e':str(users['a'].empresa_id),'u':str(users['a'].id)})
    assert [str(v) for v in db.scalars(text('SELECT id FROM remessa'))]==[ra['id']]
    assert [str(v) for v in db.scalars(text('SELECT id FROM produto_configuracao'))]==[pa['id']]
    assert [str(v) for v in db.scalars(text('SELECT id FROM documento'))]==[ra['documentos'][0]['id']]
    assert len(db.execute(text('SELECT id FROM volume')).all())==2
    assert {str(v) for v in db.scalars(text('SELECT usuario_id FROM chave_idempotencia'))}=={str(users['a'].id)}
    with pytest.raises(DBAPIError,match='row-level security'):
        with db.begin_nested():
            db.execute(text("INSERT INTO documento (remessa_id,tipo,numero) VALUES (:id,'pedido','INVASAO')"),{'id':rb['id']})
    with pytest.raises(DBAPIError,match='row-level security'):
        with db.begin_nested():
            db.execute(text("INSERT INTO produto_configuracao (empresa_id,codigo,versao,perfil_termico_id,intervalo_segundos,criado_por) VALUES (:emp,'FORJADO',1,:perfil,600,:u)"),
                       {'emp':users['a'].empresa_id,'perfil':fb['id'],'u':users['a'].id})
    with pytest.raises(DBAPIError,match='row-level security'):
        with db.begin_nested():
            db.execute(text("INSERT INTO remessa (empresa_embarcador_id,codigo,destinatario_nome,perfil_termico_id,produto_configuracao_id) VALUES (:emp,'FORJADA','Teste',:perfil,:produto)"),
                       {'emp':users['a'].empresa_id,'perfil':fa['id'],'produto':pb['id']})
    db.execute(text("SELECT set_config('app.empresa_id','',true),set_config('app.usuario_id','',true)"))
    for table in ['remessa','volume','documento','produto_configuracao','chave_idempotencia']:
        assert db.execute(text(f'SELECT id FROM {table}')).all()==[]
    db.execute(text('RESET ROLE'))


def test_http_carga_idempotente_funciona_sob_rls(ambiente):
    c,db,users,entrar=ambiente
    role='tt_carga_runtime_'+uuid4().hex
    db.execute(text(f'CREATE ROLE {role} NOLOGIN NOSUPERUSER NOBYPASSRLS'))
    db.execute(text(f'GRANT SELECT,INSERT,UPDATE ON ALL TABLES IN SCHEMA public TO {role}'))
    db.execute(text(f'GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO {role}'))
    db.execute(text(f'SET LOCAL ROLE {role}'))
    h=headers(entrar());_,p=preparar(c,h)
    key=str(uuid4()); a=post(c,'/remessas',h,payload(p),key)
    b=post(c,'/remessas',h,payload(p),key)
    assert a.status_code==b.status_code==201,a.text
    assert a.json()==b.json()
    outro=headers(entrar('b'))
    assert c.get('/api/v1/remessas',headers=outro).json()==[]
    assert post(c,'/remessas',outro,payload(p),key).status_code==404
    db.execute(text('RESET ROLE'))
