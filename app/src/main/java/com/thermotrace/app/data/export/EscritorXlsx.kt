package com.thermotrace.app.data.export

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Escritor de .xlsx sem biblioteca externa.
 *
 * Um .xlsx é um zip de XML. São ~150 linhas para gerar um arquivo que o Excel,
 * o LibreOffice e o Google Sheets abrem — e isso evita duas escolhas ruins:
 *
 *  - `jxl`, que o app de referência do fabricante usa, gera `.xls` de 1997 e
 *    está sem manutenção há anos;
 *  - Apache POI, que resolveria, pesa dezenas de MB e estoura o limite de
 *    métodos de um app Android.
 *
 * Sem dependência, sem formato obsoleto, sem inchaço.
 */
class EscritorXlsx {

    data class Aba(
        val nome: String,
        val cabecalho: List<String>,
        val linhas: List<List<Celula>>,
    )

    sealed interface Celula {
        data class Texto(val valor: String) : Celula
        data class Numero(val valor: Double) : Celula
        data object Vazia : Celula
    }

    private val abas = mutableListOf<Aba>()

    fun aba(nome: String, cabecalho: List<String>, linhas: List<List<Celula>>) = apply {
        // O Excel recusa nomes com : \ / ? * [ ] e acima de 31 caracteres.
        val seguro = nome.replace(Regex("[:\\\\/?*\\[\\]]"), "-").take(31)
        abas += Aba(seguro, cabecalho, linhas)
    }

    fun escrever(saida: OutputStream) {
        require(abas.isNotEmpty()) { "Nenhuma aba para escrever." }
        ZipOutputStream(saida).use { zip ->
            zip.entrada("[Content_Types].xml", contentTypes())
            zip.entrada("_rels/.rels", relsRaiz())
            zip.entrada("xl/workbook.xml", workbook())
            zip.entrada("xl/_rels/workbook.xml.rels", relsWorkbook())
            zip.entrada("xl/styles.xml", estilos())
            abas.forEachIndexed { i, aba ->
                zip.entrada("xl/worksheets/sheet${i + 1}.xml", planilha(aba))
            }
        }
    }

    // -----------------------------------------------------------------

    private fun ZipOutputStream.entrada(nome: String, conteudo: String) {
        putNextEntry(ZipEntry(nome))
        write(conteudo.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun contentTypes(): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        append("""<Default Extension="xml" ContentType="application/xml"/>""")
        append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
        abas.indices.forEach {
            append("""<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
        }
        append("</Types>")
    }

    private fun relsRaiz(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>""" +
            """</Relationships>"""

    private fun workbook(): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" """)
        append("""xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
        abas.forEachIndexed { i, aba ->
            append("""<sheet name="${escapar(aba.nome)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""")
        }
        append("</sheets></workbook>")
    }

    private fun relsWorkbook(): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        abas.indices.forEach {
            append("""<Relationship Id="rId${it + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""")
        }
        append("""<Relationship Id="rIdS" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
        append("</Relationships>")
    }

    /** Dois estilos: 0 = normal, 1 = cabeçalho (negrito sobre fundo escuro). */
    private fun estilos(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
            """<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font>""" +
            """<font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font></fonts>""" +
            """<fills count="3"><fill><patternFill patternType="none"/></fill>""" +
            """<fill><patternFill patternType="gray125"/></fill>""" +
            """<fill><patternFill patternType="solid"><fgColor rgb="FF1F3864"/><bgColor indexed="64"/></patternFill></fill></fills>""" +
            """<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>""" +
            """<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""" +
            """<cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>""" +
            """<xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/></cellXfs>""" +
            """</styleSheet>"""

    private fun planilha(aba: Aba): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        // Congela a primeira linha: séries de 4.000 pontos são inúteis sem isso.
        append("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
        append("<sheetData>")

        append("""<row r="1">""")
        aba.cabecalho.forEachIndexed { c, titulo ->
            append("""<c r="${coluna(c)}1" t="inlineStr" s="1"><is><t>${escapar(titulo)}</t></is></c>""")
        }
        append("</row>")

        aba.linhas.forEachIndexed { r, linha ->
            val numeroLinha = r + 2
            append("""<row r="$numeroLinha">""")
            linha.forEachIndexed { c, celula ->
                val ref = "${coluna(c)}$numeroLinha"
                when (celula) {
                    is Celula.Texto ->
                        append("""<c r="$ref" t="inlineStr"><is><t>${escapar(celula.valor)}</t></is></c>""")
                    is Celula.Numero ->
                        append("""<c r="$ref"><v>${formatarNumero(celula.valor)}</v></c>""")
                    Celula.Vazia -> Unit
                }
            }
            append("</row>")
        }

        append("</sheetData></worksheet>")
    }

    private fun formatarNumero(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString()
        else String.format(java.util.Locale.US, "%.4f", v)

    /** 0 -> A, 25 -> Z, 26 -> AA. */
    private fun coluna(indice: Int): String {
        var n = indice
        val sb = StringBuilder()
        while (true) {
            sb.insert(0, ('A' + n % 26))
            n = n / 26 - 1
            if (n < 0) break
        }
        return sb.toString()
    }

    private fun escapar(texto: String): String = buildString {
        for (ch in texto) when {
            ch == '&' -> append("&amp;")
            ch == '<' -> append("&lt;")
            ch == '>' -> append("&gt;")
            ch == '"' -> append("&quot;")
            ch == '\'' -> append("&apos;")
            // O XML do Excel rejeita caracteres de controle.
            ch.code < 0x20 && ch != '\n' && ch != '\t' -> append(' ')
            else -> append(ch)
        }
    }
}
