"""MFA por TOTP: obrigatória para a equipe ThermoTrace, opcional para clientes, sem reuso de código."""
import base64
from uuid import uuid4

from sqlalchemy import select

from app.core.security import hasher
from app.models import Empresa, LogAuditoria, Usuario
from app.models.enums import PapelUsuario, TipoEmpresa
from app.services import mfa
from tests.test_acesso_perfis import ambiente, headers, SENHA


def test_vetores_rfc6238():
    segredo = base64.b32encode(b'12345678901234567890').decode().rstrip('=')
    assert mfa.codigo_atual(segredo, agora=59) == '287082'
    assert mfa.codigo_atual(segredo, agora=1111111109) == '081804'
    assert mfa.verificar(segredo, '287 082', None, agora=59) == 1
    assert mfa.verificar(segredo, '287082', 1, agora=59) is None  # mesmo passo não vale de novo
    assert mfa.verificar(segredo, '000000', None, agora=59) is None


def login(c, email, codigo=None):
    corpo = {'email': email, 'senha': SENHA, **({'codigo_mfa': codigo} if codigo else {})}
    return c.post('/api/v1/auth/login', json=corpo)


def staff_novo(db):
    emp = db.scalar(select(Empresa).where(Empresa.tipo == TipoEmpresa.PLATAFORMA))
    u = Usuario(empresa_id=emp.id, nome='Staff novo', email=f'staff-{uuid4().hex}@example.org',
                senha_hash=hasher.hash(SENHA), papel=PapelUsuario.ADMIN)
    db.add(u); db.commit()
    return u


def test_equipe_thermotrace_precisa_ativar_mfa(ambiente):
    c, db, users, entrar = ambiente
    u = staff_novo(db)
    h = headers(login(c, u.email).json())
    bloqueio = c.get('/api/v1/plataforma/clientes', headers=h)
    assert bloqueio.status_code == 403 and bloqueio.json()['erro'] == 'mfa_obrigatorio'
    me = c.get('/api/v1/auth/me', headers=h).json()
    assert me['mfa_ativo'] is False and me['mfa_obrigatorio'] is True
    ini = c.post('/api/v1/auth/mfa/iniciar', headers=h)
    assert ini.status_code == 200 and ini.headers['cache-control'] == 'no-store'
    assert ini.json()['uri'].startswith('otpauth://totp/ThermoTrace')
    segredo = ini.json()['chave'].replace(' ', '')
    assert c.post('/api/v1/auth/mfa/confirmar', headers=h, json={'codigo': '000000'}).status_code == 422
    assert c.post('/api/v1/auth/mfa/confirmar', headers=h, json={'codigo': mfa.codigo_atual(segredo, deslocamento=-1)}).status_code == 204
    assert c.get('/api/v1/plataforma/clientes', headers=h).status_code == 200  # sessão atual continua
    assert c.post('/api/v1/auth/mfa/iniciar', headers=h).status_code == 409  # não troca segredo ativo sem passar pelo suporte

    sem = login(c, u.email)
    assert sem.status_code == 401 and sem.json()['erro'] == 'mfa_necessario' and 'access_token' not in sem.text
    ok = login(c, u.email, mfa.codigo_atual(segredo))
    assert ok.status_code == 200
    assert login(c, u.email, mfa.codigo_atual(segredo)).status_code == 401  # reuso do mesmo código
    assert login(c, u.email, mfa.codigo_atual(segredo, deslocamento=1)).status_code == 200  # relógio adiantado
    assert c.post('/api/v1/auth/mfa/desativar', headers=headers(ok.json()),
                  json={'codigo': mfa.codigo_atual(segredo, deslocamento=-1)}).status_code == 422  # obrigatória para staff


def test_senha_errada_nao_revela_mfa(ambiente):
    c, db, users, entrar = ambiente
    r = c.post('/api/v1/auth/login', json={'email': users['staff'].email, 'senha': 'errada-errada-errada'})
    assert r.status_code == 401 and r.json()['erro'] == 'nao_autenticado'


def test_gestor_ativa_e_desativa_mfa_opcional(ambiente):
    c, db, users, entrar = ambiente
    antiga = headers(entrar())
    h = headers(entrar())
    segredo = c.post('/api/v1/auth/mfa/iniciar', headers=h).json()['chave'].replace(' ', '')
    assert c.post('/api/v1/auth/mfa/confirmar', headers=h, json={'codigo': mfa.codigo_atual(segredo, deslocamento=-1)}).status_code == 204
    assert c.get('/api/v1/auth/me', headers=antiga).status_code == 401  # outras sessões encerradas
    assert c.get('/api/v1/auth/me', headers=h).json()['mfa_ativo'] is True
    assert login(c, users['a'].email).json()['erro'] == 'mfa_necessario'
    assert c.post('/api/v1/auth/mfa/desativar', headers=h, json={'codigo': '000000'}).status_code == 422
    assert c.post('/api/v1/auth/mfa/desativar', headers=h, json={'codigo': mfa.codigo_atual(segredo)}).status_code == 204
    assert login(c, users['a'].email).status_code == 200
    acoes = [l.acao for l in db.scalars(select(LogAuditoria).where(LogAuditoria.usuario_id == users['a'].id))]
    assert {'mfa_configuracao_iniciada', 'mfa_ativada', 'mfa_desativada'} <= set(acoes)
    assert not any(segredo in str(l.depois) for l in db.scalars(select(LogAuditoria)))
