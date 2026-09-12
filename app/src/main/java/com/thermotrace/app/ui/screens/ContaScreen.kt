package com.thermotrace.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thermotrace.app.data.conta.*
import com.thermotrace.app.ui.components.rememberScannerDocumento
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class EstadoConta(
    val identidade: IdentidadeConta? = null, val cargas: List<CargaConta> = emptyList(),
    val detalhe: DetalheCargaConta? = null, val busca: String = "", val consulta: String = "",
    val carregando: Boolean = false, val reconectar: Boolean = false,
    val haMais: Boolean = false, val mensagem: String? = null,
    val proximoOffset: Int = 0,
)

class ContaViewModel(private val repo: RepositorioConta) : ViewModel() {
    private val _estado = MutableStateFlow(EstadoConta())
    val estado = _estado.asStateFlow()
    init { restaurar() }

    private fun executar(restauro: Boolean = false, bloco: suspend () -> Unit) {
        if (_estado.value.carregando) return
        _estado.value = _estado.value.copy(carregando = true, mensagem = null)
        viewModelScope.launch {
            try { bloco() }
            catch (e: CancellationException) { throw e }
            catch (e: LoginNecessario) { _estado.value = EstadoConta(mensagem = e.message) }
            catch (e: Exception) {
                val texto = when (e) {
                    is FalhaHttpConta -> e.message
                    is IllegalArgumentException -> "Confira o endereço e os campos informados."
                    else -> "Não foi possível consultar o servidor. Verifique sua conexão e tente novamente."
                }
                _estado.value = _estado.value.copy(mensagem = texto,
                    reconectar = restauro && _estado.value.identidade == null)
            } finally { _estado.value = _estado.value.copy(carregando = false) }
        }
    }
    fun restaurar() = executar(restauro = true) {
        val eu = repo.restaurar()
        _estado.value = EstadoConta(identidade = eu, carregando = true)
        if (eu != null && eu.papel != "admin") carregar("")
    }
    fun entrar(endereco: String, email: String, senha: String) = executar {
        val eu = repo.entrar(endereco, email, senha)
        _estado.value = EstadoConta(identidade = eu, carregando = true)
        if (eu.papel != "admin") carregar("")
    }
    fun alterarBusca(texto: String) { _estado.value = _estado.value.copy(busca = texto.take(96)) }
    private suspend fun carregar(busca: String, mais: Boolean = false) {
        val anteriores = if (mais) _estado.value.cargas else emptyList()
        val offset = if (mais) _estado.value.proximoOffset else 0
        val pagina = repo.cargas(busca, offset)
        _estado.value = _estado.value.copy(cargas = (anteriores + pagina.cargas).distinctBy { it.id },
            haMais = pagina.haMais, consulta = busca, detalhe = null, proximoOffset = offset + pagina.cargas.size)
    }
    fun buscar() = executar { carregar(_estado.value.busca) }
    fun escaneado(valor: String) { alterarBusca(valor); buscar() }
    fun mais() = executar { carregar(_estado.value.consulta, true) }
    fun abrir(id: String) = executar { _estado.value = _estado.value.copy(detalhe = repo.detalhe(id)) }
    fun fecharDetalhe() { _estado.value = _estado.value.copy(detalhe = null) }
    fun sair() = executar {
        val confirmado = repo.sair()
        _estado.value = EstadoConta(mensagem = if (confirmado) "Você saiu da conta." else
            "Você saiu deste aparelho. A saída no servidor não pôde ser confirmada.")
    }
}

