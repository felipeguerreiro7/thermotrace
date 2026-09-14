package com.thermotrace.app.data.conta

import com.thermotrace.app.data.db.*
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

data class VolumeEnvio(val id: String, val sequencia: Int, val identidade: String?)
data class DestinoEnvio(val id: String, val codigo: String, val destinatario: String,
    val intervalo: Int, val minimo: java.math.BigDecimal, val maximo: java.math.BigDecimal,
    val volumes: List<VolumeEnvio>)

object ContratoEnvio {
    fun hash(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    fun eventoInicio(sessao: String) = UUID.nameUUIDFromBytes("tt-start-1:$sessao".toByteArray(Charsets.UTF_8)).toString()
    fun destino(raw: String, empresa: String): DestinoEnvio {
        val o = JSONObject(raw)
        require(JsonConta.uuid(o,"empresa_id") == empresa) { "Carga de outra empresa." }
        check(o.getString("status") !in listOf("cancelada", "concluida")) { "Carga online fechada ou cancelada." }
        val p = o.getJSONObject("criterio").getJSONObject("perfil")
        val lista = o.getJSONArray("volumes"); require(lista.length() <= 200)
        return DestinoEnvio(JsonConta.uuid(o,"id"), JsonConta.texto(o,"codigo"),
            JsonConta.texto(o,"destinatario_nome"), o.getInt("intervalo_segundos"),
            p.getString("min_c").toBigDecimal(), p.getString("max_c").toBigDecimal(),
            (0 until lista.length()).map { lista.getJSONObject(it) }.filter { it.getBoolean("monitorado") }.map {
                VolumeEnvio(JsonConta.uuid(it,"id"), it.getInt("sequencia"),
                    if(it.isNull("identidade")) null else it.getString("identidade")) })
    }
    private fun evidencia(v: VinculoEnvio, evento: String, em: Long, sdk: String, decoder: String, bruto: JSONArray): JSONObject {
        require(bruto.length() in 1..65547) { "Resposta original ausente ou fora do limite de envio." }
        var bytes = 0
        for (i in 0 until bruto.length()) {
            val s = bruto.get(i); require(s is String && s.length <= 131072)
            bytes += s.toByteArray(Charsets.UTF_8).size
        }
        require(bytes <= 262144) { "Resposta original excede o limite do servidor." }
        require(sdk.length in 1..64 && decoder.length in 1..64)
        require(Instant.ofEpochMilli(em).atOffset(java.time.ZoneOffset.UTC).year in 2000..2100)
        return JSONObject().put("evento_id",evento).put("dispositivo_id",v.dispositivoId)
            .put("lida_em",Instant.ofEpochMilli(em).toString()).put("uid_canonico",v.uid)
            .put("versao_sdk",sdk).put("versao_decodificador",decoder)
            .put("resposta_bruta",bruto).put("origem","declaracao_android")
    }
    fun inicio(s: SessaoEntity, v: VinculoEnvio, origem: OutboxEntity): PedidoEnvio {
        check(s.ativacaoConfirmada && s.baseDeTempo == "START_INSTANT" && s.plataformaAtivacao == "android") {
            "Ativação sem confirmação ou base de tempo incompatível."
        }
        val o = JSONObject(origem.corpoJson)
        check(o.getString("sessao_id") == s.id && o.getString("nfc_uid") == v.uid)
        val bruto = o.optJSONArray("resposta_start")
        check(bruto != null && bruto.length() > 0) { "Ativação antiga sem resposta original de START. Mantenha o laudo local; não recrie a evidência." }
        val evento = eventoInicio(s.id)
        val body = JSONObject().put("sessao_id",s.id).put("etiqueta_id",v.etiquetaOnline)
            .put("epoch_inicio_etiqueta",s.epochInicioEtiqueta).put("delay_minutos",s.delayMinutos)
            .put("intervalo_segundos",s.intervaloSegundos).put("quantidade_planejada",s.quantidadePlanejada)
            .put("min_configurado_c",java.math.BigDecimal.valueOf(s.minConfiguradoC).toPlainString())
            .put("max_configurado_c",java.math.BigDecimal.valueOf(s.maxConfiguradoC).toPlainString())
            .put("ativacao_confirmada",true).put("evidencia",evidencia(v,evento,s.inicioDispositivoMillis,
                o.getString("versao_sdk"),"start-sem-decodificacao-1",bruto)).toString()
        return pedido(evento,s.id,origem.chaveIdempotencia,"ativacao","/volumes/${v.volumeOnline}/sessoes",body,0)
    }
    fun leitura(s: SessaoEntity, v: VinculoEnvio, l: LeituraEntity, origem: OutboxEntity): PedidoEnvio {
        require(l.tipo.name in listOf("CHECKPOINT","FINAL") && l.sessaoId == s.id)
        val o = JSONObject(origem.corpoJson)
        check(o.getString("session_id") == s.id && o.getString("nfc_uid") == v.uid)
        // O JSON transacional mantém fronteiras de strings, inclusive o separador legado do Room.
        val tipo = l.tipo.name.lowercase()
        val body = evidencia(v,l.id,l.lidaEmMillis,l.versaoSdk,l.versaoDecodificador,
            o.getJSONArray("raw_sdk_response")).put("tipo",tipo)
            .put("epoch_inicio_etiqueta",s.epochInicioEtiqueta).put("intervalo_relatado_s",l.intervaloRelatado).toString()
        return pedido(l.id,s.id,l.chaveIdempotencia,tipo,"/sessoes/${s.id}/leituras",body,l.lidaEmMillis)
    }
    private fun pedido(id: String, sessao: String, local: String, tipo: String, caminho: String, corpo: String, ordem: Long): PedidoEnvio {
        require(corpo.toByteArray(Charsets.UTF_8).size <= 1048576)
        return PedidoEnvio(id,sessao,local,tipo,caminho,corpo,hash(corpo),"tt-envio-$id",ordem)
    }
    fun conferirRecibo(raw: String, p: PedidoEnvio, v: VinculoEnvio) {
        val o = JSONObject(raw)
        for ((campo,esperado) in mapOf("evento_id" to p.eventoId,"sessao_id" to p.sessaoId,
            "empresa_id" to v.empresaId,"volume_id" to v.volumeOnline,"remessa_id" to v.remessaOnline)) {
            require(JsonConta.uuid(o,campo) == esperado) { "Recibo não corresponde à coleta enviada." }
        }
        JsonConta.uuid(o,"leitura_id")
        require(o.getString("tipo") == p.tipo && o.getString("origem") == "declaracao_android")
        require(o.getLong("ordem_recebimento") > 0)
        Instant.parse(o.getString("recebida_em_servidor"))
        for(campo in listOf("hash_payload","hash_encadeado")) require(o.getString(campo).matches(Regex("[0-9a-f]{64}")))
        if(!o.isNull("hash_anterior")) require(o.getString("hash_anterior").matches(Regex("[0-9a-f]{64}")))
        require(o.getString("algoritmo_integridade") == "tt-evidencia-1" &&
            o.getString("situacao") == "evidencia_recebida_sem_validacao_fisica" &&
            o.getString("stop_fisico") == "nao_confirmado_pelo_servidor")
    }
}
