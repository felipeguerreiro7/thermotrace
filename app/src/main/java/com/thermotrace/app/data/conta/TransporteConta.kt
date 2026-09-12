package com.thermotrace.app.data.conta

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class RespostaConta(val status: Int, val corpo: String)
class FalhaHttpConta(val status: Int) : IOException(when (status) {
    401 -> "E-mail, senha ou sessão inválidos."
    403 -> "Sua conta não tem permissão para esta consulta."
    404 -> "Carga não encontrada para sua empresa."
    429 -> "Muitas tentativas. Aguarde alguns minutos."
    in 300..399 -> "O endereço de acesso mudou. Confira o endereço com a ThermoTrace."
    else -> "Não foi possível concluir a consulta. Tente novamente."
})

fun interface TransporteConta {
    fun enviar(endereco: String, caminho: String, metodo: String, token: String?, corpo: String?): RespostaConta
}

class TransporteHttpsConta : TransporteConta {
    override fun enviar(endereco: String, caminho: String, metodo: String, token: String?, corpo: String?): RespostaConta {
        val base = EnderecoConta.normalizar(endereco)
        require(caminho.startsWith("/auth/") || caminho.startsWith("/remessas"))
        val conexao = URL("$base/api/v1$caminho").openConnection() as HttpURLConnection
        try {
            conexao.instanceFollowRedirects = false // Nunca encaminhar credenciais a outro destino.
            conexao.requestMethod = metodo
            conexao.connectTimeout = 10_000; conexao.readTimeout = 15_000
            conexao.useCaches = false
            conexao.setRequestProperty("Accept", "application/json")
            token?.let {
                require(it.matches(Regex("[A-Za-z0-9_.-]{1,8192}")))
                conexao.setRequestProperty("Authorization", "Bearer $it")
            }
            corpo?.let {
                val bytes = it.toByteArray(Charsets.UTF_8); require(bytes.size <= 16_384)
                conexao.doOutput = true
                conexao.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conexao.setFixedLengthStreamingMode(bytes.size)
                conexao.outputStream.use { stream -> stream.write(bytes) }
            }
            val status = conexao.responseCode
            val stream = if (status in 200..299) conexao.inputStream else conexao.errorStream
            val buffer = ByteArrayOutputStream()
            stream?.use {
                val bloco = ByteArray(8192)
                while (true) {
                    val n = it.read(bloco); if (n < 0) break
                    if (buffer.size() + n > 8 * 1024 * 1024) throw IOException("Resposta maior que o limite de consulta.")
                    buffer.write(bloco, 0, n)
                }
            }
            return RespostaConta(status, buffer.toString(Charsets.UTF_8.name()))
        } finally { conexao.disconnect() }
    }
}
