package com.thermotrace.app

import com.thermotrace.app.nfc.TagRtcStatus
import com.thermotrace.app.nfc.confirmedLogging
import org.junit.Assert.*
import org.junit.Test

class TagRtcStatusTest {
    @Test fun `estado confirmado permite distinguir ativo de parado`() {
        assertTrue(TagRtcStatus.LOGGING.confirmedLogging())
        assertFalse(TagRtcStatus.IDLE.confirmedLogging())
    }
    @Test(expected = IllegalStateException::class)
    fun `falha de consulta nunca confirma etiqueta parada`() {
        TagRtcStatus.UNKNOWN.confirmedLogging()
    }
}
