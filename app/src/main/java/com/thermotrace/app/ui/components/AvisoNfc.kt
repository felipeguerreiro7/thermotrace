package com.thermotrace.app.ui.components

import android.content.Intent
import android.nfc.NfcAdapter
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.thermotrace.app.ui.theme.AmbarAlerta
import com.thermotrace.app.ui.theme.AmbarFundo
import com.thermotrace.app.ui.theme.VermelhoExcursao
import com.thermotrace.app.ui.theme.VermelhoFundo

/**
 * Avisa quando o NFC está desligado — ou quando o aparelho não tem NFC.
 *
 * O app do fabricante faz isso (`MainActivity.hasNfc` + diálogo com atalho
 * para Configurações) e nós não fazíamos. Sem este aviso o operador encosta
 * a etiqueta, nada acontece, e ele conclui que o app está quebrado.
 *
 * O estado é reavaliado a cada `ON_RESUME`: o operador sai para Configurações,
 * liga o NFC e volta — o aviso tem que sumir sozinho.
 */
@Composable
fun AvisoNfc(modifier: Modifier = Modifier) {
    val contexto = LocalContext.current
    val dono = LocalLifecycleOwner.current
    val adaptador = remember { NfcAdapter.getDefaultAdapter(contexto) }

    var ligado by remember { mutableStateOf(adaptador?.isEnabled == true) }

    DisposableEffect(dono) {
        val observador = LifecycleEventObserver { _, evento ->
            if (evento == Lifecycle.Event.ON_RESUME) {
                ligado = adaptador?.isEnabled == true
            }
        }
        dono.lifecycle.addObserver(observador)
        onDispose { dono.lifecycle.removeObserver(observador) }
    }

    when {
        // Aparelho sem NFC: não há o que fazer, e o texto precisa dizer isso
        // sem mandar o operador procurar uma configuração que não existe.
        adaptador == null -> Card(
            modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = VermelhoFundo),
            shape = RoundedCornerShape(10.dp),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "Este aparelho não tem NFC",
                    style = MaterialTheme.typography.titleMedium,
                    color = VermelhoExcursao,
                )
                Text(
                    "Sem NFC não é possível ler nem ativar etiquetas. Use um celular " +
                        "com NFC para operar.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        !ligado -> Card(
            modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AmbarFundo),
            shape = RoundedCornerShape(10.dp),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "NFC desligado",
                    style = MaterialTheme.typography.titleMedium,
                    color = AmbarAlerta,
                )
                Text(
                    "O app não consegue ler etiquetas com o NFC desligado.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        // Leva direto à tela de NFC. Se o fabricante do
                        // aparelho não expuser essa tela, cai nas
                        // configurações sem fio, que sempre existem.
                        runCatching {
                            contexto.startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
                        }.onFailure {
                            runCatching {
                                contexto.startActivity(
                                    Intent(Settings.ACTION_WIRELESS_SETTINGS)
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Abrir configurações de NFC") }
            }
        }
    }
}
