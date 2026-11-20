package com.thermotrace.app

import com.thermotrace.app.data.conta.ContratoViagem
import com.thermotrace.app.data.conta.ResumoViagem
import com.thermotrace.app.data.conta.SituacaoViagem
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** TT-040: viagem do servidor lida a partir de resposta real da API 0.16 (tests/test_viagem.py guarda a forma). */
class ViagemServidorTest {
    private val raw = java.io.File("../backend/docs/viagem-android-0.16.json").readText(Charsets.UTF_8)
    private val sessao = JSONObject(raw).getString("sessao_id")

    private fun alterado(bloco: JSONObject.() -> Unit) = JSONObject(raw).apply(bloco).toString()

    @Test fun `le a viagem real com excursoes e leitura final`() {
        val v = ContratoViagem.ler(raw, sessao)
        assertEquals(SituacaoViagem.EXCURSAO, v.situacao)
        assertEquals(9, v.medicoes.size)
        assertEquals(listOf(true, false), v.excursoes.map { it.acima })
        assertEquals(listOf(1200L, 600L), v.excursoes.map { it.duracaoNominalS })
        assertEquals(9.5, v.excursoes[0].extremoC, 0.0)
        val r = v.resumo!!
        assertEquals(66.67, r.percentualNaFaixa, 0.0)
        assertEquals(1800L, r.tempoForaNominalS)
        assertTrue(r.incluiLeituraFinal)
        assertEquals(2, v.coletasRecebidas)
        assertEquals(2.0, v.minC, 0.0); assertEquals(8.0, v.maxC, 0.0)
        assertTrue(v.simulacao)  // a fixture vem de simulação; o cartão precisa dizer isso
        assertTrue(v.integridadeCadeia)
        assertEquals(64, v.hashProjecao.length)
    }

    @Test fun `recusa resposta de outra sessao`() {
        assertThrows(IllegalArgumentException::class.java) {
            ContratoViagem.ler(raw, "00000000-0000-0000-0000-000000000001")
        }
    }

    @Test fun `recusa resposta que se declara validada fisicamente`() {
        assertThrows(IllegalArgumentException::class.java) {
            ContratoViagem.ler(alterado { put("validacao_fisica", true) }, sessao)
        }
    }

    @Test fun `recusa resumo que nao confere com os pontos`() {
        val raw2 = alterado { getJSONObject("resumo").put("quantidade", 8) }
        assertThrows(IllegalArgumentException::class.java) { ContratoViagem.ler(raw2, sessao) }
    }

    @Test fun `coletas divergentes prevalecem sobre a excursao`() {
        val v = ContratoViagem.ler(alterado { put("conferencia", "divergente") }, sessao)
        assertEquals(SituacaoViagem.DIVERGENTE, v.situacao)
    }

    @Test fun `sessao sem serie fica sem dados`() {
        val raw2 = alterado {
            put("pontos", org.json.JSONArray()); put("excursoes", org.json.JSONArray())
            put("resumo", JSONObject.NULL); put("conferencia", "nao_avaliavel")
        }
        val v = ContratoViagem.ler(raw2, sessao)
        assertEquals(SituacaoViagem.SEM_DADOS, v.situacao)
        assertNull(v.resumo)
    }

    @Test fun `mesma regra de situacao do painel de conformidade`() {
        fun resumo(fora: Int, final: Boolean) = ResumoViagem(9, 3.0, 7.0, fora, fora * 600L, 100.0, 1, final)
        assertEquals(SituacaoViagem.SEM_DADOS, ContratoViagem.situacao(null, divergente = true))
        assertEquals(SituacaoViagem.DIVERGENTE, ContratoViagem.situacao(resumo(0, true), divergente = true))
        assertEquals(SituacaoViagem.EXCURSAO, ContratoViagem.situacao(resumo(1, false), divergente = false))
        assertEquals(SituacaoViagem.EM_ANDAMENTO, ContratoViagem.situacao(resumo(0, false), divergente = false))
        assertEquals(SituacaoViagem.CONFORME, ContratoViagem.situacao(resumo(0, true), divergente = false))
    }
}
