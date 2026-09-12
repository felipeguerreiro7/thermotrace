package com.thermotrace.app.nfc

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

/**
 * Decodificação da sessão de temperatura e planejamento da ativação.
 *
 * Este arquivo concentra as três coisas que a v0.1 não fazia:
 *  - transformar a resposta do SDK numa série COM timestamps (D3);
 *  - preservar a base temporal nominal da etiqueta;
 *  - calcular quantos registros a etiqueta precisa comportar (D8).
 *
 * Também resolve duas armadilhas descobertas comparando os SDKs Android e iOS
 * do fabricante: `TimeBase` e `canonicalUid`.
 */

// =====================================================================
// Modos de armazenamento e capacidade
// =====================================================================

/**
 * Modos suportados pelo chip. As capacidades vêm das guardas do próprio SDK
 * (`NFCUtils.startLogging`, linhas 539-548): o SDK recusa o START se
 * `loggingCount` passar destes tetos.
 */
enum class StorageMode(
    /** Valor gravado no registrador do chip e devolvido pelo SDK. */
    val sdkMode: Int,
    /** Seletor aceito por GeneralNFC.switchStorageMode(). */
    val configurationMode: Int,
    val capacity: Int,
    val resolutionC: Double,
) {
    /** 4 bytes por amostra. Resolução plena. Padrão. */
    NORMAL(3, 0, 4_864, 0.25),

    /** 4 bytes para 2 amostras. */
    RAW(7, 2, 9_728, 0.25),

    /** 4 bytes para 3 amostras. Perde resolução; útil em viagens longas. */
    COMPRESSED(1, 1, 14_592, 0.5),

    /**
     * "limit2": 4 bytes para 8 amostras, mas grava apenas a FAIXA em que a
     * amostra caiu, não o valor. Não serve para laudo térmico — não use sem
     * decisão explícita do cliente.
     */
    LIMIT2(6, 3, 38_912, Double.NaN);

    companion object {
        /** Menor modo que cobre `samples` sem perder resolução desnecessariamente. */
        fun smallestFor(samples: Int): StorageMode? = when {
            samples <= NORMAL.capacity     -> NORMAL
            samples <= RAW.capacity        -> RAW
            samples <= COMPRESSED.capacity -> COMPRESSED
            else                           -> null
        }
    }
}

/**
 * Convenção de base de tempo.
 *
 * **Divergência real entre os SDKs do fabricante — verificada no código:**
 *
 * - Android (`InstructMap` case 22): grava `System.currentTimeMillis()/1000`,
 *   ou seja, o instante do START.
 * - iOS (`NFCTagHelper.m`, `CMD_SET_START_TIME`): grava `now + delay*60`,
 *   ou seja, o instante em que a primeira janela de medição começa.
 *
 * A MESMA etiqueta, ativada por um iPhone ou por um Android, guarda um
 * `startTime` com significado diferente. Sem registrar qual convenção foi
 * usada, a série sai deslocada de `delay` minutos — silenciosamente.
 *
 * Por isso a convenção é gravada na sessão e viaja para o banco.
 */
enum class TimeBase {
    /** startTime = instante do START. Some `delay` para chegar à 1ª janela. */
    START_INSTANT,

    /** startTime = instante da 1ª janela. `delay` já está embutido. */
    FIRST_WINDOW;

    companion object {
        val ANDROID_NFCINSTRUCT = START_INSTANT
        val IOS_FMTEMPERATURE = FIRST_WINDOW
    }
}

// =====================================================================
// Plano de ativação
// =====================================================================

