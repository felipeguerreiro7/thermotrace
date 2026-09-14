package com.thermotrace.app

import com.thermotrace.app.domain.FechamentoDoCiclo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O contrato que separa "o celular gravou a leitura final" de "a etiqueta
 * parou de gravar".
 *
 * Se alguém derivar um do outro — em qualquer direção — o app volta a chamar
 * de encerrado um volume cuja etiqueta segue registrando, que é o erro que
 * esta enum existe para impedir.
 */
class FechamentoDoCicloTest {

    @Test
    fun `sem leitura final o ciclo esta em andamento`() {
        assertEquals(
            FechamentoDoCiclo.EM_ANDAMENTO,
            FechamentoDoCiclo.de(encerradaEmMillis = null, loggerParadoEmMillis = null),
        )
    }

    @Test
    fun `leitura final sem confirmacao de stop nao fecha o ciclo`() {
        val f = FechamentoDoCiclo.de(encerradaEmMillis = 1_756_003_600_000L, loggerParadoEmMillis = null)
        assertEquals(FechamentoDoCiclo.FINAL_SEM_STOP, f)
        assertTrue(f.pendente)
    }

    @Test
    fun `so a resposta da etiqueta fecha o ciclo`() {
        val f = FechamentoDoCiclo.de(
            encerradaEmMillis = 1_756_003_600_000L,
            loggerParadoEmMillis = 1_756_003_605_000L,
        )
        assertEquals(FechamentoDoCiclo.FECHADO, f)
        assertFalse(f.pendente)
    }

    @Test
    fun `stop sem leitura final nao vira ciclo fechado`() {
        // Combinação que o fluxo não produz hoje, mas que uma sessão parada
        // pela tela de etiquetas pode produzir amanhã. Sem leitura final não
        // existe histórico gravado, então não existe ciclo documentado —
        // tratar como FECHADO daria laudo sem a leitura que o sustenta.
        assertEquals(
            FechamentoDoCiclo.EM_ANDAMENTO,
            FechamentoDoCiclo.de(encerradaEmMillis = null, loggerParadoEmMillis = 1_756_003_605_000L),
        )
    }
}
