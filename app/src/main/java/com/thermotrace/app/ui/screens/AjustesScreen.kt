package com.thermotrace.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thermotrace.app.data.prefs.Ajustes
import com.thermotrace.app.data.prefs.Preferencias
import com.thermotrace.app.domain.PerfilTermico
import com.thermotrace.app.domain.RegrasTermicas
import com.thermotrace.app.nfc.ActivationPlan
import com.thermotrace.app.nfc.versaoSdkFmsh
import com.thermotrace.app.ui.components.LinhaInfo
import com.thermotrace.app.ui.components.Secao
import com.thermotrace.app.ui.components.TechTopAppBar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ajustes do aparelho.
 *
 * A tela existe porque três decisões que mudam de cliente para cliente
 * estavam escritas no código: o intervalo de 10 min, as 72 h padrão e o piso
 * de 1,40 V. Um cliente que transporta em 4 h com intervalo de 5 min tinha
 * que pedir uma versão nova do app.
 *
 * O que ela deliberadamente NÃO faz: mexer em remessa já criada. O perfil, o
 * intervalo e a faixa de uma sessão ficam gravados na própria sessão — mudar
 * o padrão aqui vale da próxima ativação em diante, nunca para trás. Ajuste
 * que reescreve o passado é o fim da auditoria.
 */
