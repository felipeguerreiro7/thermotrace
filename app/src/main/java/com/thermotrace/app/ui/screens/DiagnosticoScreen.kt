package com.thermotrace.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thermotrace.app.domain.Medicao
import com.thermotrace.app.domain.RegrasTermicas
import com.thermotrace.app.nfc.NfcOperator
import com.thermotrace.app.nfc.SessionHeader
import com.thermotrace.app.nfc.rememberNfcOperator
import com.thermotrace.app.ui.components.GraficoTemperatura
import com.thermotrace.app.ui.components.LinhaInfo
import com.thermotrace.app.ui.components.PainelNfc
import com.thermotrace.app.ui.components.Secao
import com.thermotrace.app.ui.components.TechTopAppBar
import com.thermotrace.app.ui.components.Selo
import com.thermotrace.app.ui.theme.AmbarAlerta
import com.thermotrace.app.ui.theme.AmbarFundo
import com.thermotrace.app.ui.theme.VerdeConforme
import com.thermotrace.app.ui.theme.VerdeFundo
import com.thermotrace.app.ui.theme.VermelhoExcursao
import com.thermotrace.app.ui.theme.VermelhoFundo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Bancada de diagnóstico.
 *
 * Existe porque o app tinha exatamente uma forma de responder "por que esta
 * etiqueta não funciona?": `adb logcat -s ThermoTraceNFC`. Isso serve para
 * quem escreveu o código e para mais ninguém — e o problema aparece no
 * galpão do cliente, não na bancada de quem programou.
 *
 * Três coisas, todas somente-leitura, todas seguras de rodar numa etiqueta
 * que está monitorando carga real:
 *
 *  1. **Diagnosticar** — o que a etiqueta é: interface física, versão do SDK,
 *     se está acordada, se está registrando, o que mede agora.
 *  2. **Ler configuração gravada** — o que está DENTRO da etiqueta, não o que
 *     o app acha que gravou. Quando os dois divergem, é aqui que aparece.
 *  3. **Termômetro ao vivo** — a conferência contra um termômetro aferido,
 *     que é o que todo cliente pede antes de assinar embaixo do laudo.
 */
data class DiagnosticoUiState(
    val laudo: NfcOperator.DiagnosticoEtiqueta? = null,
    val configuracao: SessionHeader? = null,
    val brutoConfiguracao: List<String> = emptyList(),
    val aoVivo: Boolean = false,
    val medidasAoVivo: List<Medicao> = emptyList(),
    val ultimaMedida: NfcOperator.NfcEvent.MedidaAoVivo? = null,
    val aguardando: Boolean = false,
    val erro: String? = null,
    val mensagem: String? = null,
)

class DiagnosticoViewModel : ViewModel() {

    private val _estado = MutableStateFlow(DiagnosticoUiState())
    val estado = _estado.asStateFlow()

    private val _operacao = MutableStateFlow<NfcOperator.Operation?>(null)
    val operacao = _operacao.asStateFlow()

    fun operacaoConsumida() {
        _operacao.value = null
    }

    fun diagnosticar() {
        _estado.update { it.copy(aguardando = true, erro = null, mensagem = null) }
        _operacao.value = NfcOperator.Operation.Diagnosticar
    }

    fun lerConfiguracao() {
        _estado.update {
            it.copy(
                aguardando = true, erro = null,
                mensagem = "Baixando a sessão inteira para ler o cabeçalho. " +
                    "Não afaste o telefone.",
            )
        }
        _operacao.value = NfcOperator.Operation.LerConfiguracao
    }

    fun iniciarAoVivo() {
        _estado.update {
            it.copy(
                aguardando = true, aoVivo = true, erro = null,
                medidasAoVivo = emptyList(), ultimaMedida = null,
                mensagem = "Mantenha a etiqueta encostada. Cada ponto é uma leitura real.",
            )
        }
        _operacao.value = NfcOperator.Operation.TemperaturaAoVivo
    }

    fun pararAoVivo() {
        _estado.update { it.copy(aoVivo = false) }
    }

