package com.thermotrace.app.domain

import java.time.Duration
import java.time.Instant

/**
 * "A etiqueta está ativa?"
 *
 * A pergunta parece simples e não é. Sem rádio, a única prova de que o logger
 * está rodando é encostar o telefone e ler o bit de status do chip. Fora desse
 * instante, o app só pode *presumir*.
 *
 * Tratar presunção como confirmação é o jeito mais rápido de perder a
 * confiança do cliente: a tela diz "ativa", a etiqueta morreu no dia anterior,
 * e o laudo chega vazio. Por isso o estado sempre carrega junto **quando foi a
 * última verificação real** — o mesmo raciocínio da "última sincronização" da
 * especificação (§8.3).
 */
enum class Confianca(val rotulo: String) {
    /** Bit de status lido agora, nesta aproximação. */
    CONFIRMADA("Confirmada agora"),
    /** Confirmada em algum momento no passado; presumimos continuidade. */
    PRESUMIDA("Presumida"),
    /** Nunca houve confirmação. */
    DESCONHECIDA("Não verificada"),
}

enum class SituacaoEtiqueta(val rotulo: String) {
    NUNCA_ATIVADA("Não ativada"),
    REGISTRANDO("Registrando"),
    START_NAO_CONFIRMADO("START sem confirmação"),
    PARADA("Parada"),
    CAPACIDADE_ESGOTADA("Memória cheia"),
    BATERIA_BAIXA("Bateria baixa"),
    ENCERRADA("Encerrada"),
}

