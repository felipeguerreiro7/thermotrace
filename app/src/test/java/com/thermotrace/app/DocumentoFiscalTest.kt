package com.thermotrace.app

import com.thermotrace.app.domain.LeitorDocumentoFiscal
import com.thermotrace.app.domain.TipoDocumentoFiscal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentoFiscalTest {

    /** Monta uma chave estruturalmente válida com o DV calculado. */
    private fun chaveValida(
        uf: String = "35",      // SP
        aamm: String = "2608",  // ago/2026
        cnpj: String = "12345678000190",
        modelo: String = "55",  // NF-e
        serie: String = "001",
        numero: String = "000123456",
        tpEmis: String = "1",
        cNF: String = "00001234",
    ): String {
        val base = uf + aamm + cnpj + modelo + serie + numero + tpEmis + cNF
        require(base.length == 43) { "base tem ${base.length} dígitos" }
        return base + LeitorDocumentoFiscal.digitoVerificadorModulo11(base)
    }

    @Test
    fun `chave de NF-e valida e reconhecida e decomposta`() {
        val doc = LeitorDocumentoFiscal.interpretar(chaveValida(), "CODE_128")

        assertEquals(TipoDocumentoFiscal.NFE, doc.tipo)
        assertTrue("DV deveria conferir", doc.validado)
        assertEquals("000123456", doc.numero)
        assertEquals("001", doc.serie)
        assertEquals("12345678000190", doc.cnpjEmitente)
        assertEquals("SP", doc.ufEmitente)
        assertEquals("2026-08", doc.competencia.toString())
        assertNull(doc.observacaoValidacao)
    }

    @Test
    fun `modelo 57 vira CT-e`() {
        val doc = LeitorDocumentoFiscal.interpretar(chaveValida(modelo = "57"), "CODE_128")
        assertEquals(TipoDocumentoFiscal.CTE, doc.tipo)
        assertTrue(doc.validado)
    }

    @Test
    fun `digito trocado derruba a validacao`() {
        val chave = chaveValida()
        // Troca o penúltimo dígito: a chave continua com 44 dígitos e a
        // estrutura intacta, mas o DV deixa de fechar.
        val corrompida = chave.take(42) +
            (if (chave[42] == '9') '0' else chave[42] + 1) + chave.last()

        val doc = LeitorDocumentoFiscal.interpretar(corrompida, "MANUAL")
        assertFalse("chave corrompida não pode passar", doc.validado)
        assertTrue(doc.observacaoValidacao!!.contains("Dígito verificador"))
    }

    @Test
    fun `chave sai de dentro da URL do QR da SEFAZ`() {
        val chave = chaveValida()
        val url = "https://www.fazenda.sp.gov.br/nfce/qrcode?p=$chave|2|1|1|abcdef"

        val doc = LeitorDocumentoFiscal.interpretar(url, "QR_CODE")
        assertEquals(chave, doc.chaveAcesso)
        assertTrue(doc.validado)
        // O bruto vai inteiro para o banco, não só a chave extraída.
        assertEquals(url, doc.conteudoBruto)
    }

    @Test
    fun `parametro chNFe tambem e aceito`() {
        val chave = chaveValida()
        val url = "https://nfe.sefaz.rs.gov.br/consulta?chNFe=$chave&tpAmb=1"
        assertEquals(chave, LeitorDocumentoFiscal.interpretar(url, "QR_CODE").chaveAcesso)
    }

    @Test
    fun `separadores na digitacao nao atrapalham`() {
        val chave = chaveValida()
        val comEspacos = chave.chunked(4).joinToString(" ")
        assertEquals(chave, LeitorDocumentoFiscal.interpretar(comEspacos, "MANUAL").chaveAcesso)
    }

    /**
     * AWB: 3 dígitos de prefixo + 7 de série + 1 verificador, sendo o
     * verificador o resto da série por 7.
     * 1234567 % 7 = 5 → 020-12345675 é válido.
     */
    @Test
    fun `awb valida pelo modulo 7`() {
        val doc = LeitorDocumentoFiscal.interpretar("020-12345675", "CODE_128")
        assertEquals(TipoDocumentoFiscal.AWB, doc.tipo)
        assertTrue(doc.validado)
        assertEquals("020-12345675", doc.numero)
    }

    @Test
    fun `awb com verificador errado e recusado`() {
        val doc = LeitorDocumentoFiscal.interpretar("020-12345671", "CODE_128")
        assertEquals(TipoDocumentoFiscal.AWB, doc.tipo)
        assertFalse(doc.validado)
    }

    @Test
    fun `texto qualquer vira documento generico sem perder o bruto`() {
        val doc = LeitorDocumentoFiscal.interpretar("PEDIDO-4471/B", "MANUAL")
        assertEquals(TipoDocumentoFiscal.OUTRO, doc.tipo)
        assertFalse(doc.validado)
        assertEquals("PEDIDO-4471/B", doc.conteudoBruto)
        assertEquals("PEDIDO-4471/B", doc.numero)
    }

    @Test
    fun `identidade da remessa e da chave, e o volume deriva dela`() {
        val chave = chaveValida()
        val doc = LeitorDocumentoFiscal.interpretar(chave, "QR_CODE")
        assertEquals(chave, doc.identidade)
        assertEquals(
            "$chave#V002",
            com.thermotrace.app.domain.IdentidadeVolume.gerar(doc.identidade, 2)
        )
    }
}
