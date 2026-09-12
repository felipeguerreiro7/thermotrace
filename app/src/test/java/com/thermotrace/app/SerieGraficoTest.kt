package com.thermotrace.app

import com.thermotrace.app.domain.Medicao
import com.thermotrace.app.domain.prepararSerieGrafico
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class SerieGraficoTest {
    private fun ponto(i: Int, seconds: Long, temp: Double = 4.0) = Medicao(i, Instant.ofEpochSecond(seconds), temp)
    @Test fun `eixo usa tempo e nao posicao na lista`() {
        val result = prepararSerieGrafico(listOf(ponto(0, 0), ponto(1, 60), ponto(2, 600))).getOrThrow()
        assertEquals(0.1f, result[1].fracaoTempo, 0.0001f)
    }
    @Test fun `amostra unica fica visivel no centro`() {
        assertEquals(0.5f, prepararSerieGrafico(listOf(ponto(0, 0))).getOrThrow().single().fracaoTempo, 0f)
    }
    @Test fun `indices ausentes interrompem a linha`() {
        assertTrue(prepararSerieGrafico(listOf(ponto(0, 0), ponto(4, 240))).getOrThrow()[1].iniciaTrecho)
    }
    @Test fun `intervalo longo interrompe a linha quando intervalo e conhecido`() {
        assertTrue(prepararSerieGrafico(listOf(ponto(0, 0), ponto(1, 600)), 60).getOrThrow()[1].iniciaTrecho)
    }
    @Test fun `ordem recebida nao altera resultado`() {
        val points = listOf(ponto(0, 0), ponto(1, 60))
        assertEquals(prepararSerieGrafico(points).getOrThrow(), prepararSerieGrafico(points.reversed()).getOrThrow())
    }
    @Test fun `nan e tempo reverso nao produzem grafico enganoso`() {
        assertTrue(prepararSerieGrafico(listOf(ponto(0, 0, Double.NaN))).isFailure)
        assertTrue(prepararSerieGrafico(listOf(ponto(0, 60), ponto(1, 0))).isFailure)
        assertTrue(prepararSerieGrafico(listOf(ponto(0, 0), ponto(0, 60))).isFailure)
    }
}
