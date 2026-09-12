package com.thermotrace.app

import com.thermotrace.app.nfc.NfcOperator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O contrato do encerramento da entrega.
 *
 * O terceiro bipe faz duas coisas numa aproximação só: baixa o histórico e
 * desliga a etiqueta. Estes testes travam as duas propriedades que, se
 * mudarem em silêncio, produzem erro caro em campo:
 *
 *  - checkpoint NÃO pode alegar que parou a etiqueta;
 *  - o STOP usa a senha de fábrica, que é a única que as etiquetas têm hoje.
 */
class EncerramentoTest {

    private fun leitura(loggerParado: Boolean?) = NfcOperator.RawRead(
        uid = "E00401502F3A19C4",
        purpose = "destino",
        response = listOf("1", "1756000000", "432", "12", "0", "600", "4.9", "5.2", "2", "8", "0", "0"),
        deviceReadAtMillis = 1_756_003_600_000L,
        voltageV = 1.52,
        instantTempC = 5.1,
        loggerParado = loggerParado,
    )

    @Test
    fun `leitura sem stop nao alega que a etiqueta foi liberada`() {
        // O checkpoint usa o mesmo RawRead. Se o padrão fosse `false`, a tela
        // acusaria "a etiqueta não aceitou desligar" a cada parada do trajeto;
        // se fosse `true`, diria que liberou uma etiqueta que segue gravando.
        // `null` é a única resposta honesta para "nem tentei".
        val checkpoint = NfcOperator.RawRead(
            uid = "E00401502F3A19C4",
            purpose = "checkpoint",
            response = listOf("1", "1756000000", "432", "6", "0", "600", "4.9", "5.2", "2", "8", "0", "0"),
            deviceReadAtMillis = 1_756_002_000_000L,
            voltageV = 1.53,
            instantTempC = 5.0,
        )
        assertNull(checkpoint.loggerParado)
    }

    @Test
    fun `o encerramento carrega o resultado do stop junto com o historico`() {
        // Um evento só, e não dois. Dois eventos assíncronos obrigariam a tela
        // a casar a ordem de chegada — que é o que funciona na bancada e falha
        // no galpão.
        assertEquals(true, leitura(true).loggerParado)
        assertEquals(false, leitura(false).loggerParado)
    }

    @Test
    fun `encerrar usa a senha de fabrica por padrao`() {
        // Oito zeros, como no app do fabricante (`IMFragment.mStopRTC`). Se
        // alguém trocar o padrão sem gravar a senha nova nas etiquetas, o STOP
        // passa a falhar em campo e a etiqueta nunca mais é reutilizada.
        val op = NfcOperator.Operation.Encerrar()
        assertEquals(NfcOperator.SENHA_STOP_PADRAO, op.senha)
        assertEquals("00000000", op.senha)
    }

    @Test
    fun `encerrar aceita senha propria quando a etiqueta tiver uma`() {
        assertEquals("A1B2C3D4", NfcOperator.Operation.Encerrar("A1B2C3D4").senha)
    }

    @Test
    fun `encerrar e uma operacao distinta do download`() {
        // Se voltarem a usar Download para finalizar, o STOP some sem barulho:
        // o histórico continua salvo e ninguém percebe, até a etiqueta recusar
        // o próximo START com "IN RTC Flow Status".
        val encerrar: NfcOperator.Operation = NfcOperator.Operation.Encerrar()
        val download: NfcOperator.Operation = NfcOperator.Operation.Download("destino")
        assertTrue(encerrar is NfcOperator.Operation.Encerrar)
        assertTrue(download is NfcOperator.Operation.Download)
        assertTrue(encerrar != download)
    }
}
