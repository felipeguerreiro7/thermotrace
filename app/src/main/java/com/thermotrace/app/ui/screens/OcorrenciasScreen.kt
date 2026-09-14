package com.thermotrace.app.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.thermotrace.app.data.db.DestinatarioAlertaEntity
import com.thermotrace.app.data.db.OcorrenciaComAcoes
import com.thermotrace.app.data.repo.RepositorioAlertas
import com.thermotrace.app.domain.GravidadeExcursao
import com.thermotrace.app.domain.PapelDestinatario
import com.thermotrace.app.domain.StatusOcorrencia
import com.thermotrace.app.ui.components.LinhaInfo
import com.thermotrace.app.ui.components.Secao
import com.thermotrace.app.ui.components.TechTopAppBar
import com.thermotrace.app.ui.components.Selo
import com.thermotrace.app.ui.components.SeloGravidade
import com.thermotrace.app.ui.theme.AmbarAlerta
import com.thermotrace.app.ui.theme.AmbarFundo
import com.thermotrace.app.ui.theme.CinzaNeutro
import com.thermotrace.app.ui.theme.VerdeConforme
import com.thermotrace.app.ui.theme.VerdeFundo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

data class NovoDestinatario(
    val nome: String = "",
    val email: String = "",
    val papel: PapelDestinatario = PapelDestinatario.TRANSPORTADORA,
    val gravidadeMinima: GravidadeExcursao = GravidadeExcursao.ALERTA,
    val erro: String? = null,
)

class OcorrenciasViewModel(
    private val alertas: RepositorioAlertas,
    private val urlServidorAtual: String?,
    private val definirUrl: (String?) -> Unit,
) : ViewModel() {

    val abertas = alertas.observarAbertas()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val destinatarios = alertas.observarDestinatarios()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _novo = MutableStateFlow(NovoDestinatario())
    val novo = _novo.asStateFlow()

    private val _url = MutableStateFlow(urlServidorAtual.orEmpty())
    val url = _url.asStateFlow()

    fun alterarNovo(bloco: NovoDestinatario.() -> NovoDestinatario) = _novo.update(bloco)
    fun alterarUrl(v: String) = _url.update { v }

    fun salvarUrl() {
        definirUrl(_url.value.takeIf { it.isNotBlank() })
    }

    fun salvarDestinatario() = viewModelScope.launch {
        val n = _novo.value
        val email = n.email.trim()
        if (!email.contains("@") || !email.contains(".")) {
            _novo.update { it.copy(erro = "E-mail inválido.") }
            return@launch
        }
        alertas.salvarDestinatario(
            DestinatarioAlertaEntity(
                id = UUID.randomUUID().toString(),
                nome = n.nome.trim().ifBlank { email.substringBefore("@") },
                email = email,
                papel = n.papel,
                gravidadeMinima = n.gravidadeMinima,
                ativo = true,
            )
        )
        _novo.value = NovoDestinatario()
    }

    fun remover(id: String) = viewModelScope.launch { alertas.removerDestinatario(id) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OcorrenciasScreen(
    vm: OcorrenciasViewModel,
    aoVoltar: () -> Unit,
    aoAbrirOcorrencia: (String) -> Unit,
) {
    val abertas by vm.abertas.collectAsStateWithLifecycle()
    val destinatarios by vm.destinatarios.collectAsStateWithLifecycle()
    val novo by vm.novo.collectAsStateWithLifecycle()
    val url by vm.url.collectAsStateWithLifecycle()

    val formato = DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.systemDefault())

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TechTopAppBar(
                title = "Ocorrências",
                onBack = aoVoltar,
                eyebrow = "ALERT  /  INCIDENT QUEUE",
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (abertas.isEmpty()) {
                item {
                    Secao("Em aberto") {
                        Text(
                            "Nenhuma ocorrência aberta.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            } else {
                item {
                    Text(
                        "Em aberto (${abertas.size})",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                items(abertas, key = { it.ocorrencia.id }) { item ->
                    CartaoOcorrencia(item, formato) { aoAbrirOcorrencia(item.ocorrencia.id) }
                }
            }

            // ---- destinatários ------------------------------------------
            item {
                Secao("Destinatários do alerta") {
                    Text(
                        "Quem recebe o e-mail quando uma excursão é detectada. A gravidade " +
                            "mínima existe para o motorista não receber alerta de 5 minutos " +
                            "de desvio — e continuar lendo os que importam.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))

                    destinatarios.forEach { d ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(d.nome, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${d.email} · ${d.papel.rotulo} · a partir de ${d.gravidadeMinima.rotulo}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { vm.remover(d.id) }) { Text("Remover") }
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = novo.nome,
                            onValueChange = { v -> vm.alterarNovo { copy(nome = v) } },
                            label = { Text("Nome") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = novo.email,
                            onValueChange = { v -> vm.alterarNovo { copy(email = v, erro = null) } },
                            label = { Text("E-mail") },
                            singleLine = true,
                            isError = novo.erro != null,
                            modifier = Modifier.weight(1.4f),
                        )
                    }
                    novo.erro?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error)
                    }

                    Spacer(Modifier.height(8.dp))
                    Text("Papel", style = MaterialTheme.typography.labelSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PapelDestinatario.entries.forEach { p ->
                            FilterChip(
                                selected = novo.papel == p,
                                onClick = { vm.alterarNovo { copy(papel = p) } },
                                label = { Text(p.rotulo) },
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text("Recebe a partir de", style = MaterialTheme.typography.labelSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        GravidadeExcursao.entries.forEach { g ->
                            FilterChip(
                                selected = novo.gravidadeMinima == g,
                                onClick = { vm.alterarNovo { copy(gravidadeMinima = g) } },
                                label = { Text(g.rotulo) },
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = vm::salvarDestinatario,
                        enabled = novo.email.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Adicionar destinatário") }
                }
            }

            item {
                Secao("Envio online") {
                    Text("A sincronização autenticada está em integração. As coletas e os alertas continuam preservados no aparelho; configurar uma URL não envia esta fila.",
                        style = MaterialTheme.typography.bodyMedium)
                    Text("Use Conta e cargas para consultar os dados já recebidos no servidor.",
                        style = MaterialTheme.typography.labelSmall)
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun CartaoOcorrencia(
    item: OcorrenciaComAcoes,
    formato: DateTimeFormatter,
    aoClicar: () -> Unit,
) {
    val oc = item.ocorrencia
    val (cor, fundo) = when (oc.status) {
        StatusOcorrencia.ABERTA -> AmbarAlerta to AmbarFundo
        StatusOcorrencia.ALERTA_ENVIADO -> AmbarAlerta to AmbarFundo
        StatusOcorrencia.EM_TRATAMENTO -> VerdeConforme to VerdeFundo
        else -> CinzaNeutro to androidx.compose.ui.graphics.Color(0x22808080)
    }

    Secao(oc.tipo.rotulo, Modifier.clickable(onClick = aoClicar)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(oc.titulo, style = MaterialTheme.typography.bodyMedium)
            oc.gravidade?.let { SeloGravidade(it) }
        }
        Spacer(Modifier.height(8.dp))
        LinhaInfo(
            "Ocorreu em",
            formato.format(Instant.ofEpochMilli(oc.ocorridoEmMillis)),
        )
        LinhaInfo(
            "Detectado em",
            formato.format(Instant.ofEpochMilli(oc.detectadoEmMillis)),
        )
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Selo(oc.status.rotulo, cor, fundo)
            Text(
                if (item.acoes.isEmpty()) "sem ação registrada"
                else "${item.acoes.size} ação(ões)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
