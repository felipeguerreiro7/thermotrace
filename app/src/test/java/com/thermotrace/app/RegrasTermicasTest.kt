package com.thermotrace.app

import com.thermotrace.app.domain.GravidadeExcursao
import com.thermotrace.app.domain.Medicao
import com.thermotrace.app.domain.PerfilTermico
import com.thermotrace.app.domain.RegrasTermicas
import com.thermotrace.app.domain.ResultadoTermico
import com.thermotrace.app.domain.TipoExcursao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * Testes das regras que decidem o veredito do laudo.
 *
 * Cobrem também os casos-limite que aparecem em campo e que costumam
 * escapar: série vazia, etiqueta que gravou um ponto só, desvio curto de
 * abertura de caixa, e temperatura negativa.
 */
class RegrasTermicasTest {

    private val inicio: Instant = Instant.parse("2026-08-20T08:00:00Z")

    private fun serie(vararg temps: Double): List<Medicao> =
        temps.mapIndexed { i, t -> Medicao(i, inicio.plusSeconds(i * 600L), t) }

    // ---- MKT ---------------------------------------------------------

    @Test
    fun `mkt de serie constante e a propria temperatura`() {
        val mkt = RegrasTermicas.mkt(List(10) { 5.0 })!!
        assertEquals(5.0, mkt, 0.01)
    }

    @Test
    fun `mkt pesa o pico mais que a media aritmetica`() {
        // Metade a 2 °C, metade a 30 °C. A média é 16; a MKT tem que ser
        // MAIOR, porque a cinética de degradação é exponencial. É esse o
        // motivo de a indústria farmacêutica usar MKT e não média.
        val temps = List(50) { 2.0 } + List(50) { 30.0 }
        val mkt = RegrasTermicas.mkt(temps)!!
        assertTrue("MKT ($mkt) deveria superar a média (16)", mkt > 16.0)
    }

    @Test
    fun `mkt de serie vazia e nula, nao zero`() {
        // Zero seria "0 °C", uma leitura plausível de congelado. Nulo é
        // "não sei" — a diferença importa no laudo.
        assertNull(RegrasTermicas.mkt(emptyList()))
    }

    // ---- excursões ---------------------------------------------------

    @Test
    fun `serie inteira dentro da faixa nao gera excursao`() {
        val resumo = RegrasTermicas.resumir(
            serie(4.0, 4.5, 5.0, 4.2, 3.8), PerfilTermico.REFRIGERADO_2_8, 600
        )
        assertEquals(ResultadoTermico.CONFORME, resumo.resultado)
        assertTrue(resumo.excursoes.isEmpty())
        assertEquals(0L, resumo.tempoForaFaixaSegundos)
    }

    @Test
    fun `abertura rapida de caixa nao vira ocorrencia`() {
        // Um ponto a 9 °C = 10 min. A tolerância do perfil 2–8 é 600 s,
        // e a regra exige duração MAIOR que a tolerância. Sem isto o
        // sistema alarma a cada manuseio e o cliente desliga o alerta.
        val resumo = RegrasTermicas.resumir(
            serie(4.0, 4.0, 9.0, 4.0, 4.0), PerfilTermico.REFRIGERADO_2_8, 600
        )
        assertTrue(resumo.excursoes.isEmpty())
    }

    @Test
    fun `desvio contínuo longo vira uma excursao, nao varias`() {
        // Cinco pontos seguidos acima = UM segmento de 50 min.
        // Contar pontos daria "5 excursões", que é a resposta errada.
        val resumo = RegrasTermicas.resumir(
            serie(4.0, 10.5, 10.9, 11.3, 11.7, 12.1, 4.0),
            PerfilTermico.REFRIGERADO_2_8, 600
        )
        assertEquals(1, resumo.excursoes.size)
        val ex = resumo.excursoes.first()
        assertEquals(TipoExcursao.ACIMA, ex.tipo)
        assertEquals(5, ex.quantidadePontos)
        assertEquals(Duration.ofMinutes(50), ex.duracao)
        assertEquals(12.1, ex.picoC, 0.001)
        assertEquals(8.0, ex.limiteC, 0.001)
    }