data class ActivationPlan(
    val delayMinutes: Int,
    val intervalSeconds: Int,
    val loggingCount: Int,
    val minC: Int,
    val maxC: Int,
    val storageMode: StorageMode,
    val timeBase: TimeBase = TimeBase.ANDROID_NFCINSTRUCT,
) {
    /** Autonomia de gravação em horas, para exibir ao operador antes do START. */
    val coverageHours: Double get() = loggingCount * intervalSeconds / 3600.0

    companion object {
        /**
         * Substitui o `loggingCount = 1000` fixo da v0.1 (defeito D8).
         *
         * @param plannedDurationHours duração prevista do transporte
         * @param safetyFactor folga para atraso. 1.5 = 50% de margem.
         */
        fun forShipment(
            plannedDurationHours: Double,
            intervalSeconds: Int = 600,
            minC: Int,
            maxC: Int,
            safetyFactor: Double = 1.5,
            timeBase: TimeBase = TimeBase.ANDROID_NFCINSTRUCT,
        ): Result<ActivationPlan> {
            require(minC < maxC) { "Faixa térmica inválida: $minC..$maxC" }

            val needed = ceil(plannedDurationHours * safetyFactor * 3600.0 / intervalSeconds).toInt()
            // O app original nao troca o modo de memoria durante o START. Essa
            // configuracao e uma operacao fisica separada na tela Settings.
            // Para manter o fluxo operacional com um comando por aproximacao,
            // o plano automatico usa somente o modo NORMAL de fabrica.
            if (needed > StorageMode.NORMAL.capacity) {
                return Result.failure(
                    IllegalArgumentException(
                        "O modo normal comporta até ${StorageMode.NORMAL.capacity} registros, " +
                            "mas este plano precisa de $needed. Aumente o intervalo " +
                            "(atual ${intervalSeconds}s) ou divida a viagem em duas etiquetas."
                    )
                )
            }

            return Result.success(
                ActivationPlan(
                    delayMinutes = 0,
                    intervalSeconds = intervalSeconds,
                    loggingCount = needed,
                    minC = minC,
                    maxC = maxC,
                    storageMode = StorageMode.NORMAL,
                    timeBase = timeBase,
                )
            )
        }
    }
}

// =====================================================================
// Identidade da etiqueta
// =====================================================================

object TagIdentity {
    /**
     * Forma canônica do UID.
     *
     * **Necessário porque Android e iOS entregam ordens de byte diferentes
     * para a MESMA etiqueta ISO 15693.** A prova está no código do próprio
     * fabricante: `NFCTagObject.m` inverte os 8 bytes de
     * `NFCISO15693Tag.identifier` justamente para casar com a string que o
     * app Android produz. `GeneralNFC.getUid()` no Android não inverte nada.
     *
     * Se o backend receber as duas formas, a chave única `tag.nfc_uid` do
     * banco quebra e a verificação QR↔UID passa a rejeitar etiquetas válidas.
     * Canonicalize sempre na borda, antes de comparar ou persistir.
     */
    fun canonical(uid: String): String =
        uid.replace(Regex("[^0-9A-Fa-f]"), "").uppercase()

    fun matches(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        return canonical(a) == canonical(b)
    }
}

// =====================================================================
// Decodificação da resposta
// =====================================================================

/**
 * Layout de `GeneralNFC.getLoggingResult()`:
 *
 * ```
 *  [0]  estado         0=não iniciou 1=medindo 2=parada anormal 3=parada normal
 *  [1]  epoch de início (gravado pelo CELULAR — ver TimeBase)
 *  [2]  quantidade programada
 *  [3]  quantidade medida
 *  [4]  delay (minutos)
 *  [5]  intervalo (segundos)
 *  [6]  mínima registrada        [7]  máxima registrada
 *  [8]  limite mínimo            [9]  limite máximo
 *  [10] contagem abaixo          [11] contagem acima
 *  [12..] pontos, como "12.5" ou "12.5:1" quando isFiled=true
 * ```
 */
