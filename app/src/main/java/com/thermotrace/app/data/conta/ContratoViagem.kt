package com.thermotrace.app.data.conta

import com.thermotrace.app.domain.Medicao
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime

/**
 * TT-040: viagem consolidada que o servidor monta com TODAS as coletas recebidas da sessão
 * (deste e de outros celulares). Mesma regra de situação do painel de conformidade
 * (`backend/app/services/conformidade.py`), para o celular e o portal nunca se contradizerem.
 */
enum class SituacaoViagem(val rotulo: String, val explicacao: String) {
    CONFORME("Conforme", "Leitura final recebida e todas as medições dentro da faixa."),
    EXCURSAO("Com excursão", "Houve medição fora da faixa. Confira os trechos abaixo."),
    DIVERGENTE("Coletas divergentes", "Coletas da mesma etiqueta discordam entre si. Precisa de análise; nada foi corrigido."),
    EM_ANDAMENTO("Em andamento", "Dentro da faixa até agora, mas a leitura final ainda não chegou ao servidor."),
    SEM_DADOS("Sem dados", "O servidor ainda não tem coleta com medições desta sessão."),
}

data class ExcursaoViagem(val acima: Boolean, val inicio: Instant, val fim: Instant, val pontos: Int,
    val extremoC: Double, val duracaoNominalS: Long)

data class ResumoViagem(val quantidade: Int, val minimaC: Double, val maximaC: Double, val pontosForaDaFaixa: Int,
    val tempoForaNominalS: Long, val percentualNaFaixa: Double, val coletasConsideradas: Int, val incluiLeituraFinal: Boolean)

data class ViagemServidor(
    val sessaoId: String, val codigoCarga: String, val simulacao: Boolean,
    val minC: Double, val maxC: Double, val intervaloSegundos: Int,
    val integridadeCadeia: Boolean, val situacao: SituacaoViagem, val resumo: ResumoViagem?,
    val medicoes: List<Medicao>, val excursoes: List<ExcursaoViagem>, val coletasRecebidas: Int,
    val divergencias: Int, val avisos: List<String>, val hashProjecao: String,
)

object ContratoViagem {
    private const val MAX_PONTOS = 20_000

    /** Mesma tradução de falha nas duas telas que consultam a viagem. */
    fun mensagemDeFalha(e: Exception): String = when (e) {
        is LoginNecessario -> e.message ?: "Entre novamente para acessar sua conta."
        is FalhaHttpConta -> if (e.status == 404) "O servidor ainda não tem esta sessão. Envie as coletas primeiro."
            else "Não foi possível consultar a viagem (HTTP ${e.status})."
        is IllegalStateException -> e.message ?: "Não foi possível consultar a viagem."
        is IllegalArgumentException, is org.json.JSONException -> "Resposta do servidor fora do esperado por esta versão do app. Atualize o aplicativo."
        else -> "Sem conexão com o servidor. A viagem pode ser consultada depois; as coletas continuam no celular."
    }

    fun situacao(resumo: ResumoViagem?, divergente: Boolean): SituacaoViagem = when {
        resumo == null -> SituacaoViagem.SEM_DADOS
        divergente -> SituacaoViagem.DIVERGENTE
        resumo.pontosForaDaFaixa > 0 -> SituacaoViagem.EXCURSAO
        !resumo.incluiLeituraFinal -> SituacaoViagem.EM_ANDAMENTO
        else -> SituacaoViagem.CONFORME
    }

    /** Recusa resposta de outra sessão, fora do formato ou que se declare validada fisicamente. */
    fun ler(raw: String, sessaoEsperada: String): ViagemServidor {
        val o = JSONObject(raw)
        require(JsonConta.uuid(o, "sessao_id") == sessaoEsperada) { "Resposta do servidor não corresponde a esta sessão." }
        require(JsonConta.texto(o, "versao", 32).startsWith("tt-viagem-") && o.getString("tipo") == "viagem")
        // O servidor nunca libera carga; se um dia declarar o contrário, o app não deve repetir sem revisão.
        require(!o.getBoolean("validacao_fisica")) { "Resposta do servidor fora do contrato desta versão do app." }
        val hash = JsonConta.texto(o, "hash_projecao", 64)
        require(hash.matches(Regex("[0-9a-f]{64}")))
        val faixa = o.getJSONObject("faixa")
        val minC = numero(faixa, "min_c"); val maxC = numero(faixa, "max_c")
        require(minC < maxC)
        val intervalo = o.getInt("intervalo_segundos"); require(intervalo in 1..86_400)
        val conferencia = JsonConta.texto(o, "conferencia", 32)
        require(conferencia in setOf("coerente", "divergente", "nao_avaliavel"))

        val pontos = o.getJSONArray("pontos"); require(pontos.length() <= MAX_PONTOS)
        val medicoes = (0 until pontos.length()).map {
            val p = pontos.getJSONObject(it)
            Medicao(p.getInt("indice"), instante(p, "instante"), numero(p, "temperatura_c"))
        }
        require(medicoes.zipWithNext().all { (a, b) -> b.indice > a.indice }) { "Pontos da viagem fora de ordem." }

        val exc = o.getJSONArray("excursoes"); require(exc.length() <= MAX_PONTOS)
        val excursoes = (0 until exc.length()).map {
            val e = exc.getJSONObject(it)
            val tipo = JsonConta.texto(e, "tipo", 16); require(tipo == "acima" || tipo == "abaixo")
            ExcursaoViagem(tipo == "acima", instante(e, "inicio"), instante(e, "fim"), e.getInt("pontos"),
                numero(e, "extremo_c"), e.getLong("duracao_nominal_s"))
        }

        val resumo = if (o.isNull("resumo")) null else o.getJSONObject("resumo").let { r ->
            ResumoViagem(r.getInt("quantidade"), numero(r, "minima_c"), numero(r, "maxima_c"),
                r.getInt("pontos_fora_da_faixa"), r.getLong("tempo_fora_nominal_s"), numero(r, "percentual_na_faixa"),
                r.getInt("coletas_consideradas"), r.getBoolean("inclui_leitura_final"))
        }
        require(resumo == null || resumo.quantidade == medicoes.size) { "Resumo da viagem não confere com os pontos." }

        val avisos = o.getJSONArray("avisos")
        return ViagemServidor(
            sessaoId = sessaoEsperada, codigoCarga = JsonConta.texto(o, "codigo_carga", 64),
            simulacao = o.optString("origem") == "simulacao",
            minC = minC, maxC = maxC, intervaloSegundos = intervalo,
            integridadeCadeia = o.getBoolean("integridade_cadeia"),
            situacao = situacao(resumo, conferencia == "divergente"),
            resumo = resumo, medicoes = medicoes, excursoes = excursoes,
            coletasRecebidas = o.getJSONArray("coletas").length(),
            divergencias = o.getJSONArray("divergencias").length(),
            avisos = (0 until minOf(avisos.length(), 20)).map { avisos.getString(it).take(500) },
            hashProjecao = hash,
        )
    }

    private fun numero(o: JSONObject, campo: String): Double {
        val v = o.get(campo)
        require(v is Number) { "Resposta inválida do servidor." }
        return v.toDouble().also { require(it.isFinite()) }
    }

    private fun instante(o: JSONObject, campo: String): Instant = OffsetDateTime.parse(JsonConta.texto(o, campo, 64)).toInstant()
}