private fun rotuloStatus(status: String) = when (status) {
    "preparacao" -> "Em preparação"
    "aguardando_aceite" -> "Aguardando aceite"
    "aguardando_coleta" -> "Aguardando coleta"
    "em_transporte" -> "Em transporte"
    "entregue_aguardando_leitura" -> "Entrega aguardando leitura"
    "concluida" -> "Concluída"
    "cancelada" -> "Cancelada"
    else -> "Situação não reconhecida"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContaScreen(vm: ContaViewModel, aoVoltar: () -> Unit) {
    val estado by vm.estado.collectAsStateWithLifecycle()
    var endereco by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var senha by remember { mutableStateOf("") } // Nunca rememberSaveable nem estado persistido.
    val scanner = rememberScannerDocumento { doc -> vm.escaneado(doc.chaveAcesso ?: doc.numero) }
    Scaffold(topBar = {
        TopAppBar(title = { Text(if (estado.detalhe == null) "Conta e cargas" else "Detalhe da carga") },
            navigationIcon = { IconButton(onClick = { if (estado.detalhe != null) vm.fecharDetalhe() else aoVoltar() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar")
            } })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
            if (estado.carregando) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            estado.mensagem?.let { mensagem -> item { Text(mensagem, style = MaterialTheme.typography.bodyMedium) } }
            val eu = estado.identidade
            if (eu == null && !estado.reconectar) {
                item { Text("Acesse as cargas da sua empresa", style = MaterialTheme.typography.titleLarge) }
                item { Text("Use o endereço e a conta fornecidos pela ThermoTrace.") }
                item { OutlinedTextField(endereco, { endereco = it }, label = { Text("Endereço de acesso") },
                    placeholder = { Text("https://…") }, singleLine = true, enabled = !estado.carregando,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(email, { email = it }, label = { Text("E-mail") }, singleLine = true,
                    enabled = !estado.carregando, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(senha, { senha = it }, label = { Text("Senha") }, singleLine = true,
                    enabled = !estado.carregando, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth()) }
                item { Button(onClick = { val valor = senha; senha = ""; vm.entrar(endereco, email, valor) },
                    enabled = !estado.carregando && endereco.isNotBlank() && email.isNotBlank() && senha.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()) { Text("Entrar") } }
            } else if (estado.reconectar) {
                item { Button(onClick = vm::restaurar, enabled = !estado.carregando) { Text("Tentar novamente") } }
            }
            if (eu != null) {
                item { Text(eu.empresa, style = MaterialTheme.typography.titleLarge); Text(eu.nome) }
                val detalhe = estado.detalhe
                if (detalhe != null) {
                    item {
                        Text(detalhe.carga.codigo, style = MaterialTheme.typography.headlineSmall)
                        Text(detalhe.carga.destinatario)
                        Text(rotuloStatus(detalhe.carga.status))
                    }
                    item {
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Critérios desta carga", style = MaterialTheme.typography.titleMedium)
                            Text(detalhe.faixa ?: "Critério legado sem informação completa")
                            Text("Intervalo: ${detalhe.intervaloSegundos} segundos")
                            detalhe.versaoPerfil?.let { Text("Versão do perfil: $it") }
                            Text("${detalhe.quantidadeVolumes} volume(s)")
                        } }
                    }
                    item { Text("Documentos vinculados", style = MaterialTheme.typography.titleMedium) }
                    items(detalhe.documentos) { Text("${it.tipo.uppercase()} · ${it.numero}") }
                    item { Text("A conexão dessas cargas às leituras deste aparelho está em implementação.",
                        style = MaterialTheme.typography.bodySmall) }
                } else if (eu.papel != "admin") {
                    item { OutlinedTextField(estado.busca, vm::alterarBusca, label = { Text("Código da carga ou documento") },
                        singleLine = true, enabled = !estado.carregando, modifier = Modifier.fillMaxWidth()) }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::buscar, enabled = !estado.carregando) { Text("Buscar") }
                        OutlinedButton(onClick = { scanner.escanear() }, enabled = !estado.carregando) { Text("Ler código") }
                    } }
                    if (estado.cargas.isEmpty() && !estado.carregando) item { Text("Nenhuma carga encontrada para esta consulta.") }
                    if (estado.cargas.size > 1 && estado.consulta.isNotBlank()) item { Text("Confira qual entrega você quer consultar.") }
                    items(estado.cargas, key = { it.id }) { carga ->
                        Card(onClick = { vm.abrir(carga.id) }, enabled = !estado.carregando, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(carga.codigo, style = MaterialTheme.typography.titleMedium)
                                Text(carga.destinatario)
                                Text(rotuloStatus(carga.status))
                                Text(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault()).format(carga.criadaEm),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (estado.haMais) item { OutlinedButton(onClick = vm::mais, enabled = !estado.carregando) { Text("Carregar mais") } }
                } else item { Text("Conta da equipe conectada. A gestão de clientes será disponibilizada no portal da ThermoTrace.") }
            }
            if (eu != null || estado.reconectar) item { TextButton(onClick = { email = ""; senha = ""; vm.sair() },
                enabled = !estado.carregando) { Text("Sair da conta") } }
        }
    }
}
