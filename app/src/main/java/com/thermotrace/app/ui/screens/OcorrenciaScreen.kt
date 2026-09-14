package com.thermotrace.app.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.thermotrace.app.data.alerta.AlertaEmail
import com.thermotrace.app.data.alerta.EnvioAlertaWorker
import com.thermotrace.app.data.db.AcaoCorretivaEntity
import com.thermotrace.app.data.db.OcorrenciaEntity
import com.thermotrace.app.data.repo.RepositorioAlertas
import com.thermotrace.app.domain.AlertaTermico
import com.thermotrace.app.domain.RegrasTermicas
import com.thermotrace.app.domain.StatusOcorrencia
import com.thermotrace.app.domain.TipoAcaoCorretiva
import com.thermotrace.app.ui.components.LinhaInfo
import com.thermotrace.app.ui.components.Secao
import com.thermotrace.app.ui.components.SeloGravidade
import com.thermotrace.app.ui.components.TechTopAppBar
import com.thermotrace.app.ui.theme.AmbarAlerta
import com.thermotrace.app.ui.theme.VerdeConforme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class OcorrenciaUiState(
    val ocorrencia: OcorrenciaEntity? = null,
    val acoes: List<AcaoCorretivaEntity> = emptyList(),
    val alerta: AlertaTermico? = null,
    val temDestinatarios: Boolean = false,
    val temServidor: Boolean = false,
    // formulário de ação corretiva
    val tipoAcao: TipoAcaoCorretiva? = null,
    val descricaoAcao: String = "",
    val responsavel: String = "",
    val horasAtras: String = "0",
    val mensagem: String? = null,
)

