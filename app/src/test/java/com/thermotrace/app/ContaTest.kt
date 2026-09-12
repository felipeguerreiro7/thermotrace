package com.thermotrace.app

import com.thermotrace.app.data.conta.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ContaTest {
    private val empresa = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val usuario = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val cargaId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
    private val outraEmpresa = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
    private val sessao get() = SessaoConta("https://api.example.test", "old.access", "a".repeat(64), usuario, empresa)
    private fun eu(emp: String = empresa) = JSONObject().put("id", usuario).put("empresa_id", emp)
        .put("nome", "Operador de teste").put("empresa", "Cliente de teste").put("papel", "operador").toString()
    private fun tokens() = JSONObject().put("token_type", "bearer").put("access_token", "new.access")
        .put("refresh_token", "b".repeat(64)).toString()
    private fun carga(emp: String = empresa, id: String = cargaId) = JSONObject().put("id", id)
        .put("empresa_id", emp).put("codigo", "CARGA-TESTE").put("destinatario_nome", "Parceiro sintético")
        .put("status", "preparacao").put("criado_em", "2026-09-12T10:00:00Z")
    private fun lista(emp: String = empresa) = JSONArray().put(carga(emp)).toString()
    private class Cofre(var atual: SessaoConta? = null) : CofreConta {
        val gravacoes = mutableListOf<SessaoConta>()
        var falharGravacao = false
        override fun ler() = atual
        override fun gravar(sessao: SessaoConta) {
            if (falharGravacao) throw IOException("Falha simulada no armazenamento")
            gravacoes += sessao; atual = sessao
        }
        override fun limpar() { atual = null }
    }
    private data class Chamada(val caminho: String, val metodo: String, val token: String?, val corpo: String?)
    private class Transporte(val tratar: (Chamada) -> RespostaConta) : TransporteConta {
        val chamadas = mutableListOf<Chamada>()
        override fun enviar(endereco: String, caminho: String, metodo: String, token: String?, corpo: String?): RespostaConta {
            val c = Chamada(caminho, metodo, token, corpo); chamadas += c; return tratar(c)
        }
    }
    private inline fun <reified T : Throwable> falha(bloco: () -> Unit): T {
        try { bloco() } catch (e: Throwable) { if (e is T) return e else throw e }
        throw AssertionError("Esperada ${T::class.java.simpleName}")
    }

    @Test fun enderecoAceitaApenasRaizHttpsSemCredenciais() {
        assertEquals("https://api.example.test", EnderecoConta.normalizar(" HTTPS://API.EXAMPLE.TEST/api/v1/ "))
        assertEquals("https://api.example.test:8443", EnderecoConta.normalizar("https://api.example.test:8443/"))
        listOf("http://api.example.test", "https://u:p@api.example.test", "https://api.example.test?x=1",
            "https://api.example.test/#senha", "https://api.example.test/outra", "https://api.example.test:0",
            "https://api.example.test:65536", "https://", "https://api.example.test/api/v1/../x").forEach {
            falha<IllegalArgumentException> { EnderecoConta.normalizar(it) }
        }
    }
    @Test fun sessaoNuncaExibeTokensNoToString() {
        assertFalse(sessao.toString().contains(sessao.acesso)); assertFalse(sessao.toString().contains(sessao.renovacao))
    }
    @Test fun primeiraAberturaNaoFazPedido() = runBlocking {
        val repo = RepositorioConta(Cofre(), Transporte { error("Não deveria transmitir") })
        assertNull(repo.restaurar())
    }
    @Test fun loginConfereIdentidadeAntesDePersistir() = runBlocking {
        val cofre = Cofre()
        val rede = Transporte { c ->
            assertNull(cofre.atual)
            when (c.caminho) {
                "/auth/login" -> { assertNull(c.token); assertEquals("senha-teste", JSONObject(c.corpo!!).getString("senha")); RespostaConta(200, tokens()) }
                "/auth/me" -> { assertEquals("new.access", c.token); RespostaConta(200, eu()) }
                else -> error(c.caminho)
            }
        }
        val identidade = RepositorioConta(cofre, rede).entrar(sessao.endereco, "teste@example.test", "senha-teste")
        assertEquals(empresa, identidade.empresaId); assertEquals("new.access", cofre.atual!!.acesso)
        assertEquals(1, cofre.gravacoes.size)
    }
    @Test fun loginNegadoNaoPersisteSessao() = runBlocking {
        val cofre = Cofre(); val repo = RepositorioConta(cofre, Transporte { RespostaConta(401, "") })
        assertEquals(401, falha<FalhaHttpConta> { repo.entrar(sessao.endereco, "t@example.test", "senha") }.status)
        assertNull(cofre.atual)
    }
    @Test fun falhaAoSalvarLoginTentaRevogarSessaoCriada() = runBlocking {
        val cofre = Cofre().apply { falharGravacao = true }
        val rede = Transporte { c -> RespostaConta(200, if (c.caminho == "/auth/login") tokens() else if (c.caminho == "/auth/me") eu() else "") }
        falha<IOException> { RepositorioConta(cofre, rede).entrar(sessao.endereco, "t@example.test", "senha") }
        assertNull(cofre.atual); assertEquals("/auth/logout", rede.chamadas.last().caminho)
    }
    @Test fun acessoExpiradoRenovaUmaVezEConfereIdentidade() = runBlocking {
        val cofre = Cofre(sessao)
        val rede = Transporte { c -> when {
            c.caminho == "/auth/refresh" -> {
                assertTrue(cofre.atual!!.renovacaoPendente)
                assertEquals(sessao.renovacao, JSONObject(c.corpo!!).getString("refresh_token"))
                RespostaConta(200, tokens())
            }
            c.caminho == "/auth/me" -> RespostaConta(200, eu())
            c.token == "old.access" -> RespostaConta(401, "")
            else -> RespostaConta(200, lista())
        } }
        assertEquals(1, RepositorioConta(cofre, rede).cargas().cargas.size)
        assertEquals(listOf(true, false), cofre.gravacoes.map { it.renovacaoPendente })
        assertEquals(1, rede.chamadas.count { it.caminho == "/auth/refresh" })
    }
    @Test fun pedidosConcorrentesCompartilhamUmaUnicaRenovacao() = runBlocking {
        val cofre = Cofre(sessao)
        val rede = Transporte { c -> when {
            c.caminho == "/auth/refresh" -> RespostaConta(200, tokens())
            c.caminho == "/auth/me" -> RespostaConta(200, eu())
            c.token == "old.access" -> RespostaConta(401, "")
            else -> RespostaConta(200, lista())
        } }
        val repo = RepositorioConta(cofre, rede)
        coroutineScope { val a = async { repo.cargas() }; val b = async { repo.cargas() }; a.await(); b.await() }
        assertEquals(1, rede.chamadas.count { it.caminho == "/auth/refresh" })
    }
    @Test fun proibicaoNaoRenovaNemApagaSessao() = runBlocking {
        val cofre = Cofre(sessao); val rede = Transporte { RespostaConta(403, "") }
        assertEquals(403, falha<FalhaHttpConta> { RepositorioConta(cofre, rede).cargas() }.status)
        assertEquals(sessao, cofre.atual); assertEquals(1, rede.chamadas.size)
    }
    @Test fun falhaDeRedeNaConsultaPreservaSessao() = runBlocking {
        val cofre = Cofre(sessao)
        falha<IOException> { RepositorioConta(cofre, Transporte { throw IOException("offline") }).cargas() }
        assertEquals(sessao, cofre.atual)
    }
    @Test fun renovacaoIncertaExigeNovoLoginSemRepetirToken() = runBlocking {
        val cofre = Cofre(sessao)
        val rede = Transporte { c -> if (c.caminho == "/auth/refresh") throw IOException("Resposta perdida") else RespostaConta(401, "") }
        val repo = RepositorioConta(cofre, rede)
        falha<LoginNecessario> { repo.cargas() }; falha<LoginNecessario> { repo.cargas() }
        assertNull(cofre.atual); assertEquals(1, rede.chamadas.count { it.caminho == "/auth/refresh" })
    }
    @Test fun processoInterrompidoNaoReutilizaRefreshPendente() = runBlocking {
        val cofre = Cofre(sessao.copy(renovacaoPendente = true))
        falha<LoginNecessario> { RepositorioConta(cofre, Transporte { error("Não deve transmitir") }).restaurar() }
        assertNull(cofre.atual)
    }
    @Test fun resposta401MesmoDepoisDeRenovarApagaSessao() = runBlocking {
        val cofre = Cofre(sessao)
        val rede = Transporte { c -> when (c.caminho) {
            "/auth/refresh" -> RespostaConta(200, tokens())
            "/auth/me" -> RespostaConta(200, eu())
            else -> RespostaConta(401, "")
        } }
        falha<LoginNecessario> { RepositorioConta(cofre, rede).cargas() }
        assertNull(cofre.atual); assertEquals(1, rede.chamadas.count { it.caminho == "/auth/refresh" })
    }
    @Test fun respostaDeOutraEmpresaEhBloqueada() = runBlocking {
        val cofre = Cofre(sessao)
        falha<LoginNecessario> { RepositorioConta(cofre, Transporte { RespostaConta(200, lista(outraEmpresa)) }).cargas() }
        assertNull(cofre.atual)
    }
    @Test fun trocaDeEmpresaDuranteRenovacaoBloqueiaAcesso() = runBlocking {
        val cofre = Cofre(sessao)
        val rede = Transporte { c -> when (c.caminho) {
            "/auth/refresh" -> RespostaConta(200, tokens())
            "/auth/me" -> RespostaConta(200, eu(outraEmpresa))
            else -> RespostaConta(401, "")
        } }
        falha<LoginNecessario> { RepositorioConta(cofre, rede).cargas() }; assertNull(cofre.atual)
    }
    @Test fun logoutLimpaMesmoSemRedeMasNaoConfirmaServidor() = runBlocking {
        val cofre = Cofre(sessao)
        assertFalse(RepositorioConta(cofre, Transporte { throw IOException("offline") }).sair())
        assertNull(cofre.atual)
    }
    @Test fun logoutExpiradoRenovaAntesDeRevogar() = runBlocking {
        val cofre = Cofre(sessao)
        val rede = Transporte { c -> when {
            c.caminho == "/auth/refresh" -> RespostaConta(200, tokens())
            c.caminho == "/auth/me" -> RespostaConta(200, eu())
            c.token == "old.access" -> RespostaConta(401, "")
            else -> RespostaConta(204, "")
        } }
        assertTrue(RepositorioConta(cofre, rede).sair()); assertNull(cofre.atual)
        assertEquals("new.access", rede.chamadas.last().token)
    }
    @Test fun buscaCodificaDocumentoEPreservaTodasAsCandidatas() = runBlocking {
        val raw = JSONObject().put("ha_mais", false).put("candidatas", JSONArray().put(carga()).put(carga(id = usuario))).toString()
        val rede = Transporte { RespostaConta(200, raw) }
        val pagina = RepositorioConta(Cofre(sessao), rede).cargas("NF 123/45&x=1", 30)
        assertEquals(2, pagina.cargas.size); assertFalse(pagina.haMais)
        assertEquals("/remessas/localizar?valor=NF+123%2F45%26x%3D1&limite=30&offset=30", rede.chamadas.single().caminho)
    }
    @Test fun detalheUsaFaixaPreservadaNaCargaEDocumentosMultiplos() {
        val raw = carga().put("intervalo_segundos", 300).put("criterio", JSONObject().put("perfil",
            JSONObject().put("min_c", "2.50").put("max_c", "8.00").put("versao", 2)))
            .put("documentos", JSONArray().put(JSONObject().put("tipo", "nfe").put("numero", "123"))
                .put(JSONObject().put("tipo", "outro").put("numero", "REFERENCIA-1")))
            .put("volumes", JSONArray().put(JSONObject()).put(JSONObject()))
        val d = JsonConta.detalhe(raw.toString(), empresa)
        assertEquals("2,5 a 8 °C", d.faixa); assertEquals(2, d.documentos.size)
        assertEquals(2, d.quantidadeVolumes); assertEquals(2, d.versaoPerfil)
        raw.put("criterio", JSONObject.NULL)
        assertNull(JsonConta.detalhe(raw.toString(), empresa).faixa)
    }
    @Test fun contratoRecusaTokensComQuebraDeLinhaEDadosTermicosInvalidos() {
        falha<IllegalArgumentException> { JsonConta.tokens(JSONObject(tokens()).put("access_token", "bearer\r\nX:1").toString()) }
        val raw = carga().put("intervalo_segundos", 300).put("documentos", JSONArray()).put("volumes", JSONArray())
            .put("criterio", JSONObject().put("perfil", JSONObject().put("min_c", "NaN").put("max_c", "8").put("versao", 1)))
        falha<IllegalArgumentException> { JsonConta.detalhe(raw.toString(), empresa) }
    }
    @Test fun contratoRecusaUuidAbreviado() {
        falha<IllegalArgumentException> { JsonConta.uuid(JSONObject().put("id", "1-1-1-1-1"), "id") }
    }
    @Test fun contratoNaoTruncaIntervaloFracionado() {
        val raw = carga().put("intervalo_segundos", 300.5).put("documentos", JSONArray())
            .put("volumes", JSONArray()).put("criterio", JSONObject.NULL)
        falha<ArithmeticException> { JsonConta.detalhe(raw.toString(), empresa) }
    }
    @Test fun respostasCapturadasDaApi03SaoCompativeisComAndroid() {
        val raw = javaClass.getResourceAsStream("/conta-api-03.json")!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val fixture = JSONObject(raw)
        val identidade = JsonConta.identidade(fixture.getJSONObject("me").toString())
        val lista = JsonConta.pagina(fixture.getJSONArray("lista").toString(), identidade.empresaId, false)
        val busca = JsonConta.pagina(fixture.getJSONObject("busca").toString(), identidade.empresaId, true)
        val detalhe = JsonConta.detalhe(fixture.getJSONObject("detalhe").toString(), identidade.empresaId)
        assertEquals(lista.cargas.single().id, detalhe.carga.id)
        assertEquals(lista.cargas.single().id, busca.cargas.single().id)
        assertEquals("2 a 8 °C", detalhe.faixa)
        assertEquals("PED-SMOKE", detalhe.documentos.single().numero)
        assertEquals(600, detalhe.intervaloSegundos); assertEquals(2, detalhe.quantidadeVolumes)
    }
}