class AjustesViewModel(
    private val prefs: Preferencias,
    val installId: String,
    val versaoApp: String,
) : ViewModel() {

    val ajustes = prefs.ajustes

    private val _urlEditada = MutableStateFlow(prefs.atual.urlServidor.orEmpty())
    val urlEditada = _urlEditada.asStateFlow()

    private val _horasEditadas = MutableStateFlow(prefs.atual.horasPadrao.toString())
    val horasEditadas = _horasEditadas.asStateFlow()

    private val _tensaoEditada = MutableStateFlow("%.2f".format(prefs.atual.tensaoMinimaV))
    val tensaoEditada = _tensaoEditada.asStateFlow()

    private val _mensagem = MutableStateFlow<String?>(null)
    val mensagem = _mensagem.asStateFlow()

    fun alterarUrl(v: String) {
        _urlEditada.value = v
        _mensagem.value = null
    }

    fun salvarUrl() {
        val v = _urlEditada.value.trim()
        prefs.definirUrlServidor(v.takeIf { it.isNotBlank() })
        _mensagem.value = when {
            v.isBlank() -> "Servidor removido. Os alertas ficam na fila local."
            !v.startsWith("https://") ->
                "Recusado: só https. O payload leva evidência de auditoria — " +
                    "em http ele viaja legível na rede do galpão."
            else -> "Servidor salvo."
        }
    }

    fun alterarHoras(v: String) {
        _horasEditadas.value = v.filter { it.isDigit() }
        _horasEditadas.value.toIntOrNull()?.takeIf { it > 0 }?.let(prefs::definirHorasPadrao)
    }

    fun alterarTensao(v: String) {
        _tensaoEditada.value = v.filter { it.isDigit() || it == '.' || it == ',' }
        _tensaoEditada.value.replace(',', '.').toDoubleOrNull()
            ?.takeIf { it in 0.8..3.6 }
            ?.let(prefs::definirTensaoMinima)
    }

    fun escolherPerfil(p: PerfilTermico) = prefs.definirPerfilPadrao(p)
    fun escolherIntervalo(s: Int) = prefs.definirIntervalo(s)
    fun escolherFator(f: Double) = prefs.definirFatorSeguranca(f)
    fun alternarTatil(b: Boolean) = prefs.definirFeedbackTatil(b)
    fun alternarSonoro(b: Boolean) = prefs.definirFeedbackSonoro(b)
    fun alternarLed(b: Boolean) = prefs.definirPiscarLed(b)

    fun restaurar() {
        prefs.restaurarPadroes()
        _horasEditadas.value = prefs.atual.horasPadrao.toString()
        _tensaoEditada.value = "%.2f".format(prefs.atual.tensaoMinimaV)
        _mensagem.value = "Padrões restaurados. A URL do servidor foi mantida."
    }

    /** Prévia do que será gravado com os ajustes atuais. Ver [ActivationPlan]. */
    fun previsao(a: Ajustes): String {
        val plano = ActivationPlan.forShipment(
            plannedDurationHours = a.horasPadrao.toDouble(),
            intervalSeconds = a.intervaloSegundos,
            minC = a.perfilPadrao.minC,
            maxC = a.perfilPadrao.maxC,
            safetyFactor = a.fatorSeguranca,
        ).getOrNull()
            ?: return "Nenhum modo de armazenamento comporta essa combinação. " +
                "Aumente o intervalo ou reduza a duração."

        return "${plano.loggingCount} registros · cobre " +
            RegrasTermicas.formatarDuracao((plano.coverageHours * 3600).toLong()) +
            " · modo ${plano.storageMode.name}"
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AjustesScreen(
    vm: AjustesViewModel,
    aoVoltar: () -> Unit,
    aoAbrirDiagnostico: () -> Unit,
    aoAbrirTutorial: () -> Unit,
) {
    val a by vm.ajustes.collectAsStateWithLifecycle()
    val url by vm.urlEditada.collectAsStateWithLifecycle()
    val horas by vm.horasEditadas.collectAsStateWithLifecycle()
    val tensao by vm.tensaoEditada.collectAsStateWithLifecycle()
    val mensagem by vm.mensagem.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TechTopAppBar(
                title = "Ajustes",
                onBack = aoVoltar,
                eyebrow = "SYSTEM  /  CONFIGURATION",
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {

            // ---- padrões de ativação --------------------------------------
            item {
                Secao("Padrão de ativação") {
                    Text(
                        "Vale da próxima ativação em diante. Remessa já criada guarda o " +
                            "que foi gravado nela — mudar aqui não reescreve o passado.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))

                    Text("Perfil térmico", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PerfilTermico.entries.forEach { p ->
                            FilterChip(
                                selected = a.perfilPadrao == p,
                                onClick = { vm.escolherPerfil(p) },
                                label = { Text(p.faixa) },
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    Text("Intervalo entre medições", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Ajustes.INTERVALOS_SEGUNDOS.forEach { s ->
                            FilterChip(
                                selected = a.intervaloSegundos == s,
                                onClick = { vm.escolherIntervalo(s) },
                                label = { Text("${s / 60} min") },
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = horas,
                        onValueChange = vm::alterarHoras,
                        label = { Text("Duração prevista padrão (horas)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.height(14.dp))
                    Text("Folga de gravação", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Quanto a mais que a duração prevista a etiqueta precisa comportar. " +
                            "Atraso de transporte é a regra, não a exceção — e etiqueta que " +
                            "encheu para de gravar em silêncio.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Ajustes.FATORES.forEach { f ->
                            FilterChip(
                                selected = a.fatorSeguranca == f,
                                onClick = { vm.escolherFator(f) },
                                label = { Text("+${((f - 1) * 100).toInt()}%") },
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    LinhaInfo("Com estes ajustes", vm.previsao(a), destaque = true)
                }
            }

            // ---- etiqueta ---------------------------------------------------
            item {
                Secao("Etiqueta") {
                    Text(
                        "Modo compatível com o fabricante",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Identificar, iniciar, ler e parar são comandos separados. Isso " +
                            "evita que uma aproximação curta interrompa uma sequência NFC.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ---- retorno ao operador ---------------------------------------
            item {
                Secao("Retorno ao operador") {
                    Text(
                        "Encostar o celular numa caixa fechada esconde a tela. Sem vibrar " +
                            "e apitar, o operador só descobre o resultado ao afastar o " +
                            "telefone — e afastar no meio da gravação é o que corrompe a " +
                            "ativação.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    Interruptor(
                        titulo = "Vibrar",
                        detalhe = "Um toque ao detectar, dois ao concluir, um longo na falha.",
                        ligado = a.feedbackTatil,
                        aoMudar = vm::alternarTatil,
                    )
                    Spacer(Modifier.height(6.dp))
                    Interruptor(
                        titulo = "Apitar",
                        detalhe = "Bipe agudo no sucesso, grave na falha.",
                        ligado = a.feedbackSonoro,
                        aoMudar = vm::alternarSonoro,
                    )
                }
            }

            // ---- servidor ----------------------------------------------------
            item {
                Secao("Servidor de envio") {
                    Text(
                        "O e-mail de alerta sai do servidor, não do celular: credencial de " +
                            "SMTP dentro de um APK entrega a caixa da empresa para quem " +
                            "descompactar o arquivo.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = url,
                        onValueChange = vm::alterarUrl,
                        label = { Text("URL base (https://)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = vm::salvarUrl, modifier = Modifier.fillMaxWidth()) {
                        Text("Salvar servidor")
                    }
                    mensagem?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(6.dp))
                    LinhaInfo("Em uso", a.urlServidor ?: "nenhum (fila local)")
                }
            }

            // ---- ferramentas --------------------------------------------------
            item {
                Secao("Ferramentas") {
                    OutlinedButton(
                        onClick = aoAbrirTutorial,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Tutorial de uso") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = aoAbrirDiagnostico,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Diagnóstico de etiqueta") }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Interface física, estado do chip, configuração gravada e " +
                            "termômetro ao vivo. Tudo somente leitura.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = vm::restaurar) { Text("Restaurar padrões") }
                }
            }

            // ---- sobre ---------------------------------------------------------
            item {
                Secao("Sobre") {
                    LinhaInfo("Versão do app", vm.versaoApp)
                    LinhaInfo("SDK do fabricante", versaoSdkFmsh())
                    LinhaInfo("Versão da regra térmica", RegrasTermicas.VERSAO)
                    LinhaInfo("Identificador da instalação", vm.installId.take(8) + "…")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "O identificador da instalação é aleatório e não tem ligação com " +
                            "a pessoa nem com o aparelho (LGPD). Serve só para o servidor " +
                            "saber de qual celular veio cada leitura.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun Interruptor(
    titulo: String,
    detalhe: String,
    ligado: Boolean,
    aoMudar: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(titulo, style = MaterialTheme.typography.bodyMedium)
            Text(
                detalhe,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = ligado, onCheckedChange = aoMudar)
    }
}
