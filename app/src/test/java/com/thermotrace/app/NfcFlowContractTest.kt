package com.thermotrace.app

import com.thermotrace.app.nfc.ActivationPlan
import com.thermotrace.app.nfc.SessionDecoder
import com.thermotrace.app.nfc.StorageMode
import com.thermotrace.app.nfc.TagIdentity
import com.thermotrace.app.nfc.TimeBase
import com.thermotrace.app.ui.components.EtiquetaBipada
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contratos extraídos do SDK 9.3.5 do fabricante. */
class NfcFlowContractTest {

    @Test
    fun `viagem padrao cabe no modo normal`() {
        val plano = ActivationPlan.forShipment(
            plannedDurationHours = 72.0,
            intervalSeconds = 600,
            minC = 2,
            maxC = 8,
            safetyFactor = 1.5,
        ).getOrThrow()

        assertEquals(648, plano.loggingCount)
        assertEquals(StorageMode.NORMAL, plano.storageMode)
        assertEquals(0, plano.storageMode.configurationMode)
    }

    @Test
    fun `plano longo exige ajustar intervalo antes do start`() {
        val resultado = ActivationPlan.forShipment(
            plannedDurationHours = 1_000.0,
            intervalSeconds = 600,
            minC = 2,
            maxC = 8,
            safetyFactor = 1.5,
        )

        assertTrue(resultado.isFailure)
        assertEquals(StorageMode.RAW, StorageMode.smallestFor(9_000))
    }

    @Test
    fun `plano acima da capacidade falha antes de encostar na etiqueta`() {
        val resultado = ActivationPlan.forShipment(
            plannedDurationHours = 2_000.0,
            intervalSeconds = 600,
            minC = 2,
            maxC = 8,
            safetyFactor = 1.5,
        )

        assertTrue(resultado.isFailure)
    }

    @Test
    fun `resposta do sdk 935 preserva cabecalho pontos e campo`() {
        val inicio = 1_700_000_000L
        val resposta = listOf(
            "1", inicio.toString(), "648", "3", "0", "600",
            "3.5", "7.5", "2.0", "8.0", "0", "0",
            "4.25:1", "4.50:2", "4.75:3",
        )
        val leituraEm = (inicio + 1_250L) * 1_000L

        val decodificada = SessionDecoder.decode(
            response = resposta,
            deviceReadAtMillis = leituraEm,
            timeBase = TimeBase.START_INSTANT,
        ).getOrThrow()

        assertEquals(3, decodificada.samples.size)
        assertEquals(inicio, decodificada.samples.first().epochSeconds)
        assertEquals(inicio + 1_200L, decodificada.samples.last().epochSeconds)
        assertEquals(4.25, decodificada.samples.first().temperatureC, 0.0)
        assertEquals(1, decodificada.samples.first().fieldFlag)
        assertFalse(decodificada.timestampsCorrected)
    }

    @Test
    fun `resposta curta nao vira laudo vazio`() {
        assertTrue(
            SessionDecoder.decode(
                response = listOf("2"),
                deviceReadAtMillis = 0L,
                timeBase = TimeBase.START_INSTANT,
            ).isFailure
        )
    }

    @Test
    fun `uid e comparado sem pontuacao ou caixa`() {
        assertTrue(TagIdentity.matches("01:ab:CD:ef", "01ABCDEF"))
        assertEquals("01ABCDEF", TagIdentity.canonical("01-ab-cd-ef"))
    }

    @Test
    fun `entrega so finaliza depois da nota fiscal`() {
        val semNota = EtiquetaBipada(
            uid = "01ABCDEF",
            serial = "TT-01ABCDEF",
            registrando = true,
            temperaturaC = 5.0,
            tensaoV = 1.5,
            remessaVinculada = "TT-2026-0001",
            documentoVinculado = false,
        )

        assertFalse(semNota.podeFinalizar)
        assertTrue(semNota.copy(documentoVinculado = true).podeFinalizar)
    }
}
