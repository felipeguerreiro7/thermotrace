package com.thermotrace.app

import com.thermotrace.app.data.conta.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class EscopoLocalTest {
    private val sessao = SessaoConta("https://api.example.test", "token.antigo", "r".repeat(64),
        "11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222")
    private class Cofre(var atual: SessaoConta? = null) : CofreConta {
        override fun ler() = atual
        override fun gravar(sessao: SessaoConta) { atual = sessao }
        override fun limpar() { atual = null }
    }

    @Test fun legadoNuncaViraBancoDaProximaConta() {
        assertEquals("thermotrace.db", EscopoLocal.de(null).banco)
        assertNotEquals(EscopoLocal.LEGADO.banco, EscopoLocal.de(sessao).banco)
    }
    @Test fun servidorEmpresaEOperadorIsolamBancoEAjustes() {
        val contas = listOf(sessao, sessao.copy(endereco="https://producao.example.test"),
            sessao.copy(empresaId="33333333-3333-4333-8333-333333333333"),
            sessao.copy(usuarioId="44444444-4444-4444-8444-444444444444"))
        assertEquals(4, contas.map { EscopoLocal.de(it).banco }.toSet().size)
        assertEquals(4, contas.map { EscopoLocal.de(it).preferencias }.toSet().size)
    }
    @Test fun renovarOuReabrirMantemOsMesmosDados() {
        assertEquals(EscopoLocal.de(sessao), EscopoLocal.de(sessao.copy(acesso="novo", renovacao="outra", renovacaoPendente=true)))
        assertEquals(EscopoLocal.de(sessao), EscopoLocal.de(sessao.copy(endereco="https://API.EXAMPLE.TEST:443/api/v1/")))
    }
    @Test fun nomeDoArquivoNaoContemIdentificadoresNemSegredos() {
        val nome=EscopoLocal.de(sessao).banco
        assertTrue(nome.matches(Regex("thermotrace-[a-f0-9]{64}\\.db")))
        listOf(sessao.usuarioId,sessao.empresaId,sessao.acesso,sessao.renovacao,"example.test").forEach { assertFalse(nome.contains(it)) }
    }
    @Test fun identificadoresInvalidosNaoEscolhemArquivo() {
        try { EscopoLocal.de(sessao.copy(usuarioId="../../outra")); fail("Deveria rejeitar") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun consultaOfflinePreservaEscopoDaContaConhecida() = runBlocking {
        val repo=RepositorioConta(Cofre(sessao), TransporteConta { _,_,_,_,_ -> throw IOException("offline") })
        try { repo.cargas(); fail("Sem rede") } catch (_: IOException) { }
        assertEquals(EscopoLocal.de(sessao),repo.escopo.value)
    }
    @Test fun sairSemRedeDesconectaDadosSemApagarHistorico() = runBlocking {
        val repo=RepositorioConta(Cofre(sessao), TransporteConta { _,_,_,_,_ -> throw IOException("offline") })
        assertFalse(repo.sair()); assertEquals(EscopoLocal.LEGADO,repo.escopo.value)
    }
    @Test fun loginSoMudaEscopoDepoisDeSalvarSessao() = runBlocking {
        val cofre=Cofre()
        val repo=RepositorioConta(cofre,TransporteConta { _,c,_,_,_ ->
            RespostaConta(200, if(c=="/auth/login") JSONObject().put("access_token", "novo.token")
                .put("refresh_token", "r".repeat(64)).put("token_type","bearer").toString()
            else JSONObject().put("id",sessao.usuarioId).put("empresa_id",sessao.empresaId)
                .put("nome","Pessoa sintética").put("empresa","Empresa sintética").put("papel","operador").toString())
        })
        assertEquals(EscopoLocal.LEGADO,repo.escopo.value)
        repo.entrar(sessao.endereco,"sintetico@example.test","senha de teste")
        assertEquals(EscopoLocal.de(sessao),repo.escopo.value)
        assertNotNull(cofre.atual)
    }
}
