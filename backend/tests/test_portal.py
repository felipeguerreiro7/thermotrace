"""HTML público sem dados; API continua autenticada e isolada por empresa."""
import pytest
from sqlalchemy import select
from app.models import Empresa
from app.models.enums import TipoEmpresa
from tests.test_acesso_perfis import ambiente, headers
from tests.test_cargas import preparar, remessa


def test_raiz_abre_site_no_navegador_e_preserva_resposta_api(cliente):
    r = cliente.get('/', headers={'Accept':'text/html'}, follow_redirects=False)
    assert r.status_code == 307 and r.headers['location'] == '/portal'
    assert r.headers['Vary'] == 'Accept' and r.headers['Cache-Control'] == 'no-store'
    r = cliente.get('/', headers={'Accept':'application/json'})
    assert r.status_code == 200 and r.json()['portal'] == '/portal'


@pytest.mark.parametrize('path', ['/portal', '/portal/'])
def test_portal_entrega_shell_com_protecoes(cliente, path):
    r = cliente.get(path)
    assert r.status_code == 200
    assert 'lang="pt-BR"' in r.text and '/portal/assets/app.mjs' in r.text
    assert r.headers['Cache-Control'] == 'no-store'
    assert "frame-ancestors 'none'" in r.headers['Content-Security-Policy']
    assert "script-src 'self'" in r.headers['Content-Security-Policy']
    assert r.headers['X-Content-Type-Options'] == 'nosniff'


@pytest.mark.parametrize('asset,kind', [('app.mjs','javascript'), ('api.mjs','javascript'), ('styles.css','css')])
def test_assets_publicos_e_tipo_correto(cliente, asset, kind):
    r = cliente.get('/portal/assets/' + asset)
    assert r.status_code == 200
    assert kind in r.headers['content-type']


@pytest.mark.parametrize('path', ['/portal/assets/routes.py', '/portal/assets/%2e%2e/routes.py',
                                  '/portal/assets/.env', '/portal/inexistente'])
def test_portal_nao_expoe_arquivos_internos(cliente, path):
    assert cliente.get(path).status_code == 404


@pytest.mark.parametrize('path', ['/remessas', '/usuarios', '/auditoria', '/plataforma/clientes'])
def test_html_publico_nao_abre_dados(cliente, path):
    assert cliente.get('/api/v1' + path).status_code == 401


def test_dono_transportadora_staff_continuam_isolados(ambiente):
    c, db, users, entrar = ambiente
    transportadora = db.scalar(select(Empresa).where(Empresa.id == users['b'].empresa_id))
    transportadora.tipo = TipoEmpresa.TRANSPORTADORA
    db.commit()
    dono = headers(entrar('a')); transportador = headers(entrar('b')); staff = headers(entrar('staff'))
    assert c.get('/api/v1/auth/me', headers=transportador).json()['tipo_empresa'] == 'transportadora'
    _, produto = preparar(c, dono)
    carga = remessa(c, dono, produto)
    assert c.get('/api/v1/remessas/' + carga['id'], headers=transportador).status_code == 404
    assert c.get('/api/v1/remessas/localizar?valor=CARGA-001', headers=transportador).json()['candidatas'] == []
    assert c.get('/api/v1/remessas', headers=staff).status_code == 403
    assert c.get('/api/v1/plataforma/clientes', headers=dono).status_code == 403
    assert c.get('/api/v1/plataforma/clientes', headers=staff).status_code == 200
