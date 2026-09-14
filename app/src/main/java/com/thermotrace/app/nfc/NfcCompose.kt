package com.thermotrace.app.nfc

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.thermotrace.app.ThermoTraceApp
import com.thermotrace.app.ui.components.FeedbackOperador

/**
 * Liga o leitor NFC ao ciclo de vida da tela.
 *
 * **Este arquivo existe por causa de um bug real.** Eu chamava
 * `enableReaderMode` uma única vez, na composição. O Android só mantém o
 * reader mode enquanto a activity está *resumida* — quando a tela apaga, o
 * usuário puxa a gaveta de notificações ou troca de app, ele é desligado.
 * Como eu nunca reativava, o app parava de enxergar a etiqueta e quem
 * respondia era o leitor de tag do sistema: "Nova marca digitalizada".
 *
 * O app do fabricante acerta isso chamando `enableReaderMode()` dentro do
 * `onResume()` de toda activity (`BaseActivity`). Aqui a mesma coisa, só que
 * amarrada ao `Lifecycle` do Compose:
 *
 *     ON_RESUME → start()
 *     ON_PAUSE  → stop()
 *     onDispose → shutdown()
 *
 * Sem isto, o app funciona logo depois de abrir e para de funcionar na
 * primeira vez que a tela pisca — que é exatamente o sintoma difícil de
 * diagnosticar, porque "às vezes funciona".
 *
 * **Retorno tátil e sonoro.** Todo evento passa por [FeedbackOperador] antes
 * de chegar à tela. Fica aqui, e não em cada tela, porque uma tela nova que
 * esquecesse de vibrar seria uma tela em que o operador — de mão encostada
 * numa caixa, sem ver o visor — não sabe se já pode afastar o telefone.
 */
@Composable
fun rememberNfcOperator(aoEvento: (NfcOperator.NfcEvent) -> Unit): NfcOperator {
    // LocalActivity em vez de castar LocalContext: dentro de dialogo ou
    // ModalBottomSheet o contexto e um ContextWrapper e o cast estoura.
    val activity = requireNotNull(LocalActivity.current) {
        "NfcOperator precisa de uma Activity no contexto"
    }
    val donoCicloDeVida = LocalLifecycleOwner.current
    val app = activity.application as ThermoTraceApp
    val preferencias = com.thermotrace.app.LocalDadosLocais.current?.preferencias ?: app.preferencias

    val retorno = remember(activity, donoCicloDeVida) {
        FeedbackOperador(activity) { preferencias.atual }
    }

    // O callback pode mudar a cada recomposição; o operador, não. Sem isto,
    // o operador guardaria uma referência velha e os eventos iriam para uma
    // ViewModel que já não é a da tela.
    val eventoAtual by rememberUpdatedState(aoEvento)
    val operador = remember(activity, donoCicloDeVida) {
        NfcOperator(activity) { evento ->
            sinalizar(retorno, evento)
            eventoAtual(evento)
        }
    }

    DisposableEffect(operador, donoCicloDeVida) {
        val observador = LifecycleEventObserver { _, evento ->
            when (evento) {
                Lifecycle.Event.ON_RESUME -> operador.start()
                Lifecycle.Event.ON_PAUSE -> operador.stop()
                else -> Unit
            }
        }
        donoCicloDeVida.lifecycle.addObserver(observador)

        // A composição normalmente acontece DEPOIS que a Activity já recebeu
        // ON_RESUME. Um observer adicionado aqui não recebe eventos passados;
        // sem esta chamada, a primeira abertura da tela parece aguardar NFC,
        // mas o reader mode nunca é ligado. O app do fabricante não sofre com
        // isso porque chama enableReaderMode diretamente em Activity.onResume.
        if (donoCicloDeVida.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            operador.start()
        }
        onDispose {
            donoCicloDeVida.lifecycle.removeObserver(observador)
            operador.shutdown()
            retorno.liberar()
        }
    }
    return operador
}

/**
 * Traduz o evento em vibração e bipe.
 *
 * `MedidaAoVivo` fica de fora de propósito: o termômetro emite uma medida por
 * segundo, e vibrar a cada uma transformaria a conferência num alarme.
 */
private fun sinalizar(retorno: FeedbackOperador, evento: NfcOperator.NfcEvent) {
    when (evento) {
        is NfcOperator.NfcEvent.TagSeen -> retorno.detectada()

        is NfcOperator.NfcEvent.Identified,
        is NfcOperator.NfcEvent.Activated,
        is NfcOperator.NfcEvent.Downloaded,
        is NfcOperator.NfcEvent.StatusVerificado,
        is NfcOperator.NfcEvent.Diagnosticado,
        is NfcOperator.NfcEvent.ConfiguracaoLida -> retorno.sucesso()

        is NfcOperator.NfcEvent.LoggingParado ->
            if (evento.ok) retorno.sucesso() else retorno.falha()

        is NfcOperator.NfcEvent.WrongTag,
        is NfcOperator.NfcEvent.Failed -> retorno.falha()

        is NfcOperator.NfcEvent.MedidaAoVivo,
        NfcOperator.NfcEvent.AoVivoEncerrado -> Unit
    }
}