class OcorrenciaViewModel(
    private val alertas: RepositorioAlertas,
    private val temServidor: Boolean,
) : ViewModel() {

    private val _estado = MutableStateFlow(OcorrenciaUiState(temServidor = temServidor))
    val estado = _estado.asStateFlow()

    fun carregar(ocorrenciaId: String) = viewModelScope.launch {
        val alerta = alertas.montarAlerta(ocorrenciaId)
        alertas.observarOcorrencia(ocorrenciaId).collect { comAcoes ->
            _estado.update {
                it.copy(
                    ocorrencia = comAcoes?.ocorrencia,
                    acoes = comAcoes?.acoes.orEmpty(),
                    alerta = alerta,
                    temDestinatarios = alerta?.destinatarios?.any { d ->
                        d.recebe(alerta.gravidade)
                    } == true,
                )
            }
        }
    }

    /** Não registrar envio sem comprovação do servidor. */
    @Suppress("UNUSED_PARAMETER")
    fun enviarPeloServidor(context: Context) {
        _estado.update {
            it.copy(mensagem = "O envio automático está em integração. Os registros continuam preservados no aparelho.")
        }
    }

    /** Contingência: abre o app de e-mail do operador com tudo pronto. */
    fun enviarPeloApp(context: Context) = viewModelScope.launch {
        val alerta = _estado.value.alerta ?: return@launch
        // runCatching: se nao houver app de e-mail instalado, startActivity
        // lanca ActivityNotFoundException e o app fecha. Num celular de
        // galpao sem conta configurada isso e cenario provavel, nao raro.
        val abriu = runCatching { context.startActivity(AlertaEmail.intentEmail(alerta)) }
        if (abriu.isFailure) {
            _estado.update {
                it.copy(mensagem = "Nenhum app de e-mail encontrado neste aparelho. " +
                    "Use \"Enviar pelo servidor\" ou configure uma conta de e-mail.")
            }
            return@launch
        }
        alertas.marcarAlertaEnviado(
            alerta.ocorrenciaId, "app_email",
            alerta.destinatarios.filter { it.recebe(alerta.gravidade) }.map { d -> d.email },
        )
    }

    fun escolherAcao(tipo: TipoAcaoCorretiva) = _estado.update { it.copy(tipoAcao = tipo) }
    fun alterarDescricao(v: String) = _estado.update { it.copy(descricaoAcao = v) }
    fun alterarResponsavel(v: String) = _estado.update { it.copy(responsavel = v) }
    fun alterarHorasAtras(v: String) =
        _estado.update { it.copy(horasAtras = v.filter { c -> c.isDigit() }) }

    fun registrarAcao() = viewModelScope.launch {
        val e = _estado.value
        val ocorrencia = e.ocorrencia ?: return@launch
        val tipo = e.tipoAcao ?: return@launch

        // A ação acontece na estrada; o lançamento acontece quando dá.
        // O usuário informa há quanto tempo ela ocorreu, e o sistema guarda os
        // dois instantes separados.
        val horas = e.horasAtras.toLongOrNull() ?: 0L
        val ocorridoEm = Instant.now().minus(Duration.ofHours(horas))

        alertas.registrarAcaoCorretiva(
            ocorrenciaId = ocorrencia.id,
            tipo = tipo,
            descricao = e.descricaoAcao.takeIf { it.isNotBlank() },
            ocorridoEm = ocorridoEm,
            executadaPor = e.responsavel.takeIf { it.isNotBlank() },
            empresa = null,
            evidenciaUri = null,
        )
        _estado.update {
            it.copy(
                tipoAcao = null, descricaoAcao = "", responsavel = "", horasAtras = "0",
                mensagem = "Ação registrada e incluída no laudo.",
            )
        }
    }

    fun encerrar() = viewModelScope.launch {
        _estado.value.ocorrencia?.let { alertas.encerrarOcorrencia(it.id) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OcorrenciaScreen(
    ocorrenciaId: String,
    vm: OcorrenciaViewModel,
    aoVoltar: () -> Unit,
) {
    val e by vm.estado.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(ocorrenciaId) { vm.carregar(ocorrenciaId) }

    val formato = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault())

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TechTopAppBar(
                title = "Ocorrência",
                onBack = aoVoltar,
                eyebrow = "ALERT  /  INCIDENT DETAIL",
            )
        }
    ) { padding ->
        val ocorrencia = e.ocorrencia
        if (ocorrencia == null) {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { Text("Carregando…") }
            return@Scaffold
        }

        Column(
            Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Secao("O desvio") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(ocorrencia.titulo, style = MaterialTheme.typography.titleMedium)
                    ocorrencia.gravidade?.let { SeloGravidade(it) }
                }
                ocorrencia.detalhe?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(12.dp))

                // Os três tempos, sempre juntos. É a informação que separa um
                // alerta honesto de um alarme falso.
                LinhaInfo(
                    "Quando aconteceu",
                    formato.format(Instant.ofEpochMilli(ocorrencia.ocorridoEmMillis)),
                    destaque = true,
                )
                LinhaInfo(
                    "Quando detectamos",
                    formato.format(Instant.ofEpochMilli(ocorrencia.detectadoEmMillis)),
                )
                LinhaInfo(
                    "Quando foi lançado",
                    formato.format(Instant.ofEpochMilli(ocorrencia.registradoEmMillis)),
                )
                e.alerta?.let { a ->
                    if (a.atrasoDeteccao.toMinutes() > 5) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Detectado ${RegrasTermicas.formatarDuracao(a.atrasoDeteccao.seconds)} " +
                                "depois de ocorrer. A etiqueta registra sozinha e só é lida por " +
                                "aproximação — não existe alerta em tempo real.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                LinhaInfo("Versão da regra", ocorrencia.versaoRegra)
            }

            // ---- envio do alerta -----------------------------------------
            Secao("Alerta por e-mail") {
                if (ocorrencia.alertaEnviadoEmMillis != null) {
                    Text(
                        "Enviado em ${formato.format(Instant.ofEpochMilli(ocorrencia.alertaEnviadoEmMillis))}" +
                            (ocorrencia.alertaCanal?.let { c ->
                                if (c == "app_email") " pelo app de e-mail" else " pelo servidor"
                            } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = VerdeConforme,
                    )
                    ocorrencia.alertaDestinatarios?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.height(10.dp))
                } else if (!e.temDestinatarios) {
                    Text(
                        "Nenhum destinatário cadastrado para esta gravidade. Cadastre em " +
                            "Ocorrências › Destinatários, senão o alerta não tem para onde ir.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AmbarAlerta,
                    )
                    Spacer(Modifier.height(10.dp))
                } else {
                    Text(
                        e.alerta?.destinatarios
                            ?.filter { d -> e.alerta?.let { d.recebe(it.gravidade) } == true }
                            ?.joinToString(", ") { d -> "${d.nome} <${d.email}>" }
                            ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { vm.enviarPeloServidor(context) },
                        enabled = false,
                        modifier = Modifier.weight(1f),
                    ) { Text("Envio em integração") }
                    OutlinedButton(
                        onClick = { vm.enviarPeloApp(context) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Abrir no e-mail") }
                }

                if (!e.temServidor) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Servidor não configurado. O alerta fica na fila local e sobe quando " +
                            "houver backend — ou use \"Abrir no e-mail\" para avisar agora.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ---- ação corretiva ------------------------------------------
            Secao("Ação tomada") {
                if (e.acoes.isNotEmpty()) {
                    e.acoes.sortedBy { it.ocorridoEmMillis }.forEach { acao ->
                        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                            Text(acao.tipo.rotulo, style = MaterialTheme.typography.bodyMedium)
                            acao.descricao?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall)
                            }
                            Text(
                                "Ocorreu em ${formato.format(Instant.ofEpochMilli(acao.ocorridoEmMillis))} · " +
                                    "lançado em ${formato.format(Instant.ofEpochMilli(acao.registradoEmMillis))}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            acao.executadaPor?.let {
                                Text(
                                    "Por $it",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }

                Text("Registrar nova ação", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TipoAcaoCorretiva.entries.forEach { tipo ->
                        FilterChip(
                            selected = e.tipoAcao == tipo,
                            onClick = { vm.escolherAcao(tipo) },
                            label = { Text(tipo.rotulo) },
                        )
                    }
                }

                if (e.tipoAcao != null) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = e.descricaoAcao,
                        onValueChange = vm::alterarDescricao,
                        label = { Text("O que foi feito") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = e.responsavel,
                            onValueChange = vm::alterarResponsavel,
                            label = { Text("Quem executou") },
                            singleLine = true,
                            modifier = Modifier.weight(1.4f),
                        )
                        OutlinedTextField(
                            value = e.horasAtras,
                            onValueChange = vm::alterarHorasAtras,
                            label = { Text("Há quantas horas") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Se a ação foi tomada na estrada e você só está lançando agora, informe " +
                            "há quantas horas. O laudo guarda os dois instantes separados.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = vm::registrarAcao, modifier = Modifier.fillMaxWidth()) {
                        Text("Registrar ação")
                    }
                }
            }

            if (ocorrencia.status != StatusOcorrencia.RESOLVIDA && e.acoes.isNotEmpty()) {
                OutlinedButton(onClick = vm::encerrar, modifier = Modifier.fillMaxWidth()) {
                    Text("Encerrar ocorrência")
                }
            }

            e.mensagem?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.height(24.dp))
        }
    }
}
