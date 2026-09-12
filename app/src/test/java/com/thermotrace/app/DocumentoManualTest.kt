package com.thermotrace.app

import com.thermotrace.app.domain.DocumentoManual
import com.thermotrace.app.domain.LeitorDocumentoFiscal
import com.thermotrace.app.domain.TipoDocumentoFiscal
import org.junit.Assert.*
import org.junit.Test

class DocumentoManualTest {
    private fun chave(uf: String = "35", mes: String = "09"): String {
        val base = uf + "26" + mes + "12345678000190" + "55" + "001" + "000123456" + "1" + "00001234"
        return base + LeitorDocumentoFiscal.digitoVerificadorModulo11(base)
    }

    @Test fun `numero curto fica como NF e preserva origem manual`() {
        val doc = DocumentoManual.criar(" 123456 ", false)
        assertEquals(TipoDocumentoFiscal.NFE, doc.tipo)
        assertEquals("123456", doc.numero)
        assertNull(doc.chaveAcesso)
        assertFalse(doc.validado)
        assertEquals("MANUAL", doc.simbologia)
        assertEquals(" 123456 ", doc.conteudoBruto)
    }

    @Test fun `zeros a esquerda nao criam outra identidade`() {
        assertEquals(DocumentoManual.criar("123", false).identidade,
            DocumentoManual.criar("000000123", false).identidade)
    }

    @Test fun `chave formatada preserva bruto e nao afirma validacao fiscal`() {
        val bruto = chave().chunked(4).joinToString(" ")
        val doc = DocumentoManual.criar(bruto, true)
        assertEquals(chave(), doc.chaveAcesso)
        assertEquals(bruto, doc.conteudoBruto)
        assertEquals("000123456", doc.numero)
        assertFalse(doc.validado)
        assertTrue(doc.observacaoValidacao!!.contains("sem consulta fiscal"))
    }

    @Test fun `numero vazio zero letras ou longo e recusado`() {
        listOf("", " ", "0", "000", "NF123", "1234567890", "12345678901").forEach {
            assertTrue(it, runCatching { DocumentoManual.criar(it, false) }.isFailure)
        }
    }

    @Test fun `chave truncada ou com letras nao e convertida silenciosamente`() {
        listOf(chave().dropLast(1), "NF" + chave(), chave() + "0").forEach {
            assertTrue(runCatching { DocumentoManual.criar(it, true) }.isFailure)
        }
    }

    @Test fun `chave com digito incorreto e recusada`() {
        val original = chave()
        val trocada = original.dropLast(1) + ((original.last().digitToInt() + 1) % 10)
        assertTrue(runCatching { DocumentoManual.criar(trocada, true) }.isFailure)
    }

    @Test fun `data ou UF inexistente e recusada mesmo com digito correto`() {
        assertTrue(runCatching { DocumentoManual.criar(chave(mes = "13"), true) }.isFailure)
        assertTrue(runCatching { DocumentoManual.criar(chave(uf = "00"), true) }.isFailure)
    }
}