data class SessionHeader(
    val statusCode: String,
    val startEpoch: Long,
    val plannedCount: Int,
    val measuredCount: Int,
    val delayMinutes: Int,
    val intervalSeconds: Int,
    val recordedMinC: Double,
    val recordedMaxC: Double,
    val limitMinC: Double,
    val limitMaxC: Double,
    val belowCount: Int,
    val aboveCount: Int,
) {
    val statusLabel: String get() = when (statusCode) {
        "0" -> "Aguardando início (delay não venceu)"
        "1" -> "Monitorando"
        "2" -> "Encerrada de forma anormal"
        "3" -> "Encerrada normalmente"
        else -> "Estado desconhecido ($statusCode)"
    }
}

data class Sample(
    val index: Int,
    val epochSeconds: Long,
    val temperatureC: Double,
    val fieldFlag: Int?,
)

data class DecodedSession(
    val header: SessionHeader,
    val samples: List<Sample>,
    /**
     * Nome legado: diferença entre horário da coleta e último ponto nominal.
     * Inclui espera após parada e erro do celular; não estima deriva do sensor.
     */
    val rtcDriftSeconds: Long,
    val effectiveIntervalSeconds: Double,
    val timestampsCorrected: Boolean,
    val raw: List<String>,
    /**
     * Confronto entre o que a etiqueta declara no cabeçalho e o que a série
     * decodificada diz. Ver [Reconciliacao].
     */
    val reconciliacao: Reconciliacao,
)

object SessionDecoder {
    const val VERSION = "fm13dt160-decoder-1.1"

    /** O horário da coleta não mede deriva do RTC: uma etiqueta parada pode
     * ser lida dias depois. Preserva a base nominal fornecida pelo fabricante.
     * Falhas de estrutura são devolvidas ao chamador antes de gerar gráfico.
     */
    fun decode(
        response: List<String>,
        deviceReadAtMillis: Long,
        timeBase: TimeBase,
    ): Result<DecodedSession> = runCatching {
        require(response.size >= 12) { "Histórico incompleto: menos de 12 campos." }
        fun integer(i: Int): Int = response[i].toIntOrNull()
            ?: throw IllegalArgumentException("Campo $i ilegível no histórico.")
        fun finite(i: Int): Double = response[i].toDoubleOrNull()?.takeIf { it.isFinite() }
            ?: throw IllegalArgumentException("Campo $i inválido no histórico.")
        val header = SessionHeader(
            statusCode = response[0],
            startEpoch = response[1].toLongOrNull()
                ?: throw IllegalArgumentException("Horário de início ilegível."),
            plannedCount = integer(2), measuredCount = integer(3),
            delayMinutes = integer(4), intervalSeconds = integer(5),
            recordedMinC = response[6].toDoubleOrNull() ?: Double.NaN,
            recordedMaxC = response[7].toDoubleOrNull() ?: Double.NaN,
            limitMinC = finite(8), limitMaxC = finite(9),
            belowCount = integer(10), aboveCount = integer(11),
        )
        require(header.startEpoch > 0 && header.delayMinutes >= 0) { "Base de tempo inválida." }
        require(header.intervalSeconds > 0) { "Intervalo inválido na etiqueta." }
        require(header.plannedCount > 0 && header.measuredCount in 0..header.plannedCount) {
            "Contagem de amostras inválida."
        }
        require(header.limitMinC < header.limitMaxC) { "Faixa térmica inválida." }
        require(header.belowCount >= 0 && header.aboveCount >= 0) { "Contagem de excursões inválida." }
        val points = response.drop(12)
        require(points.size == header.measuredCount) {
            "Histórico incompleto: ${points.size} de ${header.measuredCount} medições. Aproxime novamente."
        }
        // A primeira amostra usa START + delay no Android; iOS já inclui delay.
        val firstEpoch = Math.addExact(header.startEpoch,
            if (timeBase == TimeBase.START_INSTANT) header.delayMinutes * 60L else 0L)
        val samples = points.mapIndexed { i, raw ->
            val parts = raw.split(':')
            val temperature = parts[0].toDoubleOrNull()
            require(temperature != null && temperature.isFinite()) { "Temperatura inválida na amostra $i." }
            require(parts.size <= 2 && (parts.size == 1 || parts[1].toIntOrNull() != null)) {
                "Indicador inválido na amostra $i."
            }
            Sample(i, Math.addExact(firstEpoch, i.toLong() * header.intervalSeconds),
                temperature, parts.getOrNull(1)?.toInt())
        }
        val nominalEnd = samples.lastOrNull()?.epochSeconds ?: firstEpoch
        val reconciliacao = Reconciliacao.comparar(response, samples.map { it.temperatureC })
        DecodedSession(
            header = header, samples = samples,
            // Campo legado: diferença coleta–último ponto, NÃO deriva medida.
            rtcDriftSeconds = Math.subtractExact(deviceReadAtMillis / 1000L, nominalEnd),
            effectiveIntervalSeconds = header.intervalSeconds.toDouble(),
            timestampsCorrected = false, raw = response,
            reconciliacao = reconciliacao,
        )
    }
}