    @Test
    fun `dois desvios separados sao duas excursoes`() {
        val resumo = RegrasTermicas.resumir(
            serie(4.0, 11.0, 11.0, 4.0, 4.0, 12.0, 12.0, 4.0),
            PerfilTermico.REFRIGERADO_2_8, 600
        )
        assertEquals(2, resumo.excursoes.size)
    }

    @Test
    fun `desvio abaixo do minimo tambem e detectado`() {
        val resumo = RegrasTermicas.resumir(
            serie(4.0, -1.0, -1.5, -2.0, 4.0), PerfilTermico.REFRIGERADO_2_8, 600
        )
        assertEquals(1, resumo.excursoes.size)
        val ex = resumo.excursoes.first()
        assertEquals(TipoExcursao.ABAIXO, ex.tipo)
        // O pico de um desvio para baixo é a MENOR temperatura.
        assertEquals(-2.0, ex.picoC, 0.001)
    }

    @Test
    fun `desvio grande em magnitude e critico mesmo se curto`() {
        // 4 °C acima do limite: produto comprometido independentemente do
        // tempo. Duração de 30 min só não bastaria para "crítica".
        val resumo = RegrasTermicas.resumir(
            serie(4.0, 13.5, 13.8, 14.0, 4.0), PerfilTermico.REFRIGERADO_2_8, 600
        )
        assertEquals(GravidadeExcursao.CRITICA, resumo.excursoes.first().gravidade)
        assertEquals(ResultadoTermico.EXCURSAO, resumo.resultado)
    }

    @Test
    fun `excursao que vai ate o fim da serie e fechada corretamente`() {
        // Caso-limite clássico: o desvio não "termina" porque a série
        // acabou. Sem a sentinela no laço, este segmento se perderia.
        val resumo = RegrasTermicas.resumir(
            serie(4.0, 4.0, 11.0, 11.5, 12.0), PerfilTermico.REFRIGERADO_2_8, 600
        )
        assertEquals(1, resumo.excursoes.size)
        assertEquals(3, resumo.excursoes.first().quantidadePontos)
    }

    @Test
    fun `serie vazia devolve resumo vazio sem estourar`() {
        val resumo = RegrasTermicas.resumir(emptyList(), PerfilTermico.REFRIGERADO_2_8, 600)
        assertEquals(ResultadoTermico.SEM_LEITURA, resumo.resultado)
        assertEquals(0, resumo.quantidadeMedicoes)
    }

    @Test
    fun `um ponto so nao quebra a segmentacao`() {
        val resumo = RegrasTermicas.resumir(serie(4.0), PerfilTermico.REFRIGERADO_2_8, 600)
        assertEquals(1, resumo.quantidadeMedicoes)
        assertEquals(ResultadoTermico.CONFORME, resumo.resultado)
    }

    @Test
    fun `perfil congelado trata negativos como faixa normal`() {
        val temps = serie(-20.0, -18.5, -22.0, -19.0)
        val resumo = RegrasTermicas.resumir(temps, PerfilTermico.CONGELADO, 600)
        assertEquals(ResultadoTermico.CONFORME, resumo.resultado)
        assertEquals(-22.0, resumo.minimaC!!, 0.001)
    }

    // ---- planejamento de capacidade ----------------------------------

    @Test
    fun `planejamento cobre a duracao com folga`() {
        // 24 h a cada 10 min = 144 pontos; com fator 1,5 = 216.
        assertEquals(216, RegrasTermicas.planejarQuantidade(Duration.ofHours(24), 600))
    }

    @Test
    fun `planejamento tem piso para viagem muito curta`() {
        // Uma hora daria 9 pontos. O piso de 12 evita uma série curta
        // demais para qualquer análise.
        assertEquals(12, RegrasTermicas.planejarQuantidade(Duration.ofMinutes(30), 600))
    }

    // ---- formatação --------------------------------------------------

    @Test
    fun `duracao zero aparece como travessao, nao como zero minutos`() {
        assertEquals("—", RegrasTermicas.formatarDuracao(0))
    }

    @Test
    fun `duracao longa aparece em dias`() {
        assertEquals("2d 3h", RegrasTermicas.formatarDuracao(Duration.ofHours(51).seconds))
    }
}
