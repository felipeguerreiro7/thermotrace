package com.thermotrace.app

import android.content.Context
import com.thermotrace.app.data.db.*
import com.thermotrace.app.data.export.*
import com.thermotrace.app.domain.*
import com.thermotrace.app.nfc.Reconciliacao
import org.json.JSONArray
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.*
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class ExportacaoAuditoriaTest {
    @get:Rule val temp = TemporaryFolder()
    private fun leitura(bruto: List<String>, serie: List<Double> = emptyList()): LeituraEntity {
        val l = mock(LeituraEntity::class.java)
        `when`(l.id).thenReturn("coleta-sintetica")
        `when`(l.sessaoId).thenReturn("sessao-sintetica")
        `when`(l.respostaBruta).thenReturn(bruto)
        `when`(l.temperaturas).thenReturn(serie)
        `when`(l.tipo).thenReturn(TipoLeitura.CHECKPOINT)
        return l
    }
    private fun xmls(bytes: ByteArray): Map<String, org.w3c.dom.Document> {
        val docs = mutableMapOf<String, org.w3c.dom.Document>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            var item = zip.nextEntry
            while (item != null) {
                if (item.name.endsWith(".xml")) docs[item.name] = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder().parse(zip.readBytes().inputStream())
                item = zip.nextEntry
            }
        }
        return docs
    }
    private fun linhas(doc: org.w3c.dom.Document): List<List<String>> {
        val rows = doc.getElementsByTagName("row")
        return (0 until rows.length).map { i ->
            val cells = (rows.item(i) as org.w3c.dom.Element).getElementsByTagName("c")
            val porColuna = (0 until cells.length).associate {
                val cell = cells.item(it) as org.w3c.dom.Element
                val coluna = cell.getAttribute("r").takeWhile { c -> c.isLetter() }
                    .fold(0) { total, c -> total * 26 + (c - 'A' + 1) } - 1
                coluna to cell.textContent
            }
            List((porColuna.keys.maxOrNull() ?: -1) + 1) { porColuna[it].orEmpty() }
        }
    }

    @Test fun `bruto com delimitadores e quebra de linha pode ser reconstruido exatamente`() {
        val bruto = listOf("", "4.5:1", "a|b", "linha\nseguinte", "\"citacao\"", "<xml>&", "😀", "x".repeat(65000) + "😀")
        val writer = EscritorXlsx()
        EvidenciaBrutaXlsx.adicionar(writer, listOf(leitura(bruto)))
        val bytes = ByteArrayOutputStream().also { writer.escrever(it) }.toByteArray()
        val rows = linhas(xmls(bytes).getValue("xl/worksheets/sheet1.xml")).drop(1)
        assertTrue(rows.all { it[5].length <= 30000 })
        val refeito = rows.groupBy { it[2].toDouble().toInt() }.toSortedMap().values.map { partes ->
            val json = partes.sortedBy { it[3].toDouble() }.joinToString("") { it[5] }
            JSONArray("[$json]").getString(0)
        }
        assertEquals(bruto, refeito)
    }

    @Test fun `campo longo se divide sem perder caracteres ou cortar emoji`() {
        val valor = "a".repeat(29999) + "😀" + "z".repeat(40000)
        val partes = EvidenciaBrutaXlsx.fragmentar(valor)
        assertEquals(valor, partes.joinToString(""))
        assertTrue(partes.all { it.length <= 30000 && !it.last().isHighSurrogate() })
    }

    @Test fun `excel completo mostra divergencia de contagens e versao da conferencia`() {
        val bruto = listOf("1", "1700000000", "3", "3", "0", "60", "4", "6", "2", "8", "1", "0", "4", "5", "6")
        val l = leitura(bruto, listOf(4.0, 5.0, 6.0))
        val remessa = mock(RemessaEntity::class.java)
        `when`(remessa.codigo).thenReturn("ENSAIO-SINTETICO")
        `when`(remessa.perfilTermicoCodigo).thenReturn(PerfilTermico.REFRIGERADO_2_8.codigo)
        `when`(remessa.status).thenReturn(StatusRemessa.AGUARDANDO_COLETA)
        val completa = mock(RemessaCompleta::class.java)
        `when`(completa.remessa).thenReturn(remessa)
        `when`(completa.volumes).thenReturn(emptyList())
        `when`(completa.documentos).thenReturn(emptyList())
        `when`(completa.custodia).thenReturn(emptyList())
        val sessao = mock(SessaoComLeituras::class.java)
        val entidadeSessao = mock(SessaoEntity::class.java)
        `when`(sessao.sessao).thenReturn(entidadeSessao)
        `when`(sessao.leituras).thenReturn(listOf(l))
        val contexto = mock(Context::class.java)
        `when`(contexto.cacheDir).thenReturn(temp.root)
        val arquivo = ExportadorLaudo(contexto).gerar(ExportadorLaudo.Entrada(completa, listOf(sessao), emptyMap(), emptyMap(), emptyMap()))
        val docs = xmls(arquivo.readBytes())
        val auditoria = linhas(docs.getValue("xl/worksheets/sheet6.xml"))
        val indice = auditoria[0].indexOf("Conferência cabeçalho e série")
        assertEquals(Reconciliacao.comparar(bruto, l.temperaturas).explicacao, auditoria[1][indice])
        assertTrue(auditoria[1].contains(Reconciliacao.VERSION))
        assertTrue(linhas(docs.getValue("xl/worksheets/sheet1.xml")).flatten().any { it.contains("pendente de conferência") })
        assertTrue(linhas(docs.getValue("xl/worksheets/sheet1.xml")).any { it == listOf("RESULTADO GERAL", "PENDENTE DE CONFERÊNCIA") })
        assertEquals(16, linhas(docs.getValue("xl/worksheets/sheet7.xml")).size)
    }
}
