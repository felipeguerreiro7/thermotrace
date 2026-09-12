package com.thermotrace.app.nfc

internal enum class TagRtcStatus { LOGGING, IDLE, UNKNOWN }

/** A ausência de resposta não confirma que a etiqueta parou. */
internal fun TagRtcStatus.confirmedLogging(): Boolean = when (this) {
    TagRtcStatus.LOGGING -> true
    TagRtcStatus.IDLE -> false
    TagRtcStatus.UNKNOWN -> throw IllegalStateException(
        "Não foi possível confirmar o estado. Afaste e aproxime a etiqueta novamente."
    )
}