data class EstadoEtiqueta(
    val situacao: SituacaoEtiqueta,
    val confianca: Confianca,
    /** Quando o bit de status foi lido de verdade pela última vez. */
    val verificadoEm: Instant?,
    val ativadaEm: Instant?,
    val intervaloSegundos: Int,
    val capacidadeProgramada: Int,
    /** Quantos registros a etiqueta já deveria ter, pelo relógio. */
    val registrosEsperados: Int,
    /** Quantos foram efetivamente baixados na última leitura. */
    val registrosBaixados: Int?,
    val tensaoV: Double?,
    val ultimaTemperaturaC: Double?,
) {
    val cheiaEm: Instant?
        get() = ativadaEm?.plusSeconds(capacidadeProgramada.toLong() * intervaloSegundos)

    val autonomiaRestante: Duration?
        get() = cheiaEm?.let {
            val d = Duration.between(Instant.now(), it)
            if (d.isNegative) Duration.ZERO else d
        }

    val ocupacao: Float
        get() = if (capacidadeProgramada <= 0) 0f
        else (registrosEsperados.toFloat() / capacidadeProgramada).coerceIn(0f, 1f)

    val desdeVerificacao: Duration?
        get() = verificadoEm?.let { Duration.between(it, Instant.now()) }

    /**
     * Se a última confirmação envelheceu demais, "presumida" já não é uma
     * afirmação defensável — vira sugestão de checkpoint.
     */
    val verificacaoEnvelhecida: Boolean
        get() = (desdeVerificacao?.toHours() ?: Long.MAX_VALUE) >= 24

    val precisaAtencao: Boolean
        get() = situacao in setOf(
            SituacaoEtiqueta.START_NAO_CONFIRMADO,
            SituacaoEtiqueta.PARADA,
            SituacaoEtiqueta.CAPACIDADE_ESGOTADA,
            SituacaoEtiqueta.BATERIA_BAIXA,
        ) || verificacaoEnvelhecida

    /** Frase pronta para a tela. Diz o estado E o quanto ele é confiável. */
    val resumo: String
        get() {
            val base = when (situacao) {
                SituacaoEtiqueta.NUNCA_ATIVADA -> "Etiqueta ainda não ativada"
                SituacaoEtiqueta.REGISTRANDO -> "Registrando a cada ${intervaloSegundos / 60} min"
                SituacaoEtiqueta.START_NAO_CONFIRMADO ->
                    "START aceito, mas a etiqueta não confirmou o registro"
                SituacaoEtiqueta.PARADA -> "A etiqueta não está registrando"
                SituacaoEtiqueta.CAPACIDADE_ESGOTADA ->
                    "Memória cheia — parou de gravar novos pontos"
                SituacaoEtiqueta.BATERIA_BAIXA -> "Bateria abaixo do mínimo de operação"
                SituacaoEtiqueta.ENCERRADA -> "Monitoramento encerrado"
            }
            val quando = when (confianca) {
                Confianca.CONFIRMADA -> "confirmado agora"
                Confianca.PRESUMIDA -> desdeVerificacao?.let {
                    "última verificação há ${RegrasTermicas.formatarDuracao(it.seconds)}"
                } ?: "sem verificação recente"
                Confianca.DESCONHECIDA -> "nunca verificado"
            }
            return "$base · $quando"
        }

    companion object {
        val NAO_ATIVADA = EstadoEtiqueta(
            situacao = SituacaoEtiqueta.NUNCA_ATIVADA,
            confianca = Confianca.DESCONHECIDA,
            verificadoEm = null, ativadaEm = null,
            intervaloSegundos = 600, capacidadeProgramada = 0,
            registrosEsperados = 0, registrosBaixados = null,
            tensaoV = null, ultimaTemperaturaC = null,
        )

        const val TENSAO_MINIMA_V = 1.40

        /**
         * Deriva o estado a partir do que está no banco mais, opcionalmente,
         * uma leitura de status feita agora.
         *
         * @param registrandoAgora resultado do bit de status, quando acabamos
         *        de encostar o telefone. `null` significa que não verificamos —
         *        e é justamente essa distinção que a tela precisa mostrar.
         */
        fun derivar(
            ativadaEm: Instant?,
            ativacaoConfirmada: Boolean,
            encerrada: Boolean,
            intervaloSegundos: Int,
            capacidadeProgramada: Int,
            registrosBaixados: Int?,
            tensaoV: Double?,
            ultimaTemperaturaC: Double?,
            registrandoAgora: Boolean?,
            /** Resultado da última verificação gravada, seja quando for. */
            registrandoNaUltimaVerificacao: Boolean? = null,
            verificadoEm: Instant?,
        ): EstadoEtiqueta {
            if (ativadaEm == null) return NAO_ATIVADA
            // O que sabemos sobre o registro: preferimos o de agora; na falta,
            // o último gravado. A confiança abaixo registra qual dos dois foi.
            val registrando = registrandoAgora ?: registrandoNaUltimaVerificacao

            val decorrido = Duration.between(ativadaEm, Instant.now()).seconds.coerceAtLeast(0)
            val esperados = if (intervaloSegundos > 0)
                (decorrido / intervaloSegundos).toInt().coerceAtMost(capacidadeProgramada)
            else 0

            val situacao = when {
                encerrada -> SituacaoEtiqueta.ENCERRADA
                tensaoV != null && tensaoV < TENSAO_MINIMA_V -> SituacaoEtiqueta.BATERIA_BAIXA
                registrando == false -> SituacaoEtiqueta.PARADA
                capacidadeProgramada > 0 && esperados >= capacidadeProgramada ->
                    SituacaoEtiqueta.CAPACIDADE_ESGOTADA
                !ativacaoConfirmada && registrando != true ->
                    SituacaoEtiqueta.START_NAO_CONFIRMADO
                else -> SituacaoEtiqueta.REGISTRANDO
            }

            val confianca = when {
                registrandoAgora != null -> Confianca.CONFIRMADA
                verificadoEm != null -> Confianca.PRESUMIDA
                else -> Confianca.DESCONHECIDA
            }

            return EstadoEtiqueta(
                situacao = situacao,
                confianca = confianca,
                verificadoEm = verificadoEm,
                ativadaEm = ativadaEm,
                intervaloSegundos = intervaloSegundos,
                capacidadeProgramada = capacidadeProgramada,
                registrosEsperados = esperados,
                registrosBaixados = registrosBaixados,
                tensaoV = tensaoV,
                ultimaTemperaturaC = ultimaTemperaturaC,
            )
        }
    }
}
