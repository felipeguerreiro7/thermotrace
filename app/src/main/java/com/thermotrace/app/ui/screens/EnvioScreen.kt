package com.thermotrace.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thermotrace.app.data.conta.*
import com.thermotrace.app.data.db.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

data class EstadoEnvio(val sessaoId: String = "", val resumo: String = "", val busca: String = "",
    val cargas: List<CargaConta> = emptyList(), val destino: DestinoEnvio? = null,
    val volume: VolumeEnvio? = null, val vinculo: VinculoEnvio? = null,
    val pedidos: List<PedidoEnvio> = emptyList(), val ocupado: Boolean = false, val mensagem: String? = null)

class EnvioViewModel(private val envio: EnvioColetas, private val db: ThermoTraceDb): ViewModel() {
    private val _estado = MutableStateFlow(EstadoEnvio())
    val estado = _estado.asStateFlow()
    private fun executar(bloco: suspend () -> Unit) {
        if (_estado.value.ocupado) return
        _estado.value = _estado.value.copy(ocupado=true,mensagem=null)
        viewModelScope.launch {
            try { bloco() }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { _estado.value = _estado.value.copy(mensagem=when(e) {
                is LoginNecessario -> e.message
                is FalhaHttpConta -> when(e.status) {
                    404 -> "Carga ou etiqueta não cadastrada no servidor desta empresa. Confira o catálogo."
                    409 -> "Conflito no cadastro. Preserve a coleta e confira com o suporte."
                    else -> "Não foi possível acessar o servidor (HTTP ${e.status}). Tente novamente."
                }
                is IllegalStateException -> e.message
                else -> "Não foi possível concluir. Confira a conexão e tente novamente; os registros continuam no celular."
            }) }
            finally {
                // Também recarrega recibos de um envio parcialmente concluído.
                runCatching { atualizar() }
                _estado.value = _estado.value.copy(ocupado=false)
            }
        }
    }
    private suspend fun atualizar() {
        val id = _estado.value.sessaoId
        if(id.isNotEmpty()) _estado.value = _estado.value.copy(vinculo=envio.vinculo(id),pedidos=envio.pedidos(id))
    }
    fun carregar(id: String) = executar {
        val s = checkNotNull(db.sessaoDao().buscar(id))
        val r = checkNotNull(db.remessaDao().buscar(s.remessaId))
        val v = checkNotNull(db.volumeDao().buscar(s.volumeId))
        val etiqueta = checkNotNull(db.etiquetaDao().buscar(s.etiquetaId))
        _estado.value = EstadoEnvio(sessaoId=id,ocupado=true,
            resumo="${r.codigo} · volume ${v.sequencia}\n${etiqueta.serial} · UID ${etiqueta.uidNfc}\n${s.minConfiguradoC} a ${s.maxConfiguradoC} °C · intervalo ${s.intervaloSegundos} s")
        atualizar()
    }
    fun busca(s: String) { _estado.value = _estado.value.copy(busca=s.take(96)) }
    fun buscar() = executar {
        check(_estado.value.busca.isNotBlank()) { "Informe o código ou documento da carga online." }
        _estado.value = _estado.value.copy(cargas=envio.cargas(_estado.value.busca),destino=null,volume=null)
        if(_estado.value.cargas.isEmpty()) _estado.value = _estado.value.copy(mensagem="Nenhuma carga encontrada para esta conta.")
    }
    fun escolherCarga(id: String) = executar { _estado.value = _estado.value.copy(destino=envio.destino(id),volume=null) }
    fun escolherVolume(v: VolumeEnvio) { _estado.value = _estado.value.copy(volume=v) }
    fun vincular() = executar {
        val e = _estado.value
        envio.vincular(e.sessaoId,checkNotNull(e.destino).id,checkNotNull(e.volume).id)
        _estado.value = _estado.value.copy(mensagem="Destino confirmado. Toque em Enviar coletas para transmitir.")
    }
    fun enviar() = executar {
        val n = envio.enviar(_estado.value.sessaoId)
        _estado.value = _estado.value.copy(mensagem=if(n==0) "Nenhuma coleta nova para enviar." else "$n coleta(s) com recibo salvo neste celular.")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnvioScreen(id: String, vm: EnvioViewModel, aoVoltar: () -> Unit) {
    val e by vm.estado.collectAsStateWithLifecycle()
    LaunchedEffect(id) { vm.carregar(id) }
    BackHandler(e.ocupado) { }
    var confirmar by remember { mutableStateOf(false) }
    if(confirmar) AlertDialog(onDismissRequest={ confirmar=false },title={ Text("Confirmar destino das coletas?") },
        text={ Text("${e.destino?.codigo} · ${e.destino?.destinatario}\nVolume ${e.volume?.sequencia}\n\nConfira a carga física. O vínculo fica registrado e não poderá ser trocado nesta tela.") },
        confirmButton={ TextButton(onClick={ confirmar=false; vm.vincular() }) { Text("Confirmar vínculo") } },
        dismissButton={ TextButton(onClick={ confirmar=false }) { Text("Voltar e conferir") } })
    Scaffold(topBar={ TopAppBar(title={ Text("Enviar coletas") },navigationIcon={ IconButton(onClick=aoVoltar,enabled=!e.ocupado) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack,"Voltar") } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item { Text(e.resumo,style=MaterialTheme.typography.titleMedium) }
            item { Text("Envio pela conta original. As coletas ficam no celular até o servidor confirmar o recebimento. GPS, STOP físico e laudo completo ainda permanecem locais.") }
            if(e.ocupado) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            e.mensagem?.let { item { Text(it) } }
            val vinculo = e.vinculo
            if(vinculo == null) {
                item { OutlinedTextField(e.busca,vm::busca,label={ Text("Código ou documento da carga online") },enabled=!e.ocupado,modifier=Modifier.fillMaxWidth()) }
                item { Button(onClick=vm::buscar,enabled=!e.ocupado && e.busca.isNotBlank()) { Text("Buscar carga") } }
                items(e.cargas,key={it.id}) { c -> OutlinedButton(onClick={vm.escolherCarga(c.id)},enabled=!e.ocupado,modifier=Modifier.fillMaxWidth()) {
                    Text("${c.codigo} · ${c.destinatario}") } }
                e.destino?.let { d ->
                    item { Text("Destino: ${d.codigo}\n${d.minimo} a ${d.maximo} °C · intervalo ${d.intervalo} s") }
                    items(d.volumes,key={it.id}) { v -> OutlinedButton(onClick={vm.escolherVolume(v)},enabled=!e.ocupado,modifier=Modifier.fillMaxWidth()) {
                        Text("${if(e.volume?.id==v.id) "✓ " else ""}Volume ${v.sequencia} · ${v.identidade ?: "sem código adicional"}") } }
                    item { Button(onClick={confirmar=true},enabled=!e.ocupado && e.volume!=null) { Text("Conferir vínculo") } }
                }
            } else {
                item { Text("Destino registrado: ${vinculo.codigoCarga} · volume ${vinculo.sequenciaVolume}") }
                item { Button(onClick=vm::enviar,enabled=!e.ocupado,modifier=Modifier.fillMaxWidth()) { Text("Enviar coletas / tentar novamente") } }
                item { Text("${e.pedidos.count{it.recibo!=null}} recibo(s) salvo(s). Novas coletas são incluídas ao tocar em Enviar.") }
                items(e.pedidos,key={it.eventoId}) { p -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                    Text("${p.tipo.replaceFirstChar { it.uppercase() }} · ${if(p.recibo==null) "Pendente" else "Recebida pelo servidor"}")
                    p.erro?.let { Text(it) }
                    p.recibo?.let { raw ->
                        val r = JSONObject(raw)
                        Text("Recebida em: ${r.getString("recebida_em_servidor")}")
                        Text("Comprovante: ${r.getString("leitura_id")}",style=MaterialTheme.typography.bodySmall)
                        Text("Recebimento não comprova validação física ou liberação da carga.",style=MaterialTheme.typography.bodySmall)
                    }
                } } }
            }
        }
    }
}