// =====================================================================
// Payload para o backend (outbox offline)
// =====================================================================

/**
 * O celular do operador trabalha sem sinal (doca, câmara fria, galpão).
 * A leitura vai para uma fila local e é reenviada até o servidor confirmar.
 *
 * `ingestKey` é a chave de idempotência: reenviar não duplica.
 * Bate 1:1 com `tt.ingest_tag_read(...)` em `db/02_funcoes_auditoria.sql`.
 */
object OutboxPayload {

    fun ingestKey(installId: String, sessionId: String, attemptSeq: Int): String =
        "dev:$installId:sess:$sessionId:read:$attemptSeq"

    fun build(
        ingestKey: String,
        sessionId: String,
        nfcUid: String,
        purpose: String,
        decoded: DecodedSession,
        timeBase: TimeBase,
        deviceReadAtMillis: Long,
        deviceClockSkewMillis: Long?,
        voltageV: Double?,
        instantTempC: Double?,
        sdkVersion: String,
    ): JSONObject = JSONObject().apply {
        put("ingest_key", ingestKey)
        put("session_id", sessionId)
        // Canonicalizado: sem isto, leituras iOS e Android da mesma etiqueta
        // chegam ao servidor com UIDs diferentes.
        put("nfc_uid", TagIdentity.canonical(nfcUid))
        put("read_purpose", purpose)
        put("device_read_at", isoUtc(deviceReadAtMillis))
        put("device_clock_skew_ms", deviceClockSkewMillis ?: JSONObject.NULL)
        put("sdk_version", sdkVersion)
        // Sem isto o servidor não sabe se startEpoch já inclui o delay.
        put("time_base", timeBase.name)
        // Evidência bruta — NUNCA descartar (princípio P2 do banco).
        put("raw_sdk_response", JSONArray(decoded.raw))
        put("rtc_drift_seconds", decoded.rtcDriftSeconds) // campo legado
        put("rtc_drift_estimated", false)
        put("read_lag_seconds", decoded.rtcDriftSeconds)
        put("decoder_version", SessionDecoder.VERSION)
        put("timestamps_corrected", decoded.timestampsCorrected)
        put("effective_interval_seconds", decoded.effectiveIntervalSeconds)
        put("voltage_v", voltageV ?: JSONObject.NULL)
        put("instant_temp_c", instantTempC ?: JSONObject.NULL)
        put("first_sample_epoch", decoded.samples.firstOrNull()?.epochSeconds ?: JSONObject.NULL)
        put("temperatures_c", JSONArray().apply {
            decoded.samples.forEach { put(it.temperatureC) }
        })
        put("field_flags", JSONArray().apply {
            decoded.samples.forEach { put(it.fieldFlag ?: JSONObject.NULL) }
        })
    }

    private fun isoUtc(millis: Long): String {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        f.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return f.format(java.util.Date(millis))
    }
}
