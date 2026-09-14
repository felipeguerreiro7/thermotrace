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
    fun enviarIdempotente(endereco: String, caminho: String, token: String, corpo: String, chave: String): RespostaConta =
        throw UnsupportedOperationException("Transporte não suporta envio idempotente.")
}

class TransporteHttpsConta : TransporteConta {
    override fun enviar(endereco: String, caminho: String, metodo: String, token: String?, corpo: String?): RespostaConta {
        return executar(endereco, caminho, metodo, token, corpo, null)
    }
    override fun enviarIdempotente(endereco: String, caminho: String, token: String, corpo: String, chave: String): RespostaConta =
        executar(endereco, caminho, "POST", token, corpo, chave)

    private fun executar(endereco: String, caminho: String, metodo: String, token: String?, corpo: String?, chave: String?): RespostaConta {
        val base = EnderecoConta.normalizar(endereco)
        require(caminho.matches(Regex("/(auth/[a-z]+|remessas[^#]*|dispositivos|etiquetas/localizar\\?uid=[0-9A-F]+|volumes/[0-9a-f-]{36}/sessoes|sessoes/[0-9a-f-]{36}/leituras)")))
        chave?.let { require(it.matches(Regex("[A-Za-z0-9:_-]{1,128}"))) }
        val conexao = URL("$base/api/v1$caminho").openConnection() as HttpURLConnection
        try {
            conexao.instanceFollowRedirects = false // Nunca encaminhar credenciais a outro destino.
            conexao.requestMethod = metodo
            conexao.connectTimeout = 10_000; conexao.readTimeout = 15_000
            conexao.useCaches = false
            conexao.setRequestProperty("Accept", "application/json")
            chave?.let { conexao.setRequestProperty("Idempotency-Key", it) }
            token?.let {
                require(it.matches(Regex("[A-Za-z0-9_.-]{1,8192}")))
                conexao.setRequestProperty("Authorization", "Bearer $it")
            }
            corpo?.let {
                val bytes = it.toByteArray(Charsets.UTF_8); require(bytes.size <= if (chave != null) 1_048_576 else 16_384)
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
