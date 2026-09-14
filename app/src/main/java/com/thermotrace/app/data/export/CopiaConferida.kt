package com.thermotrace.app.data.export

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Retorna somente depois de fechar o destino e conferir os bytes relidos. */
object CopiaConferida {
    fun salvar(origem: File, abrirSaida: () -> OutputStream, abrirLeitura: () -> InputStream) {
        val esperado = origem.inputStream().use(::hash)
        abrirSaida().use { destino -> origem.inputStream().use { it.copyTo(destino) } }
        val recebido = abrirLeitura().use(::hash)
        check(esperado == recebido) { "A cópia gravada não corresponde ao laudo original." }
    }

    private fun hash(entrada: InputStream): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = entrada.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n); total += n
        }
        return total to digest.digest().joinToString("") { "%02x".format(it) }
    }
}
