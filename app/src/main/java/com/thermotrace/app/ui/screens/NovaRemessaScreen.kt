package com.thermotrace.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thermotrace.app.data.repo.Repositorio
import com.thermotrace.app.domain.PerfilTermico
import com.thermotrace.app.domain.RegrasTermicas
import com.thermotrace.app.domain.DocumentoFiscal
import com.thermotrace.app.domain.LeitorDocumentoFiscal
import com.thermotrace.app.domain.TipoDocumento
import com.thermotrace.app.ui.components.LinhaInfo
import com.thermotrace.app.ui.components.rememberScannerDocumento
import com.thermotrace.app.ui.components.Secao
import com.thermotrace.app.ui.components.TechTopAppBar
import com.thermotrace.app.ui.theme.AmbarAlerta
import com.thermotrace.app.ui.theme.VerdeConforme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

data class NovaRemessaUiState(
    val codigo: String = "…",
    val documento: DocumentoFiscal? = null,
    val numeroDocumento: String = "",
    val remetente: String = "",
    val transportadora: String = "",
    val destinatario: String = "",
    val destinoEndereco: String = "",
    val contatoRecebimento: String = "",
    val descricaoCarga: String = "",
    val perfil: PerfilTermico = PerfilTermico.REFRIGERADO_2_8,
    val intervaloMinutos: Int = 10,
    val totalVolumes: String = "1",
    val volumesMonitorados: String = "1",
    val duracaoPrevistaHoras: String = "72",
    val criadaId: String? = null,
) {
    val volumes: Int get() = totalVolumes.toIntOrNull()?.coerceIn(1, 999) ?: 1
    val monitorados: Int get() = (volumesMonitorados.toIntOrNull() ?: 1).coerceIn(1, volumes)
    val horas: Double get() = duracaoPrevistaHoras.toDoubleOrNull()?.coerceAtLeast(1.0) ?: 72.0

    /** Quantos registros a etiqueta vai precisar. Mostrado ANTES de criar. */
    val registrosNecessarios: Int
        get() = RegrasTermicas.planejarQuantidade(
            Duration.ofMinutes((horas * 60).toLong()), intervaloMinutos * 60
        )
}

class NovaRemessaViewModel(private val repo: Repositorio) : ViewModel() {
    private val _estado = MutableStateFlow(NovaRemessaUiState())
    val estado = _estado.asStateFlow()

    init {
        viewModelScope.launch {
            _estado.update { it.copy(codigo = repo.proximoCodigo()) }
        }
    }

    fun alterar(bloco: NovaRemessaUiState.() -> NovaRemessaUiState) = _estado.update(bloco)

