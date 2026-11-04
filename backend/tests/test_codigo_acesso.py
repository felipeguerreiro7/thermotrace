"""WEB-11: convite e redefinição por código de uso único; nenhuma senha por URL, log ou terceiros."""
from datetime import timedelta
from pathlib import Path
from uuid import uuid4, UUID

from sqlalchemy import select, text, update

from app.core.security import agora
from app.models import ChaveIdempotencia, Empresa, LogAuditoria, TokenRecuperacao, Usuario
from tests.test_acesso_perfis import ambiente, headers, SENHA

NOVA = 'Senha-nova-sintetica-123'


def convidar(c, h, papel='operador', email=None):
    email = email or f'convidado-{uuid4().hex}@example.org'
    r = c.post('/api/v1/usuarios/convites', headers=h, json={'nome': 'Pessoa convidada', 'email': email, 'papel': papel})
    assert r.status_code == 201, r.text
    assert r.headers['cache-control'] == 'no-store'
    return email, r.json()


def ativar(c, email, codigo, senha=NOVA):
    return c.post('/api/v1/auth/ativar', json={'email': email, 'codigo': codigo, 'nova_senha': senha})


def login(c, email, senha=NOVA):
    return c.post('/api/v1/auth/login', json={'email': email, 'senha': senha})


def test_convite_primeiro_acesso_e_uso_unico(ambiente):
    c, db, users, entrar = ambiente
    h = headers(entrar())
    email, r = convidar(c, h)
    codigo = r['acesso']['codigo_acesso']
    assert r['usuario']['acesso'] == 'aguardando_primeiro_acesso' and r['acesso']['finalidade'] == 'convite'
    assert len(codigo) == 14 and codigo.count('-') == 2
    assert login(c, email, SENHA).status_code == 401  # conta nasce sem senha conhecida
    errado = ativar(c, email, 'AAAA-BBBB-CCCC')
    assert errado.status_code == 422 and 'novo código' in errado.json()['mensagem']
    assert ativar(c, 'outra-' + email, codigo).status_code == 422  # código vale só para o e-mail convidado
    assert ativar(c, email, codigo.lower().replace('-', ' ')).status_code == 204  # digitação tolerante
    assert ativar(c, email, codigo, 'Outra-senha-sintetica-9').status_code == 422  # uso único
    assert login(c, email).status_code == 200
    pessoas = {p['email']: p for p in c.get('/api/v1/usuarios', headers=h).json()}
    assert pessoas[email]['acesso'] == 'ativo' and pessoas[email]['papel'] == 'operador'
    acoes = {l.acao for l in db.scalars(select(LogAuditoria).where(LogAuditoria.entidade_id == UUID(r['usuario']['id'])))}
    assert {'usuario_convidado', 'codigo_acesso_convite_gerado', 'codigo_acesso_convite_usado'} <= acoes


def test_codigo_nunca_fica_em_claro(ambiente):
    c, db, users, entrar = ambiente
    email, r = convidar(c, headers(entrar()))
    codigo = r['acesso']['codigo_acesso']; bruto = codigo.replace('-', '')
    tokens = db.scalars(select(TokenRecuperacao.token_hash).where(TokenRecuperacao.usuario_id == UUID(r['usuario']['id']))).all()
    assert len(tokens) == 1 and bruto not in tokens[0]
    textos = [str(l.depois) for l in db.scalars(select(LogAuditoria))]
    textos += [str(k.resposta_corpo) for k in db.scalars(select(ChaveIdempotencia))]
    assert not any(bruto in t or codigo in t for t in textos)
    assert ativar(c, email, codigo).status_code == 204
    assert not any(NOVA in str(l.depois) for l in db.scalars(select(LogAuditoria)))


def test_redefinicao_invalida_anterior_e_encerra_sessoes(ambiente):
    c, db, users, entrar = ambiente
    h = headers(entrar())
    sessao_op = headers(entrar('op'))
    um = c.post(f"/api/v1/usuarios/{users['op'].id}/codigo-acesso", headers=h)
    dois = c.post(f"/api/v1/usuarios/{users['op'].id}/codigo-acesso", headers=h)
    assert um.status_code == dois.status_code == 201 and dois.json()['finalidade'] == 'redefinicao'
    assert c.get('/api/v1/auth/me', headers=sessao_op).status_code == 200  # gerar não derruba a sessão
    assert ativar(c, users['op'].email, um.json()['codigo_acesso']).status_code == 422  # substituído
    assert ativar(c, users['op'].email, dois.json()['codigo_acesso']).status_code == 204
    assert c.get('/api/v1/auth/me', headers=sessao_op).status_code == 401  # senha nova encerra sessões
    assert login(c, users['op'].email, SENHA).status_code == 401 and login(c, users['op'].email).status_code == 200


