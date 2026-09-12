package com.thermotrace.app.data.conta

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.net.URI
import java.time.Instant
import java.util.UUID

object EnderecoConta {
    fun normalizar(valor: String): String {
        val uri = runCatching { URI(valor.trim()) }.getOrNull()
            ?: throw IllegalArgumentException("Informe um endereço HTTPS válido.")
        require(uri.scheme?.lowercase() == "https" && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.port in -1..65535 && uri.port != 0 && uri.rawPath in listOf("", "/", "/api/v1", "/api/v1/")) {
            "Use o endereço HTTPS fornecido pela ThermoTrace, sem usuário, senha ou parâmetros."
        }
        return URI("https", null, uri.host.lowercase(), uri.port, null, null, null).toASCIIString()
    }
}

data class SessaoConta(
    val endereco: String, val acesso: String, val renovacao: String,
    val usuarioId: String, val empresaId: String, val renovacaoPendente: Boolean = false,
) {
    override fun toString() = "SessaoConta([protegida])"
}

interface CofreConta {
    fun ler(): SessaoConta?
    fun gravar(sessao: SessaoConta)
    fun limpar()
}

data class IdentidadeConta(val id: String, val empresaId: String, val nome: String, val empresa: String, val papel: String)
data class CargaConta(val id: String, val codigo: String, val destinatario: String, val status: String, val criadaEm: Instant)
data class DocumentoConta(val tipo: String, val numero: String)
data class DetalheCargaConta(val carga: CargaConta, val faixa: String?, val intervaloSegundos: Int,
    val documentos: List<DocumentoConta>, val quantidadeVolumes: Int, val versaoPerfil: Int?)
data class PaginaCargas(val cargas: List<CargaConta>, val haMais: Boolean)
class LoginNecessario(message: String = "Entre novamente para acessar sua conta.") : Exception(message)

object JsonConta {
    fun texto(o: JSONObject, campo: String, maximo: Int = 2000): String {
        val value = o.get(campo)
        require(value is String && value.isNotBlank() && value.length <= maximo) { "Resposta inválida do servidor." }
        return value
    }
    fun uuid(o: JSONObject, campo: String): String {
        val original = texto(o, campo, 36)
        val normalizado = UUID.fromString(original).toString()
        require(original.equals(normalizado, ignoreCase = true))
        return normalizado
    }
    private fun inteiro(o: JSONObject, campo: String, minimo: Int, maximo: Int): Int {
        val v = o.get(campo)
        require(v is Number)
        val n = BigDecimal(v.toString()).intValueExact()
        require(n in minimo..maximo)
        return n
    }
    fun identidade(raw: String): IdentidadeConta = JSONObject(raw).let {
        val papel = texto(it, "papel", 16)
        require(papel in listOf("operador", "gestor", "admin"))
        IdentidadeConta(uuid(it, "id"), uuid(it, "empresa_id"), texto(it, "nome"), texto(it, "empresa"), papel)
    }
    fun tokens(raw: String): Pair<String, String> = JSONObject(raw).let {
        require(texto(it, "token_type", 16).lowercase() == "bearer")
        val a = texto(it, "access_token", 8192)
        val r = texto(it, "refresh_token", 128)
        require(a.matches(Regex("[A-Za-z0-9_.-]+")) && r.matches(Regex("[A-Za-z0-9_-]{40,128}")))
        a to r
    }
    private fun carga(o: JSONObject, empresa: String): CargaConta {
        if (uuid(o, "empresa_id") != empresa) throw LoginNecessario("Sua empresa de acesso mudou. Entre novamente.")
        return CargaConta(uuid(o, "id"), texto(o, "codigo", 64), texto(o, "destinatario_nome"),
            texto(o, "status", 64), Instant.parse(texto(o, "criado_em", 64)))
    }
    fun pagina(raw: String, empresa: String, busca: Boolean): PaginaCargas {
        val obj = if (busca) JSONObject(raw) else null
        val lista = obj?.getJSONArray("candidatas") ?: JSONArray(raw)
        require(lista.length() <= 100)
        return PaginaCargas((0 until lista.length()).map { carga(lista.getJSONObject(it), empresa) },
            obj?.getBoolean("ha_mais") ?: (lista.length() == 30))
    }
    fun detalhe(raw: String, empresa: String): DetalheCargaConta {
        val o = JSONObject(raw)
        require(o.has("criterio"))
        val perfil = if (o.isNull("criterio")) null else o.getJSONObject("criterio").getJSONObject("perfil")
        val faixa = perfil?.let {
            val min = BigDecimal(texto(it, "min_c", 32)); val max = BigDecimal(texto(it, "max_c", 32))
            require(min < max)
            "${min.stripTrailingZeros().toPlainString().replace('.', ',')} a ${max.stripTrailingZeros().toPlainString().replace('.', ',')} °C"
        }
        val docs = o.getJSONArray("documentos"); val volumes = o.getJSONArray("volumes")
        require(docs.length() <= 100 && volumes.length() <= 200)
        val intervalo = inteiro(o, "intervalo_segundos", 1, 86_400)
        return DetalheCargaConta(carga(o, empresa), faixa, intervalo,
            (0 until docs.length()).map { val d = docs.getJSONObject(it); DocumentoConta(texto(d, "tipo", 24), texto(d, "numero", 64)) },
            volumes.length(), perfil?.let { inteiro(it, "versao", 1, Int.MAX_VALUE) })
    }
}