    fun aoEvento(evento: NfcOperator.NfcEvent) {
        when (evento) {
            is NfcOperator.NfcEvent.Diagnosticado -> _estado.update {
                it.copy(aguardando = false, laudo = evento.laudo, mensagem = null)
            }

            is NfcOperator.NfcEvent.ConfiguracaoLida -> _estado.update {
                it.copy(
                    aguardando = false,
                    configuracao = evento.cabecalho,
                    brutoConfiguracao = evento.bruto,
                    mensagem = null,
                )
            }

            is NfcOperator.NfcEvent.MedidaAoVivo -> _estado.update {
                val t = evento.temperaturaC
                it.copy(
                    aguardando = false,
                    ultimaMedida = evento,
                    // A janela é curta de propósito: o gráfico ao vivo serve
                    // para ver a etiqueta reagir à mão ou ao gelo, não para
                    // virar histórico — histórico é o que o chip grava sozinho.
                    medidasAoVivo = if (t == null) it.medidasAoVivo else
                        (it.medidasAoVivo + Medicao(evento.amostra, Instant.now(), t))
                            .takeLast(JANELA_AO_VIVO),
                )
            }

            NfcOperator.NfcEvent.AoVivoEncerrado -> _estado.update {
                it.copy(
                    aoVivo = false, aguardando = false,
                    mensagem = "Termômetro encerrado. A etiqueta voltou a dormir.",
                )
            }

            is NfcOperator.NfcEvent.Failed -> _estado.update {
                it.copy(aguardando = false, aoVivo = false, erro = evento.message)
            }

            else -> Unit
        }
    }

    /** Relatório em texto puro, para colar num e-mail ao fornecedor. */
    fun relatorio(): String = buildString {
        val e = _estado.value
        val agora = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
            .withZone(ZoneId.systemDefault()).format(Instant.now())
        appendLine("DIAGNÓSTICO DE ETIQUETA — ThermoTrace")
        appendLine("=".repeat(52))
        appendLine("Gerado em: $agora")
        appendLine()

        e.laudo?.let { l ->
            appendLine("IDENTIDADE")
            appendLine("  UID .............: ${l.uid}")
            appendLine("  Interface .......: ${l.rotuloInterface ?: "—"}")
            appendLine("  Tecnologias .....: ${l.tecnologias.joinToString(", ")}")
            appendLine("  ISO 15693 (NfcV) : ${if (l.suportaIso15693) "SIM" else "não"}")
            appendLine("  ISO 14443-A .....: ${if (l.suportaIso14443a) "SIM" else "não"}")
            appendLine("  SDK do fabricante: ${l.versaoSdk}")
            appendLine()
            appendLine("ESTADO AGORA")
            appendLine("  Acordada ........: " + when (l.acordada) {
                true -> "sim"
                false -> "não (dormindo)"
                null -> "não respondeu"
            })
            appendLine("  Registrando .....: ${if (l.registrando) "sim" else "não"}")
            appendLine("  Temperatura .....: ${l.temperaturaC?.let { "%.2f °C".format(it) } ?: "—"}")
            appendLine("  Tensão ..........: ${l.voltageV?.let { "%.2f V".format(it) } ?: "—"}")
            appendLine("  Campo ...........: ${l.campo ?: "—"}")
            appendLine()
        }

        e.configuracao?.let { c ->
            appendLine("CONFIGURAÇÃO GRAVADA NA ETIQUETA")
            appendLine("  Estado ..........: ${c.statusLabel}")
            appendLine("  Epoch de início .: ${c.startEpoch}")
            appendLine("  Programados .....: ${c.plannedCount}")
            appendLine("  Medidos .........: ${c.measuredCount}")
            appendLine("  Delay ...........: ${c.delayMinutes} min")
            appendLine("  Intervalo .......: ${c.intervalSeconds} s")
            appendLine("  Limites .........: ${c.limitMinC} a ${c.limitMaxC} °C")
            appendLine("  Registrado ......: ${c.recordedMinC} a ${c.recordedMaxC} °C")
            appendLine("  Fora da faixa ...: ${c.belowCount} abaixo, ${c.aboveCount} acima")
            appendLine()
        }

        e.ultimaMedida?.let {
            appendLine("ÚLTIMA MEDIDA AO VIVO")
            appendLine("  Amostras ........: ${it.amostra + 1}")
            appendLine("  Temperatura .....: ${it.temperaturaC?.let { t -> "%.2f °C".format(t) } ?: "—"}")
            appendLine()
        }

        appendLine("Regra de avaliação: ${RegrasTermicas.VERSAO}")
        if (e.laudo == null && e.configuracao == null) {
            appendLine()
            appendLine("(nenhuma leitura feita ainda)")
        }
    }

