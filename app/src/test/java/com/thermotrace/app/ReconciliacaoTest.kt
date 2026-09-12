package com.thermotrace.app

import com.thermotrace.app.nfc.SessionDecoder
import com.thermotrace.app.nfc.TimeBase
import org.junit.Assert.*
import org.junit.Test

/**
 * Confronto entre o cabeçalho da etiqueta e a série decodificada.
 *
 * Origem: ensaio de 12/09/2026, em que a coleta devolveu mínima de −29,8 °C
 * com a etiqueta sobre a mesa. O cabeçalho da etiqueta traz mínima, máxima e
 * contagem de pontos fora da faixa calculadas pela própria etiqueta — números
 * independentes da nossa decodificação da série. Se os dois discordam, um
 * deles está errado, e isso é detectável sem termômetro de referência.
 *
 * Estes testes fixam o comportamento de DETECTAR e NOMEAR a divergência.
 * Corrigir valor é outra conversa, e depende de comparação com o app do
 * fabricante — ver TT-005.
 */
class ReconciliacaoTest {
    private val start = 1_700_000_000L

    /** Cabeçalho: status, início, previstos, medidos, delay, intervalo, mín/máx registrados, limites, abaixo, acima. */
    private fun resposta(
        minRegistrada: String = "4",
        maxRegistrada: String = "6",
        abaixo: String = "0",
        acima: String = "0",
        pontos: List<String> = listOf("4.0:1", "5.0:0", "6.0:1"),
    ) = listOf("3", "$start", "3", "3", "5", "60", minRegistrada, maxRegistrada, "2", "8", abaixo, acima) + pontos

    private fun decodificar(resposta: List<String>) =
        SessionDecoder.decode(resposta, (start + 420) * 1000, TimeBase.START_INSTANT).getOrThrow()

    @Test fun `serie coerente com o cabecalho nao levanta divergencia`() {
        val r = decodificar(resposta()).reconciliacao
        assertFalse(r.naoReconciliado)
        assertNull(r.explicacao)
    }

    @Test fun `extremo absurdo na serie e denunciado pelo cabecalho`() {
        // A etiqueta diz que registrou de 4 a 6 °C. A série diz −29,8 a 30,8.
        val r = decodificar(resposta(pontos = listOf("-29.8:0", "5.0:0", "30.8:0"))).reconciliacao
        assertTrue(r.naoReconciliado)
        assertTrue(r.extremosDivergem)
        assertEquals(-29.8, r.minimaSerie!!, 0.001)
        assertEquals(30.8, r.maximaSerie!!, 0.001)
        assertEquals(4.0, r.minimaEtiqueta, 0.001)
        assertNotNull(r.explicacao)
    }

    @Test fun `contagem de pontos fora da faixa tambem e confrontada`() {
        // Série dentro dos extremos declarados, mas a etiqueta conta um ponto
        // abaixo da faixa que a série não tem.
        val r = decodificar(resposta(abaixo = "1")).reconciliacao
        assertTrue(r.naoReconciliado)
        assertTrue(r.contagensDivergem)
        assertFalse(r.extremosDivergem)
        assertEquals(0, r.abaixoSerie)
        assertEquals(1, r.abaixoEtiqueta)
        assertNotNull(r.explicacao)
    }

    @Test fun `arredondamento de uma casa decimal nao vira divergencia`() {
        // A etiqueta publica uma casa; 4,04 e 4,0 são a mesma medida.
        val r = decodificar(resposta(minRegistrada = "4.04", maxRegistrada = "5.96")).reconciliacao
        assertFalse(r.naoReconciliado)
    }

    @Test fun `divergencia de extremo e de contagem aparecem juntas no texto`() {
        val r = decodificar(
            resposta(abaixo = "0", acima = "0", pontos = listOf("-29.8:0", "5.0:0", "30.8:0"))
        ).reconciliacao
        val texto = r.explicacao!!
        assertTrue(texto, texto.contains("Não reconciliado"))
        assertTrue(texto, texto.contains("4,0") || texto.contains("4.0"))
    }

    @Test fun `extremo ilegivel nao vira coerencia`() {
        val r = decodificar(resposta(minRegistrada = "indisponível")).reconciliacao
        assertFalse(r.avaliavel)
        assertFalse(r.conferida)
        assertEquals("Não avaliável", r.rotulo)
        assertNotNull(r.explicacao)
    }

    @Test fun `infinito no cabecalho nao vira coerencia`() {
        assertFalse(decodificar(resposta(maxRegistrada = "Infinity")).reconciliacao.conferida)
    }

    @Test fun `sem amostras nao e conferencia aprovada`() {
        val bruto = resposta().take(12).toMutableList().apply { this[3] = "0" }
        val r = decodificar(bruto).reconciliacao
        assertFalse(r.avaliavel)
        assertTrue(r.explicacao!!.contains("sem amostras"))
    }

    @Test fun `exportacao e decoder usam a mesma conferencia inclusive contagens`() {
        val bruto = resposta(abaixo = "1")
        val resultado = decodificar(bruto)
        val reavaliado = com.thermotrace.app.nfc.Reconciliacao.comparar(bruto, resultado.samples.map { it.temperatureC })
        assertEquals(resultado.reconciliacao, reavaliado)
        assertTrue(reavaliado.contagensDivergem)
    }

    @Test fun `serie legada truncada nao e conferida`() {
        val r = com.thermotrace.app.nfc.Reconciliacao.comparar(resposta(), listOf(4.0, 6.0))
        assertFalse(r.conferida)
        assertTrue(r.explicacao!!.contains("quantidade"))
    }

    @Test fun `cabecalho incompleto ou contagens impossiveis nao e avaliavel`() {
        assertFalse(com.thermotrace.app.nfc.Reconciliacao.comparar(listOf("1"), listOf(4.0)).avaliavel)
        assertFalse(decodificar(resposta(abaixo = "4")).reconciliacao.avaliavel)
    }

    @Test fun `conferencia nao reescreve bruto nem temperaturas absurdas`() {
        val bruto = resposta(pontos = listOf("-29.8:0", "5.0:0", "30.8:0"))
        val resultado = decodificar(bruto)
        assertEquals(bruto, resultado.raw)
        assertEquals(listOf(-29.8, 5.0, 30.8), resultado.samples.map { it.temperatureC })
        assertFalse(resultado.reconciliacao.conferida)
    }
}
