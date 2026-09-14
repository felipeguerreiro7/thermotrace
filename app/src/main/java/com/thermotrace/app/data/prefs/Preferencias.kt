package com.thermotrace.app.data.prefs

import android.content.Context
import com.thermotrace.app.domain.PerfilTermico
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ajustes do aparelho.
 *
 * Nasceram espalhados: a URL do servidor morava dentro da tela de Ocorrências,
 * o piso de tensão era constante em três arquivos diferentes e o intervalo de
 * 10 min estava escrito à mão em quatro lugares. Quando o cliente pediu 5 min
 * não havia um lugar para mudar.
 *
 * O que NÃO entra aqui: qualquer coisa que a auditoria precise reconstruir.
 * O perfil térmico usado numa remessa fica gravado na sessão, não no ajuste —
 * mudar o padrão depois não pode reescrever o passado.
 */
data class Ajustes(
    val urlServidor: String? = null,
    val perfilPadrao: PerfilTermico = PerfilTermico.REFRIGERADO_2_8,
    val horasPadrao: Int = 72,
    val intervaloSegundos: Int = 600,
    val fatorSeguranca: Double = 1.5,
    val tensaoMinimaV: Double = 1.40,
    val feedbackTatil: Boolean = true,
    val feedbackSonoro: Boolean = true,
    /** Acender o LED da etiqueta que respondeu. Custa bateria da etiqueta. */
    val piscarLed: Boolean = true,
    val tutorialConcluido: Boolean = false,
) {
    companion object {
        /** Intervalos oferecidos na tela. Fora disso, a conta de cobertura mente. */
        val INTERVALOS_SEGUNDOS = listOf(300, 600, 900, 1800, 3600)
        val FATORES = listOf(1.2, 1.5, 2.0)
    }
}

class Preferencias(context: Context, arquivo: String = ARQUIVO) {

    private val prefs = context.getSharedPreferences(arquivo, Context.MODE_PRIVATE)

    private val _ajustes = MutableStateFlow(ler())
    val ajustes: StateFlow<Ajustes> = _ajustes.asStateFlow()

    val atual: Ajustes get() = _ajustes.value

    private fun ler() = Ajustes(
        urlServidor = prefs.getString(URL_SERVIDOR, null)
            ?.trim()?.trimEnd('/')?.takeIf { it.startsWith("https://") },
        perfilPadrao = PerfilTermico.porCodigo(
            prefs.getString(PERFIL, null) ?: PerfilTermico.REFRIGERADO_2_8.codigo
        ),
        horasPadrao = prefs.getInt(HORAS, 72),
        intervaloSegundos = prefs.getInt(INTERVALO, 600),
        // Arredondar na leitura não é preciosismo: `SharedPreferences` só
        // guarda Float, e 1.5f volta como 1.5000000596… em Double. O chip
        // "+50%" compara por igualdade e nunca mais apareceria selecionado.
        fatorSeguranca = duasCasas(prefs.getFloat(FATOR, 1.5f)),
        tensaoMinimaV = duasCasas(prefs.getFloat(TENSAO, 1.40f)),
        feedbackTatil = prefs.getBoolean(TATIL, true),
        feedbackSonoro = prefs.getBoolean(SONORO, true),
        piscarLed = prefs.getBoolean(LED, true),
        tutorialConcluido = prefs.getBoolean(TUTORIAL_CONCLUIDO, false),
    )

    private fun gravar(bloco: Ajustes.() -> Ajustes) {
        val novo = _ajustes.value.bloco()
        prefs.edit()
            .putString(URL_SERVIDOR, novo.urlServidor)
            .putString(PERFIL, novo.perfilPadrao.codigo)
            .putInt(HORAS, novo.horasPadrao)
            .putInt(INTERVALO, novo.intervaloSegundos)
            .putFloat(FATOR, novo.fatorSeguranca.toFloat())
            .putFloat(TENSAO, novo.tensaoMinimaV.toFloat())
            .putBoolean(TATIL, novo.feedbackTatil)
            .putBoolean(SONORO, novo.feedbackSonoro)
            .putBoolean(LED, novo.piscarLed)
            .putBoolean(TUTORIAL_CONCLUIDO, novo.tutorialConcluido)
            .apply()
        _ajustes.value = novo
    }

    fun definirUrlServidor(url: String?) = gravar {
        // Só https: o payload leva evidência de auditoria e identificação de
        // remessa. Em http isso viaja legível na rede do galpão.
        copy(urlServidor = url?.trim()?.trimEnd('/')?.takeIf { it.startsWith("https://") })
    }

    fun definirPerfilPadrao(p: PerfilTermico) = gravar { copy(perfilPadrao = p) }
    fun definirHorasPadrao(h: Int) = gravar { copy(horasPadrao = h.coerceIn(1, 8_760)) }
    fun definirIntervalo(s: Int) = gravar { copy(intervaloSegundos = s.coerceAtLeast(60)) }
    fun definirFatorSeguranca(f: Double) = gravar { copy(fatorSeguranca = f.coerceIn(1.0, 3.0)) }
    fun definirTensaoMinima(v: Double) = gravar { copy(tensaoMinimaV = v.coerceIn(0.8, 3.6)) }
    fun definirFeedbackTatil(b: Boolean) = gravar { copy(feedbackTatil = b) }
    fun definirFeedbackSonoro(b: Boolean) = gravar { copy(feedbackSonoro = b) }
    fun definirPiscarLed(b: Boolean) = gravar { copy(piscarLed = b) }
    fun concluirTutorial() = gravar { copy(tutorialConcluido = true) }

    fun restaurarPadroes() = gravar {
        Ajustes(urlServidor = urlServidor, tutorialConcluido = tutorialConcluido)
    }

    private fun duasCasas(v: Float): Double = Math.round(v * 100.0) / 100.0

    companion object {
        /** Mesmo arquivo da v0.3: o install_id e a URL já salvos continuam valendo. */
        private const val ARQUIVO = "thermotrace"

        private const val URL_SERVIDOR = "url_servidor"
        private const val PERFIL = "perfil_padrao"
        private const val HORAS = "horas_padrao"
        private const val INTERVALO = "intervalo_segundos"
        private const val FATOR = "fator_seguranca"
        private const val TENSAO = "tensao_minima_v"
        private const val TATIL = "feedback_tatil"
        private const val SONORO = "feedback_sonoro"
        private const val LED = "piscar_led"
        private const val TUTORIAL_CONCLUIDO = "tutorial_concluido"
    }
}