    private companion object {
        /** Pontos mantidos no gráfico ao vivo: cerca de dois minutos. */
        const val JANELA_AO_VIVO = 120
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticoScreen(vm: DiagnosticoViewModel, aoVoltar: () -> Unit) {
    val e by vm.estado.collectAsStateWithLifecycle()
    val operacao by vm.operacao.collectAsStateWithLifecycle()
    val contexto = LocalContext.current

    val operador = rememberNfcOperator { vm.aoEvento(it) }

    // Diagnóstico é o único fluxo que aceita QUALQUER etiqueta: o objetivo é
    // justamente descobrir o que a etiqueta desconhecida na mão do operador é.
    // Nada é gravado nela em nenhum dos três caminhos desta tela.
    LaunchedEffect(Unit) { operador.expectedUid = null }

    LaunchedEffect(operacao) {
        operacao?.let {
            operador.request(it)
            vm.operacaoConsumida()
        }
    }
    // O laço do termômetro roda na thread do NFC e só olha esta bandeira.
    LaunchedEffect(e.aoVivo) { if (!e.aoVivo) operador.encerrarAoVivo() }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TechTopAppBar(
                title = "Diagnóstico",
                onBack = aoVoltar,
                eyebrow = "NFC  /  HARDWARE DIAGNOSTICS",
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PainelNfc(
                titulo = when {
                    e.aoVivo -> "Termômetro ao vivo"
                    e.aguardando -> "Aproxime a etiqueta"
                    else -> "Bancada de diagnóstico"
                },
                instrucao = if (e.aoVivo)
                    "Mantenha encostada. Afastar encerra a medição."
                else
                    "Tudo nesta tela é somente leitura. Nada é gravado na etiqueta, " +
                        "nem mesmo numa que esteja monitorando carga agora.",
                aguardando = e.aguardando,
                erro = e.erro,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = vm::diagnosticar,
                    enabled = !e.aoVivo,
                    modifier = Modifier.weight(1f),
                ) { Text("Diagnosticar") }
                OutlinedButton(
                    onClick = if (e.aoVivo) vm::pararAoVivo else vm::iniciarAoVivo,
                    modifier = Modifier.weight(1f),
                ) { Text(if (e.aoVivo) "Parar" else "Termômetro") }
            }

            e.mensagem?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }

            // ---- termômetro ao vivo ---------------------------------------
            if (e.aoVivo || e.medidasAoVivo.isNotEmpty()) {
                Secao("Medição ao vivo") {
                    val ultima = e.ultimaMedida
                    Text(
                        ultima?.temperaturaC?.let { "%.2f °C".format(it) } ?: "—",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        "${e.medidasAoVivo.size} leitura(s) · " +
                            (ultima?.voltageV?.let { "bateria %.2f V".format(it) } ?: "bateria —"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (e.medidasAoVivo.size > 1) {
                        Spacer(Modifier.height(10.dp))
                        val temperaturas = e.medidasAoVivo.map { it.temperaturaC }
                        GraficoTemperatura(
                            medicoes = e.medidasAoVivo,
                            // Sem faixa de perfil aqui: a conferência é contra
                            // um termômetro aferido, não contra um limite.
                            minC = temperaturas.min() - 0.5,
                            maxC = temperaturas.max() + 0.5,
                            altura = 140.dp,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Encoste a etiqueta e um termômetro aferido no mesmo ponto por " +
                            "alguns minutos. É esta comparação que o cliente pede antes " +
                            "de aceitar o laudo — e ela não exige ligar a etiqueta.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ---- identidade -----------------------------------------------
            e.laudo?.let { l ->
                Secao("Identidade da etiqueta") {
                    LinhaInfo("UID", l.uid, destaque = true)
                    LinhaInfo("Interface", l.rotuloInterface ?: "—")
                    LinhaInfo("Tecnologias", l.tecnologias.joinToString(", "))
                    LinhaInfo("SDK do fabricante", l.versaoSdk)
                    LinhaInfo(
                        "Acordada",
                        when (l.acordada) {
                            true -> "Sim"
                            false -> "Não (dormindo)"
                            null -> "Não respondeu"
                        },
                    )
                    LinhaInfo("Registrando", if (l.registrando) "Sim" else "Não", destaque = true)
                    LinhaInfo(
                        "Temperatura agora",
                        l.temperaturaC?.let { "%.2f °C".format(it) } ?: "—",
                    )
                    LinhaInfo(
                        "Bateria",
                        l.voltageV?.let { "%.2f V".format(it) } ?: "—",
                        destaque = (l.voltageV ?: 9.9) < 1.40,
                    )
                    LinhaInfo("Força do campo", l.campo ?: "—")
                }

                VereditoIPhone(l)
            }

            // ---- configuração gravada -------------------------------------
            Secao("Configuração gravada na etiqueta") {
                Text(
                    "Lê o que está DENTRO do chip: faixa, intervalo, quantos registros " +
                        "foram programados e quantos já existem. Quando o app e a etiqueta " +
                        "discordam, é aqui que a divergência aparece.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = vm::lerConfiguracao,
                    enabled = !e.aoVivo && !e.aguardando,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Ler configuração gravada") }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Baixa a sessão inteira — o SDK não expõe o cabeçalho sozinho. " +
                        "Custa bateria da etiqueta; use quando precisar conferir.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                e.configuracao?.let { c ->
                    Spacer(Modifier.height(12.dp))
                    LinhaInfo("Estado", c.statusLabel, destaque = true)
                    LinhaInfo("Faixa configurada", "${c.limitMinC} a ${c.limitMaxC} °C")
                    LinhaInfo("Intervalo", "${c.intervalSeconds} s")
                    LinhaInfo("Delay", "${c.delayMinutes} min")
                    LinhaInfo("Registros programados", "${c.plannedCount}")
                    LinhaInfo("Registros medidos", "${c.measuredCount}", destaque = true)
                    LinhaInfo(
                        "Ocupação",
                        if (c.plannedCount > 0)
                            "%d%%".format(100 * c.measuredCount / c.plannedCount)
                        else "—",
                    )
                    LinhaInfo("Mínima registrada", "${c.recordedMinC} °C")
                    LinhaInfo("Máxima registrada", "${c.recordedMaxC} °C")
                    LinhaInfo("Pontos abaixo / acima", "${c.belowCount} / ${c.aboveCount}")
                    LinhaInfo(
                        "Cobertura programada",
                        RegrasTermicas.formatarDuracao(
                            c.plannedCount.toLong() * c.intervalSeconds
                        ),
                    )
                }
            }

            // ---- relatório -------------------------------------------------
            if (e.laudo != null || e.configuracao != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { copiar(contexto, vm.relatorio()) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Copiar") }
                    OutlinedButton(
                        onClick = { compartilhar(contexto, vm.relatorio()) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Enviar ao fornecedor") }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * O veredito que decide se existe app de iPhone.
 *
 * O Core NFC público só envia comando proprietário por `customCommand` em
 * `NFCISO15693Tag`. Se a etiqueta comprada não expuser NfcV, a v2 iOS não é
 * questão de esforço de programação — ela não é possível com API pública.
 * Melhor descobrir na bancada do que depois de prometer ao cliente.
 */
@Composable
internal fun VereditoIPhone(laudo: NfcOperator.DiagnosticoEtiqueta) {
    val (cor, fundo, titulo) = when {
        laudo.suportaIso15693 ->
            Triple(VerdeConforme, VerdeFundo, "Compatível com iPhone")
        laudo.suportaIso14443a ->
            Triple(VermelhoExcursao, VermelhoFundo, "Sem caminho para iPhone")
        else ->
            Triple(AmbarAlerta, AmbarFundo, "Interface não identificada")
    }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = fundo),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Selo(titulo, cor, fundo)
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    laudo.suportaIso15693 ->
                        "A etiqueta responde a ISO 15693. É por esse padrão que o Core NFC " +
                            "público do iPhone envia comando proprietário — o app de iPhone " +
                            "é viável com este modelo."
                    laudo.suportaIso14443a ->
                        "Esta etiqueta só expõe ISO 14443-A. O iPhone não envia comando " +
                            "proprietário por esse caminho com API pública: o app de iPhone " +
                            "não sai com este modelo de etiqueta. Trate como decisão de " +
                            "compra, não de programação."
                    else ->
                        "Não deu para classificar a interface. Rode o diagnóstico de novo " +
                            "com a etiqueta bem encostada."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private fun copiar(contexto: Context, texto: String) {
    val cm = contexto.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cm?.setPrimaryClip(ClipData.newPlainText("Diagnóstico ThermoTrace", texto))
}

private fun compartilhar(contexto: Context, texto: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Diagnóstico de etiqueta — ThermoTrace")
        putExtra(Intent.EXTRA_TEXT, texto)
    }
    runCatching {
        contexto.startActivity(Intent.createChooser(intent, "Enviar diagnóstico"))
    }
}
