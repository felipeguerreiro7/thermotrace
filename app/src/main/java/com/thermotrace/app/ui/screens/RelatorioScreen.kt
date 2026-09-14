package com.thermotrace.app.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.thermotrace.app.nfc.Reconciliacao
import com.thermotrace.app.ui.components.HistoricoColetas
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thermotrace.app.data.export.ExportadorLaudo
import com.thermotrace.app.data.repo.Repositorio
import com.thermotrace.app.domain.Medicao
import com.thermotrace.app.domain.PerfilTermico
import com.thermotrace.app.domain.RegrasTermicas
import com.thermotrace.app.domain.ResultadoTermico
import com.thermotrace.app.ui.components.GraficoTemperatura
import com.thermotrace.app.ui.components.LinhaInfo
import com.thermotrace.app.ui.components.Metrica
import com.thermotrace.app.ui.components.Secao
import com.thermotrace.app.ui.components.SeloGravidade
import com.thermotrace.app.ui.components.SeloResultado
import com.thermotrace.app.ui.components.TechTopAppBar
import com.thermotrace.app.ui.theme.VermelhoExcursao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class RelatorioViewModel(private val repo: Repositorio) : ViewModel() {

    private val _estado = MutableStateFlow(RemessaUiState())
    val estado = _estado.asStateFlow()

    private val _medicoes = MutableStateFlow<Map<String, List<Medicao>>>(emptyMap())
    val medicoes = _medicoes.asStateFlow()

    private val _arquivo = MutableStateFlow<File?>(null)
    val arquivo = _arquivo.asStateFlow()

    fun carregar(remessaId: String) = viewModelScope.launch {
        val remessa = repo.buscarRemessa(remessaId) ?: return@launch
        val sessoes = remessa.volumes.mapNotNull { repo.sessaoDoVolume(it.id) }
        val etiquetas = sessoes.mapNotNull { s ->
            repo.buscarEtiqueta(s.sessao.etiquetaId)?.let { s.sessao.etiquetaId to it }
        }.toMap()

        _medicoes.value = sessoes.associate { s ->
            val ultima = s.leituras.maxByOrNull { it.lidaEmMillis }
            s.sessao.id to (ultima?.let { repo.medicoesDe(it) } ?: emptyList())
        }
        _estado.update {
            it.copy(
                remessa = remessa,
                sessoes = sessoes,
                etiquetas = etiquetas,
                resumos = sessoes.associate { s -> s.sessao.id to repo.resumir(s) },
                custodia = repo.eventosCustodia(remessaId),
            )
        }
    }

    fun exportar(context: Context) = viewModelScope.launch {
        val e = _estado.value
        val remessa = e.remessa ?: return@launch
        val arquivo = withContext(Dispatchers.IO) {
            ExportadorLaudo(context).gerar(
                ExportadorLaudo.Entrada(
                    remessa = remessa,
                    sessoes = e.sessoes,
                    etiquetas = e.etiquetas,
                    resumos = e.resumos,
                    medicoes = _medicoes.value,
                )
            )
        }
        // So depois de o arquivo existir. Marcar antes diria que a evidencia
        // saiu do aparelho quando a geracao ainda podia falhar.
        repo.marcarExportadas(e.sessoes.flatMap { s -> s.leituras.map { it.id } })
        _arquivo.value = arquivo
        // O seletor de compartilhamento sempre existe, mas um aparelho sem
        // nenhum app que aceite xlsx pode recusar. Falhar aqui nao pode
        // derrubar o app depois de o laudo ja ter sido gerado com sucesso.
        runCatching { context.startActivity(ExportadorLaudo(context).compartilhar(arquivo)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelatorioScreen(
    remessaId: String,
    vm: RelatorioViewModel,
    aoVoltar: () -> Unit,
) {
    val e by vm.estado.collectAsStateWithLifecycle()
    val medicoes by vm.medicoes.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(remessaId) { vm.carregar(remessaId) }

    val formato = DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.systemDefault())

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TechTopAppBar(
                title = "Laudo térmico",
                onBack = aoVoltar,
                eyebrow = "ANALYTICS  /  THERMAL REPORT",
            )
        }
    ) { padding ->
        val remessa = e.remessa
        if (remessa == null) {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { Text("Carregando…") }
            return@Scaffold
        }

        val perfil = PerfilTermico.porCodigo(remessa.remessa.perfilTermicoCodigo)
        val conferencias = remember(e.sessoes) {
            e.sessoes.flatMap { it.leituras }.map { Reconciliacao.comparar(it.respostaBruta, it.temperaturas) }
        }
        val pendencias = conferencias.count { !it.conferida }
        val vereditos = e.resumos.values.map { it.resultado }
        val geral = when {
            vereditos.isEmpty() || vereditos.any { it == ResultadoTermico.SEM_LEITURA } ->
                ResultadoTermico.SEM_LEITURA
            vereditos.any { it == ResultadoTermico.EXCURSAO } -> ResultadoTermico.EXCURSAO
            vereditos.any { it == ResultadoTermico.ALERTA } -> ResultadoTermico.ALERTA
            else -> ResultadoTermico.CONFORME
        }

        Column(
            Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Secao("Resultado geral") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(remessa.remessa.codigo, style = MaterialTheme.typography.titleMedium)
                    if (pendencias > 0) Text("Pendente de conferência", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelLarge)
                    else SeloResultado(geral)
                }
                Spacer(Modifier.height(10.dp))
                LinhaInfo("Perfil térmico", "${perfil.rotulo}")
                LinhaInfo("Destinatário", remessa.remessa.destinatario)
                LinhaInfo("Volumes monitorados", "${remessa.volumes.count { it.monitorado }}")
                LinhaInfo("Versão da regra", RegrasTermicas.VERSAO)
                if (pendencias > 0) LinhaInfo("Resultado térmico calculado", geral.rotulo)
                if (pendencias > 0) {
                    Text("$pendencias coleta(s) com divergência ou dados insuficientes para conferir. O resultado térmico permanece pendente de conferência da evidência.",
                        color = MaterialTheme.colorScheme.error)
                }
                Text("Conferência ${Reconciliacao.VERSION}. Coerência entre cabeçalho e série não comprova calibração nem libera a carga.",
                    style = MaterialTheme.typography.bodySmall)
            }

            e.sessoes.forEach { sessao ->
                val volume = remessa.volumes.firstOrNull { it.id == sessao.sessao.volumeId }
                val resumo = e.resumos[sessao.sessao.id] ?: return@forEach
                val pontos = medicoes[sessao.sessao.id].orEmpty()

                Secao("Volume ${volume?.sequencia ?: "?"} · ${e.etiquetas[sessao.sessao.etiquetaId]?.serial ?: ""}") {
                    HistoricoColetas(sessao.leituras, sessao.sessao.minConfiguradoC, sessao.sessao.maxConfiguradoC)
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        Metrica("mínima", resumo.minimaC?.let { "%.1f°".format(it) } ?: "—")
                        Metrica("máxima", resumo.maximaC?.let { "%.1f°".format(it) } ?: "—")
                        Metrica("MKT", resumo.mktC?.let { "%.1f°".format(it) } ?: "—")
                        Metrica(
                            "fora da faixa",
                            RegrasTermicas.formatarDuracao(resumo.tempoForaFaixaSegundos),
                            cor = if (resumo.tempoForaFaixaSegundos > 0) VermelhoExcursao else null,
                        )
                    }

                    GraficoTemperatura(
                        medicoes = pontos,
                        minC = sessao.sessao.minConfiguradoC,
                        maxC = sessao.sessao.maxConfiguradoC,
                        intervaloEsperadoSegundos = sessao.sessao.intervaloSegundos,
                    )

                    if (resumo.excursoes.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        Text("Excursões", style = MaterialTheme.typography.labelMedium)
                        resumo.excursoes.forEach { ex ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "${ex.tipo.rotulo} · pico %.1f °C".format(ex.picoC),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        "${formato.format(ex.inicio)} → ${formato.format(ex.fim)} " +
                                            "(${RegrasTermicas.formatarDuracao(ex.duracao.seconds)})",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                SeloGravidade(ex.gravidade)
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    // Bloco de auditoria: é o que sustenta o resto da tela.
                    sessao.leituras.maxByOrNull { it.lidaEmMillis }?.let { leitura ->
                        LinhaInfo("Leituras nesta sessão", "${sessao.leituras.size}")
                        LinhaInfo("Diferença leitura/último ponto", "${leitura.derivaRelogioSegundos} s")
                        LinhaInfo(
                            "Horários corrigidos",
                            if (leitura.horariosCorrigidos) "Sim" else "Não"
                        )
                        LinhaInfo("Base de tempo", sessao.sessao.baseDeTempo)
                        LinhaInfo(
                            "Ativação confirmada",
                            if (sessao.sessao.ativacaoConfirmada) "Sim" else "NÃO",
                            destaque = !sessao.sessao.ativacaoConfirmada,
                        )
                        LinhaInfo("SHA-256", leitura.hashPayload.take(16) + "…")
                    }
                }
            }

            Button(
                onClick = { vm.exportar(context) },
                modifier = Modifier.fillMaxWidth(),
                enabled = e.sessoes.isNotEmpty(),
            ) { Text("Exportar laudo em Excel") }

            Text(
                "A planilha traz seis abas: Laudo, Resumo, Medições, Excursões, Custódia e " +
                    "Auditoria. A última carrega o hash do dado bruto lido da etiqueta — é " +
                    "ela que transforma a planilha em evidência.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
