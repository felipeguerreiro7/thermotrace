package com.thermotrace.app

import com.thermotrace.app.nfc.SessionDecoder
import com.thermotrace.app.nfc.TimeBase
import org.junit.Assert.*
import org.junit.Test

class SessionDecoderRegressionTest {
    private val start = 1_700_000_000L
    private fun response(status: String = "3", count: String = "3", points: List<String> = listOf("4.0:1", "5.0:0", "6.0:1")) =
        listOf(status, "$start", "3", count, "5", "60", "4", "6", "2", "8", "0", "0") + points

    @Test fun `ler no dia seguinte nao desloca nem estica a serie`() {
        val early = SessionDecoder.decode(response(), (start + 420) * 1000, TimeBase.START_INSTANT).getOrThrow()
        val late = SessionDecoder.decode(response(), (start + 86400) * 1000, TimeBase.START_INSTANT).getOrThrow()
        assertEquals(early.samples, late.samples)
        assertEquals(start + 300, late.samples.first().epochSeconds)
        assertEquals(start + 420, late.samples.last().epochSeconds)
        assertFalse(late.timestampsCorrected)
    }

    @Test fun `relogio do leitor atrasado nao inverte os pontos`() {
        val read = SessionDecoder.decode(response("1"), (start - 3600) * 1000, TimeBase.START_INSTANT).getOrThrow()
        assertEquals(listOf(start + 300, start + 360, start + 420), read.samples.map { it.epochSeconds })
    }

    @Test fun `ios nao soma delay pela segunda vez`() {
        val read = SessionDecoder.decode(response(), (start + 86400) * 1000, TimeBase.FIRST_WINDOW).getOrThrow()
        assertEquals(start, read.samples.first().epochSeconds)
        assertEquals(start + 120, read.samples.last().epochSeconds)
    }

    @Test fun `historico truncado nao vira laudo completo`() {
        assertTrue(SessionDecoder.decode(response(count = "4"), start * 1000, TimeBase.START_INSTANT).isFailure)
    }

    @Test fun `temperatura invalida nao chega ao grafico`() {
        for (value in listOf("NaN", "Infinity", "erro", "")) {
            assertTrue(value, SessionDecoder.decode(response(points = listOf("4", value, "6")), start * 1000, TimeBase.START_INSTANT).isFailure)
        }
    }

    @Test fun `cabecalho ilegivel nao recebe valor zero silencioso`() {
        for (index in listOf(2, 3, 4, 5, 8, 9, 10, 11)) {
            val input = response().toMutableList().apply { this[index] = "erro" }
            assertTrue("campo $index", SessionDecoder.decode(input, start * 1000, TimeBase.START_INSTANT).isFailure)
        }
    }
}
