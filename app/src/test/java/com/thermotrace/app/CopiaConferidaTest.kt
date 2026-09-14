package com.thermotrace.app

import com.thermotrace.app.data.export.CopiaConferida
import org.junit.Assert.*
import org.junit.Test
import java.io.*

class CopiaConferidaTest {
    private fun arquivo(bloco: (File) -> Unit) {
        val file=File.createTempFile("laudo-teste", ".bin")
        try { file.writeBytes(ByteArray(25000) { (it % 251).toByte() }); bloco(file) } finally { file.delete() }
    }
    @Test fun copiaGrandeRelidaIgualAoOriginal() = arquivo { file ->
        val destino=ByteArrayOutputStream()
        CopiaConferida.salvar(file,{destino},{ByteArrayInputStream(destino.toByteArray())})
        assertArrayEquals(file.readBytes(),destino.toByteArray())
    }
    @Test(expected=IllegalStateException::class) fun destinoTruncadoNaoConfirmaCopia() = arquivo { file ->
        CopiaConferida.salvar(file,{ByteArrayOutputStream()},{ByteArrayInputStream(byteArrayOf(1,2))})
    }
    @Test(expected=IOException::class) fun falhaAoFecharDestinoNaoConfirmaCopia() = arquivo { file ->
        CopiaConferida.salvar(file,{object:ByteArrayOutputStream(){ override fun close(){throw IOException("Falha simulada")} }},{error("Não deve reler")})
    }
    @Test(expected=IOException::class) fun destinoSemPermissaoDeLeituraNaoConfirmaCopia() = arquivo { file ->
        CopiaConferida.salvar(file,{ByteArrayOutputStream()},{throw IOException("Sem permissão")})
    }
}
