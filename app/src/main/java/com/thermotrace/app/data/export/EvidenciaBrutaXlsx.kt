package com.thermotrace.app.data.export

import com.thermotrace.app.data.db.LeituraEntity
import org.json.JSONObject

/** Campos ordenados, sem reunir milhares de amostras em uma única célula. */
object EvidenciaBrutaXlsx {
    fun adicionar(escritor: EscritorXlsx, leituras: List<LeituraEntity>) {
        val linhas = leituras.flatMap { leitura ->
            leitura.respostaBruta.flatMapIndexed { indice, valor ->
                val partes = fragmentar(JSONObject.quote(valor))
                partes.mapIndexed { parte, texto ->
                    listOf(
                        EscritorXlsx.Celula.Texto(leitura.id),
                        EscritorXlsx.Celula.Texto(leitura.sessaoId),
                        EscritorXlsx.Celula.Numero(indice.toDouble()),
                        EscritorXlsx.Celula.Numero((parte + 1).toDouble()),
                        EscritorXlsx.Celula.Numero(partes.size.toDouble()),
                        EscritorXlsx.Celula.Texto(texto),
                    )
                }
            }
        }
        escritor.aba("Evidência bruta",
            listOf("ID da coleta", "ID da sessão", "Índice do campo (0+)", "Parte (1+)", "Total de partes", "Valor original como string JSON"), linhas)
    }

    internal fun fragmentar(valor: String): List<String> {
        val partes = mutableListOf<String>()
        var inicio = 0
        while (inicio < valor.length) {
            var fim = minOf(inicio + 30000, valor.length)
            if (fim < valor.length && valor[fim - 1].isHighSurrogate()) fim--
            partes += valor.substring(inicio, fim)
            inicio = fim
        }
        return partes.ifEmpty { listOf("") }
    }
}