def test_quem_pode_gerar_codigo(ambiente):
    c, db, users, entrar = ambiente
    a, b, op = headers(entrar()), headers(entrar('b')), headers(entrar('op'))
    alvo = f"/api/v1/usuarios/{users['op'].id}/codigo-acesso"
    assert c.post(alvo, headers=b).status_code == 404       # outra empresa
    assert c.post(alvo, headers=op).status_code == 403      # operador não gera
    assert c.post(f"/api/v1/usuarios/{users['a'].id}/codigo-acesso", headers=a).status_code == 422  # a si mesmo
    gestor2 = Usuario(empresa_id=users['a'].empresa_id, nome='Gestor 2', email=f'g2-{uuid4().hex}@example.org',
                      senha_hash=users['a'].senha_hash, papel=users['a'].papel, ultimo_login_em=agora())
    db.add(gestor2); db.commit()
    assert c.post(f'/api/v1/usuarios/{gestor2.id}/codigo-acesso', headers=a).status_code == 422  # gestor ativo: suporte
    assert c.post('/api/v1/usuarios/convites', headers=op, json={'nome': 'X', 'email': 'x@example.org'}).status_code == 403
    assert c.post('/api/v1/usuarios/convites', headers=a, json={'nome': 'Adm', 'email': 'adm@example.org', 'papel': 'admin'}).status_code == 422
    assert c.post('/api/v1/usuarios/convites', headers=a, json={'nome': 'Rep', 'email': users['b'].email}).status_code == 409
    sem_login = c.post('/api/v1/usuarios/convites', headers={}, json={'nome': 'X', 'email': 'y@example.org'})
    assert sem_login.status_code == 401


def test_codigo_vencido_e_usuario_desativado(ambiente):
    c, db, users, entrar = ambiente
    h = headers(entrar())
    email, r = convidar(c, h)
    db.execute(update(TokenRecuperacao).where(TokenRecuperacao.usuario_id == UUID(r['usuario']['id']))
               .values(expira_em=agora() - timedelta(minutes=1)))
    db.commit()
    assert ativar(c, email, r['acesso']['codigo_acesso']).status_code == 422
    email2, r2 = convidar(c, h)
    assert c.post(f"/api/v1/usuarios/{r2['usuario']['id']}/desativar", headers=h).status_code == 204
    assert ativar(c, email2, r2['acesso']['codigo_acesso']).status_code == 422
    t = db.scalars(select(TokenRecuperacao).where(TokenRecuperacao.usuario_id == UUID(r2['usuario']['id']))).one()
    assert t.revogado_em is not None and t.usado_em is None


def test_tentativas_limitadas(ambiente):
    c, db, users, entrar = ambiente
    email, r = convidar(c, headers(entrar()))
    codigos = [ativar(c, email, f'ZZZZ-ZZZZ-ZZ{i:02d}').status_code for i in range(11)]
    assert codigos[:10] == [422] * 10 and codigos[10] == 429
    assert ativar(c, email, r['acesso']['codigo_acesso']).status_code == 429  # nem o certo passa durante o bloqueio


def test_staff_cadastra_empresa_com_codigo_e_redefine_gestor(ambiente):
    c, db, users, entrar = ambiente
    staff = headers(entrar('staff'))
    email = f'gestor-{uuid4().hex}@example.org'
    corpo = {'razao_social': 'Transportadora sintética', 'cnpj': '11444777000161', 'tipo': 'transportadora',
             'gestor': {'nome': 'Gestor convidado', 'email': email}}
    r = c.post('/api/v1/plataforma/clientes', headers=staff, json=corpo)
    assert r.status_code == 201, r.text
    assert r.headers['cache-control'] == 'no-store' and r.json()['acesso']['finalidade'] == 'convite'
    assert c.post('/api/v1/plataforma/clientes', headers=staff, json={**corpo, 'gestor': {'nome': 'Outro gestor', 'email': f'g-{uuid4().hex}@example.org'}}).status_code == 409
    assert ativar(c, email, r.json()['acesso']['codigo_acesso']).status_code == 204
    gestor_h = headers(login(c, email).json())
    assert c.get('/api/v1/auth/me', headers=gestor_h).json()['tipo_empresa'] == 'transportadora'
    empresa_id = r.json()['id']; gestor_id = r.json()['gestor']['id']
    lista = {e['id']: e for e in c.get('/api/v1/plataforma/clientes?limite=100', headers=staff).json()}
    assert [g['email'] for g in lista[empresa_id]['gestores']] == [email] and lista[empresa_id]['tipo'] == 'transportadora'
    novo = c.post(f'/api/v1/plataforma/clientes/{empresa_id}/gestores/{gestor_id}/codigo-acesso', headers=staff)
    assert novo.status_code == 201 and novo.json()['finalidade'] == 'redefinicao'
    # Ação do suporte fica visível na auditoria do próprio cliente.
    assert 'codigo_acesso_redefinicao_gerado' in {e['acao'] for e in c.get('/api/v1/auditoria?limite=100', headers=gestor_h).json()}
    outra = str(users['a'].empresa_id)
    assert c.post(f'/api/v1/plataforma/clientes/{outra}/gestores/{gestor_id}/codigo-acesso', headers=staff).status_code == 404
    assert c.post(f'/api/v1/plataforma/clientes/{empresa_id}/gestores/{gestor_id}/codigo-acesso', headers=gestor_h).status_code == 403


def test_fluxo_com_papel_runtime_minimo(ambiente):
    c, db, users, entrar = ambiente
    h = headers(entrar())
    role = 'tt_min_acesso_' + uuid4().hex
    db.execute(text(f'CREATE ROLE {role} NOLOGIN NOSUPERUSER NOBYPASSRLS'))
    for linha in (Path(__file__).parents[1] / 'scripts/runtime-permissoes.sql').read_text(encoding='utf-8').splitlines():
        if linha.startswith('GRANT '):
            db.execute(text(linha.replace('thermotrace_runtime', role)))
    db.execute(text(f'SET LOCAL ROLE {role}'))
    email, r = convidar(c, h)
    assert ativar(c, email, r['acesso']['codigo_acesso']).status_code == 204
    assert login(c, email).status_code == 200
    db.execute(text('RESET ROLE'))
