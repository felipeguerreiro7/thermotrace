"""HTML público sem dados; API continua autenticada e isolada por empresa."""
import pytest
from sqlalchemy import select
from app.models import Empresa
from app.models.enums import TipoEmpresa
from tests.test_acesso_perfis import ambiente, headers
from tests.test_cargas import preparar, remessa


def test_raiz_abre_site_no_navegador_e_preserva_resposta_api(cliente):
    r = cliente.get('/', headers={'Accept':'text/html'}, follow_redirects=False)
    assert r.status_code == 200 and 'text/html' in r.headers['content-type']
    assert 'href="/portal"' in r.text and 'href="/privacidade"' in r.text
    assert r.headers['Vary'] == 'Accept' and r.headers['Cache-Control'] == 'no-store'
    r = cliente.get('/', headers={'Accept':'application/json'})
    assert r.status_code == 200 and r.json()['portal'] == '/portal'


@pytest.mark.parametrize('path', ['/privacidade', '/termos'])
def test_site_publico_sem_script_e_com_protecoes(cliente, path):
    r = cliente.get(path)
    assert r.status_code == 200 and 'lang="pt-BR"' in r.text
    assert '<script' not in r.text and '/site/site.css' in r.text
    csp = r.headers['Content-Security-Policy']
    assert "default-src 'none'" in csp and "frame-ancestors 'none'" in csp and 'script-src' not in csp
    assert r.headers['X-Content-Type-Options'] == 'nosniff'


def test_raiz_html_tambem_sem_script(cliente):
    r = cliente.get('/', headers={'Accept':'text/html'})
    assert '<script' not in r.text
    assert "default-src 'none'" in r.headers['Content-Security-Policy']


def test_politica_declara_localizacao_e_ainda_e_rascunho(cliente):
    texto = cliente.get('/privacidade').text
    # Enquanto houver campo a completar, a faixa de rascunho não pode sumir.
    assert ('class="pendente"' in texto) == ('id="rascunho"' in texto)
    assert 'Localização' in texto and 'não é enviada ao servidor' in texto


def test_css_do_site_e_nada_mais(cliente):
    r = cliente.get('/site/site.css')
    assert r.status_code == 200 and 'text/css' in r.headers['content-type']
    for path in ['/site/index.html', '/site/privacidade.html', '/site/../routes.py', '/site/']:
        assert cliente.get(path).status_code == 404


@pytest.mark.parametrize('path', ['/portal', '/portal/', '/portal/conferir'])
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