    fun criar() = viewModelScope.launch {
        val e = _estado.value
        val id = repo.criarRemessa(
            codigo = e.codigo,
            remetente = e.remetente,
            transportadora = e.transportadora,
            destinatario = e.destinatario,
            destinoEndereco = e.destinoEndereco,
            contatoRecebimento = e.contatoRecebimento,
            perfil = e.perfil,
            descricaoCarga = e.descricaoCarga,
            intervaloSegundos = e.intervaloMinutos * 60,
            previsaoColeta = Instant.now(),
            previsaoEntrega = Instant.now().plus(Duration.ofMinutes((e.horas * 60).toLong())),
            totalVolumes = e.volumes,
            volumesMonitorados = e.monitorados,
            documento = e.documento
                ?: e.numeroDocumento.takeIf { it.isNotBlank() }
                    ?.let { LeitorDocumentoFiscal.interpretar(it, "MANUAL") },
        )
        _estado.update { it.copy(criadaId = id) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NovaRemessaScreen(
    vm: NovaRemessaViewModel,
    aoVoltar: () -> Unit,
    aoCriar: (String) -> Unit,
) {
    val e by vm.estado.collectAsStateWithLifecycle()
    val scanner = rememberScannerDocumento { doc -> vm.alterar { copy(documento = doc) } }
    LaunchedEffect(e.criadaId) { e.criadaId?.let(aoCriar) }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TechTopAppBar(
                title = "Nova remessa",
                onBack = aoVoltar,
                eyebrow = "LOGISTICS  /  NEW RECORD",
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Secao("Documento fiscal") {
                Text(
                    "A identidade da remessa vem do documento, não da etiqueta. A etiqueta é " +
                        "insumo reutilizável; o documento é o que existe para o cliente, para a " +
                        "transportadora e para a fiscalização.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))

                val doc = e.documento
                if (doc != null) {
                    LinhaInfo("Tipo", doc.tipo.rotulo, destaque = true)
                    LinhaInfo("Número", doc.numero.trimStart('0'))
                    doc.serie?.let { LinhaInfo("Série", it.trimStart('0').ifBlank { "0" }) }
                    doc.chaveFormatada?.let { LinhaInfo("Chave de acesso", it) }
                    LeitorDocumentoFiscal.formatarCnpj(doc.cnpjEmitente)?.let {
                        LinhaInfo("CNPJ do emitente", it)
                    }
                    doc.ufEmitente?.let { LinhaInfo("UF", it) }
                    doc.competencia?.let { LinhaInfo("Competência", it.toString()) }
                    LinhaInfo("Lido por", doc.simbologia)

                    Spacer(Modifier.height(8.dp))
                    if (doc.validado) {
                        Text(
                            "Dígito verificador confere. Validação feita no aparelho, sem rede.",
                            style = MaterialTheme.typography.labelSmall,
                            color = VerdeConforme,
                        )
                    } else {
                        Text(
                            doc.observacaoValidacao ?: "Documento não validado.",
                            style = MaterialTheme.typography.labelSmall,
                            color = AmbarAlerta,
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { vm.alterar { copy(documento = null) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Trocar documento") }
                } else {
                    Button(
                        onClick = { scanner.escanear() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Escanear QR ou código de barras") }

                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = e.numeroDocumento,
                        onValueChange = { v -> vm.alterar { copy(numeroDocumento = v) } },
                        label = { Text("Ou digite a chave de 44 dígitos / AWB / número") },
                        supportingText = {
                            Text("Digitar é a alternativa. Escanear guarda o conteúdo bruto, " +
                                "que é o que a auditoria pede.")
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (e.numeroDocumento.filter { it.isDigit() }.length >= 11) {
                        val previa = LeitorDocumentoFiscal.interpretar(e.numeroDocumento, "MANUAL")
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (previa.validado) "Reconhecido: ${previa.rotuloCurto}"
                            else previa.observacaoValidacao ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (previa.validado) VerdeConforme else AmbarAlerta,
                        )
                    }
                }
            }

            Secao("Remessa") {
                LinhaInfo("Código interno", e.codigo, destaque = true)
            }

            Secao("Partes") {
                Campo("Remetente", e.remetente) { v -> vm.alterar { copy(remetente = v) } }
                Campo("Transportadora", e.transportadora) { v -> vm.alterar { copy(transportadora = v) } }
                Campo("Destinatário", e.destinatario) { v -> vm.alterar { copy(destinatario = v) } }
                Campo("Endereço de entrega", e.destinoEndereco) { v -> vm.alterar { copy(destinoEndereco = v) } }
                Campo("Contato no recebimento", e.contatoRecebimento) { v -> vm.alterar { copy(contatoRecebimento = v) } }
                Campo("Descrição da carga", e.descricaoCarga) { v -> vm.alterar { copy(descricaoCarga = v) } }
            }

            Secao("Perfil térmico") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PerfilTermico.entries.forEach { perfil ->
                        FilterChip(
                            selected = e.perfil == perfil,
                            onClick = { vm.alterar { copy(perfil = perfil) } },
                            label = { Text(perfil.faixa) },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(e.perfil.rotulo, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Desvios abaixo de ${e.perfil.toleranciaSegundos / 60} min não geram " +
                        "ocorrência: é o tempo de abrir a caixa ou transferir de doca.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Secao("Volumes e duração") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CampoNumero("Total", e.totalVolumes, Modifier.weight(1f)) { v ->
                        vm.alterar { copy(totalVolumes = v) }
                    }
                    CampoNumero("Monitorados", e.volumesMonitorados, Modifier.weight(1f)) { v ->
                        vm.alterar { copy(volumesMonitorados = v) }
                    }
                    CampoNumero("Horas previstas", e.duracaoPrevistaHoras, Modifier.weight(1f)) { v ->
                        vm.alterar { copy(duracaoPrevistaHoras = v) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                // Este bloco existe porque a v0.1 gravava 1000 registros fixos:
                // com intervalo de 10 min isso cobria 6,9 dias e a etiqueta
                // parava de gravar sem avisar ninguém.
                LinhaInfo("Intervalo de registro", "${e.intervaloMinutos} min")
                LinhaInfo("Registros necessários", "${e.registrosNecessarios}", destaque = true)
                LinhaInfo(
                    "Capacidade da etiqueta",
                    if (e.registrosNecessarios <= 4864) "4.864 (modo normal) — cabe"
                    else "acima do modo normal: será usado modo comprimido",
                )
            }

            Button(
                onClick = vm::criar,
                enabled = e.destinatario.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Criar e vincular etiquetas", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Campo(rotulo: String, valor: String, aoMudar: (String) -> Unit) {
    OutlinedTextField(
        value = valor,
        onValueChange = aoMudar,
        label = { Text(rotulo) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
private fun CampoNumero(
    rotulo: String,
    valor: String,
    modifier: Modifier = Modifier,
    aoMudar: (String) -> Unit,
) {
    OutlinedTextField(
        value = valor,
        onValueChange = { aoMudar(it.filter { c -> c.isDigit() || c == '.' }) },
        label = { Text(rotulo) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}
