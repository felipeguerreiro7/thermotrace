package com.thermotrace.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thermotrace.app.data.db.EtiquetaEntity
import com.thermotrace.app.data.repo.Repositorio
import com.thermotrace.app.nfc.NfcOperator
import com.thermotrace.app.nfc.rememberNfcOperator
import com.thermotrace.app.nfc.TagIdentity
import com.thermotrace.app.ui.components.LinhaInfo
import com.thermotrace.app.ui.components.PainelNfc
import com.thermotrace.app.ui.components.Secao
import com.thermotrace.app.ui.components.TechTopAppBar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class CadastroEtiquetaState(
    val serial: String = "",
    val uidLido: String? = null,
    val temperaturaC: Double? = null,
    val tensaoV: Double? = null,
    val aguardando: Boolean = false,
    val parando: Boolean = false,
    val erro: String? = null,
    val mensagem: String? = null,
)

class EtiquetasViewModel(private val repo: Repositorio) : ViewModel() {

    val etiquetas = repo.observarEtiquetas()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _busca = MutableStateFlow("")
    val busca = _busca.asStateFlow()

    fun buscar(v: String) {
        _busca.value = v
    }

    /**
     * A lista filtrada.
     *
     * Busca por código impresso E por UID: quem tem a etiqueta na mão procura
     * pelo código; quem está olhando um log do servidor tem só o UID. Os dois
     * caminhos precisam chegar na mesma etiqueta.
     */
    val etiquetasVisiveis = combine(etiquetas, _busca) { lista, termo ->
        val t = termo.trim()
        if (t.isBlank()) lista
        else lista.filter {
            it.serial.contains(t, ignoreCase = true) ||
                it.uidNfc.contains(t.replace(Regex("[^0-9A-Fa-f]"), ""), ignoreCase = true)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _cadastro = MutableStateFlow(CadastroEtiquetaState())
    val cadastro = _cadastro.asStateFlow()

    fun alterarSerial(v: String) = _cadastro.update { it.copy(serial = v, erro = null) }

    fun aguardarTag() = _cadastro.update {
        it.copy(aguardando = true, erro = null, mensagem = null)
    }

    fun aguardarParada() = _cadastro.update {
        it.copy(parando = true, aguardando = true, erro = null, mensagem = null)
    }

    fun aoEvento(evento: NfcOperator.NfcEvent) = when (evento) {
        is NfcOperator.NfcEvent.Identified -> _cadastro.update {
            it.copy(
                aguardando = false,
                uidLido = evento.snapshot.uid,
                temperaturaC = evento.snapshot.temperatureC,
                tensaoV = evento.snapshot.voltageV,
                mensagem = "Etiqueta lida. Informe o código impresso e salve.",
            )
        }
        is NfcOperator.NfcEvent.LoggingParado -> _cadastro.update {
            it.copy(
                aguardando = false, parando = false,
                mensagem = if (evento.ok)
                    "Registro parado. A etiqueta esta livre para um novo ciclo."
                else
                    "A etiqueta recusou o STOP. Ela pode nao estar registrando, " +
                        "ou ter senha diferente da padrao de fabrica.",
            )
        }

        is NfcOperator.NfcEvent.Failed -> _cadastro.update {
            it.copy(aguardando = false, parando = false, erro = evento.message)
        }
        else -> Unit
    }

    fun salvar() = viewModelScope.launch {
        val c = _cadastro.value
        val uid = c.uidLido ?: return@launch
        val serial = c.serial.trim().ifBlank { "TT-" + uid.takeLast(8) }

        if (repo.etiquetaPorUid(uid) != null) {
            _cadastro.update { it.copy(erro = "Esta etiqueta já está cadastrada.") }
            return@launch
        }
        if (repo.etiquetaPorQr(serial) != null) {
            _cadastro.update { it.copy(erro = "Já existe uma etiqueta com este código.") }
            return@launch
        }

        repo.cadastrarEtiqueta(
            EtiquetaEntity(
                id = UUID.randomUUID().toString(),
                serial = serial,
                qrPayload = serial,
                uidNfc = TagIdentity.canonical(uid),
                uhfEpc = null,
                uhfTid = null,
                loteId = null,
                certificadoCalibracao = null,
                calibracaoValidaAteMillis = null,
                ciclosAtivacao = 0,
                ultimaTensaoV = c.tensaoV,
                aposentadaEmMillis = null,
            )
        )
        _cadastro.value = CadastroEtiquetaState(mensagem = "Etiqueta $serial cadastrada.")
    }
}

/** A partir de quantas etiquetas a busca compensa o espaço que ocupa. */
private const val LIMIAR_BUSCA_ETIQUETAS = 8

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EtiquetasScreen(vm: EtiquetasViewModel, aoVoltar: () -> Unit) {
    val etiquetas by vm.etiquetas.collectAsStateWithLifecycle()
    val visiveis by vm.etiquetasVisiveis.collectAsStateWithLifecycle()
    val busca by vm.busca.collectAsStateWithLifecycle()
    val cadastro by vm.cadastro.collectAsStateWithLifecycle()

    val operador = rememberNfcOperator { vm.aoEvento(it) }
    // Cadastro e o unico fluxo sem UID esperado: aqui a etiqueta ainda nao
    // tem identidade no sistema. Nada e gravado nela.
    LaunchedEffect(Unit) { operador.expectedUid = null }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TechTopAppBar(
                title = "Etiquetas",
                onBack = aoVoltar,
                eyebrow = "ASSET  /  TAG REGISTRY",
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Secao("Cadastrar etiqueta") {
                    Text(
                        "Encoste a etiqueta para ler o UID, depois informe o código impresso. " +
                            "É esse par que permite conferir a etiqueta certa na ativação.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))

                    PainelNfc(
                        titulo = if (cadastro.aguardando) "Aproxime a etiqueta" else "Ler etiqueta",
                        instrucao = "Somente leitura. Nada é gravado no cadastro.",
                        aguardando = cadastro.aguardando,
                        erro = cadastro.erro,
                    )

                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            vm.aguardarTag()
                            operador.request(NfcOperator.Operation.Identify)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !cadastro.aguardando,
                    ) { Text("Ler etiqueta") }

                    Spacer(Modifier.height(8.dp))
                    // Desativar mora aqui, e não só no fluxo de remessa,
                    // porque em teste de bancada a etiqueta fica presa num
                    // ciclo anterior e recusa novo START. Sem esta saída ela
                    // vira lixo até a memória encher.
                    OutlinedButton(
                        onClick = {
                            vm.aguardarParada()
                            operador.request(NfcOperator.Operation.StopLogging())
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !cadastro.aguardando,
                    ) {
                        Text(
                            if (cadastro.parando) "Aproxime para parar…"
                            else "Parar registro (desativar)"
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Desativar libera a etiqueta para um novo ciclo. Use quando ela " +
                            "ficou presa num teste anterior e recusa a ativação.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    cadastro.uidLido?.let { uid ->
                        Spacer(Modifier.height(12.dp))
                        LinhaInfo("UID lido", uid, destaque = true)
                        cadastro.temperaturaC?.let { LinhaInfo("Temperatura", "%.2f °C".format(it)) }
                        cadastro.tensaoV?.let { LinhaInfo("Bateria", "%.2f V".format(it)) }

                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = cadastro.serial,
                            onValueChange = vm::alterarSerial,
                            label = { Text("Código impresso (ex.: TT-A7K9P2X4)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = vm::salvar, modifier = Modifier.fillMaxWidth()) {
                            Text("Salvar etiqueta")
                        }
                    }

                    cadastro.mensagem?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            item {
                Text(
                    "Cadastradas (${etiquetas.size})",
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            // Um cliente com duzentas etiquetas rolava a lista inteira para
            // conferir uma. Aparece só quando começa a doer.
            if (etiquetas.size >= LIMIAR_BUSCA_ETIQUETAS) {
                item {
                    OutlinedTextField(
                        value = busca,
                        onValueChange = vm::buscar,
                        placeholder = { Text("Código impresso ou UID") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = {
                            if (busca.isNotBlank()) {
                                IconButton(onClick = { vm.buscar("") }) {
                                    Icon(Icons.Default.Close, "Limpar busca")
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (etiquetas.isNotEmpty() && visiveis.isEmpty()) {
                item {
                    Text(
                        "Nenhuma etiqueta combina com \"$busca\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(visiveis, key = { it.id }) { etiqueta ->
                Secao(etiqueta.serial) {
                    LinhaInfo("UID NFC", etiqueta.uidNfc)
                    LinhaInfo("Ciclos de ativação", "${etiqueta.ciclosAtivacao}")
                    LinhaInfo(
                        "Última tensão",
                        etiqueta.ultimaTensaoV?.let { "%.2f V".format(it) } ?: "—",
                        destaque = (etiqueta.ultimaTensaoV ?: 9.9) < 1.4,
                    )
                    LinhaInfo(
                        "Certificado de calibração",
                        etiqueta.certificadoCalibracao ?: "não informado",
                    )
                    if (etiqueta.certificadoCalibracao == null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Sem certificado, o laudo desta etiqueta não se sustenta numa " +
                                "auditoria. Peça o certificado do lote ao fornecedor.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
