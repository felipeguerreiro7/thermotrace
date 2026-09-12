package com.thermotrace.app.ui.components

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.thermotrace.app.data.prefs.Ajustes

/**
 * Retorno tátil e sonoro das operações NFC.
 *
 * **Por que isto existe:** encostar o celular numa caixa fechada, num pallet
 * ou dentro de câmara fria significa que a tela está virada para o outro lado
 * ou embaçada. O operador não vê nem "Etiqueta lida" nem "Etiqueta errada" —
 * ele descobre o resultado quando afasta o telefone, que é tarde demais, porque
 * afastar no meio da gravação é exatamente o que corrompe a ativação.
 *
 * Três sinais, deliberadamente distintos ao toque:
 *
 *   detectada → um toque curto (a etiqueta entrou no campo, NÃO afaste)
 *   sucesso   → dois toques + bipe agudo (pode afastar)
 *   falha     → um pulso longo + bipe grave (nada foi gravado)
 *
 * O operador aprende os três em meia hora e para de olhar a tela.
 */
class FeedbackOperador(
    context: Context,
    private val ajustes: () -> Ajustes,
) {

    private val appContext = context.applicationContext

    private val vibrador: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    // Criado sob demanda: um ToneGenerator vivo segura um canal de áudio, e
    // a maioria das sessões do app nunca emite som nenhum.
    private var gerador: ToneGenerator? = null

    private fun tocar(tom: Int, duracaoMs: Int) {
        if (!ajustes().feedbackSonoro) return
        runCatching {
            val g = gerador ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, VOLUME)
                .also { gerador = it }
            g.startTone(tom, duracaoMs)
        }
    }

    private fun vibrar(padrao: LongArray) {
        if (!ajustes().feedbackTatil) return
        val v = vibrador?.takeIf { it.hasVibrator() } ?: return
        runCatching {
            v.vibrate(VibrationEffect.createWaveform(padrao, -1))
        }
    }

    /** A etiqueta entrou no campo. Ainda não terminou nada — não afaste. */
    fun detectada() {
        vibrar(longArrayOf(0, 30))
        tocar(ToneGenerator.TONE_PROP_BEEP, 80)
    }

    /** A operação terminou e foi confirmada pela etiqueta. Pode afastar. */
    fun sucesso() {
        vibrar(longArrayOf(0, 45, 90, 45))
        tocar(ToneGenerator.TONE_PROP_ACK, 150)
    }

    /** Recusa ou erro. Nada foi gravado na etiqueta. */
    fun falha() {
        vibrar(longArrayOf(0, 350))
        tocar(ToneGenerator.TONE_SUP_ERROR, 400)
    }

    fun liberar() {
        runCatching { gerador?.release() }
        gerador = null
    }

    private companion object {
        /** 80 de 100: audível num galpão sem virar incômodo numa sala. */
        const val VOLUME = 80
    }
}
