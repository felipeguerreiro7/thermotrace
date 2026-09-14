package com.thermotrace.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.thermotrace.app.nfc.rememberNfcOperator
import com.thermotrace.app.domain.DocumentoFiscal
import com.thermotrace.app.ui.components.DialogoNotaManual
import com.thermotrace.app.ui.components.HistoricoColetas
import com.thermotrace.app.ui.components.rememberScannerDocumento
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thermotrace.app.data.db.CustodiaEntity
import com.thermotrace.app.data.db.EtiquetaEntity
import com.thermotrace.app.data.db.RemessaCompleta
import com.thermotrace.app.data.db.SessaoComLeituras
import com.thermotrace.app.data.db.VolumeEntity
import com.thermotrace.app.data.repo.Repositorio
import com.thermotrace.app.data.repo.RepositorioAlertas
import com.thermotrace.app.data.db.OcorrenciaComAcoes
import com.thermotrace.app.domain.EstadoEtiqueta
import com.thermotrace.app.ui.components.CartaoEstadoEtiqueta
import com.thermotrace.app.domain.PerfilTermico
import com.thermotrace.app.domain.RegrasTermicas
import com.thermotrace.app.domain.ResultadoTermico
import com.thermotrace.app.domain.ResumoTermico
import com.thermotrace.app.domain.StatusVolume
import com.thermotrace.app.domain.TipoLeitura
import com.thermotrace.app.ui.components.LinhaInfo
import com.thermotrace.app.ui.components.Secao
import com.thermotrace.app.ui.components.SeloGravidade
import com.thermotrace.app.ui.components.SeloResultado
import com.thermotrace.app.ui.components.TrilhoLeituras
import com.thermotrace.app.ui.components.TechTopAppBar
import com.thermotrace.app.ui.theme.AmbarAlerta
import com.thermotrace.app.ui.theme.AzulClaro
import com.thermotrace.app.ui.theme.VerdeConforme
import com.thermotrace.app.ui.theme.VermelhoExcursao
import com.thermotrace.app.ui.theme.CinzaNeutro
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class RemessaUiState(
    val remessa: RemessaCompleta? = null,
    val sessoes: List<SessaoComLeituras> = emptyList(),
    val etiquetas: Map<String, EtiquetaEntity> = emptyMap(),
    val resumos: Map<String, ResumoTermico> = emptyMap(),
    val custodia: List<CustodiaEntity> = emptyList(),
    val estados: Map<String, EstadoEtiqueta> = emptyMap(),
    val ocorrencias: List<OcorrenciaComAcoes> = emptyList(),
    val mensagem: String? = null,
)

class RemessaViewModel(
    private val repo: Repositorio,
    private val alertas: RepositorioAlertas,
) : ViewModel() {
    private val _estado = MutableStateFlow(RemessaUiState())
    val estado = _estado.asStateFlow()

    fun anexarDocumento(remessaId: String, doc: DocumentoFiscal) = viewModelScope.launch {
        val mensagem = when (val r = repo.anexarDocumento(remessaId, doc)) {
            is Repositorio.ResultadoDocumento.Ok -> "Nota vinculada à remessa."
            is Repositorio.ResultadoDocumento.JaUsada -> "Esta nota já está na remessa ${r.codigoRemessa}. Confira o documento."
            is Repositorio.ResultadoDocumento.RemessaInexistente -> r.motivo
        }
        _estado.update { it.copy(mensagem = mensagem) }
        carregar(remessaId)
    }

    fun carregar(remessaId: String) = viewModelScope.launch {
        val remessa = repo.buscarRemessa(remessaId) ?: return@launch
        val sessoes = remessa.volumes.mapNotNull { repo.sessaoDoVolume(it.id) }
        val etiquetas = sessoes.mapNotNull { s ->
            repo.buscarEtiqueta(s.sessao.etiquetaId)?.let { s.sessao.etiquetaId to it }
        }.toMap() + remessa.volumes.mapNotNull { v ->
            v.etiquetaId?.let { id -> repo.buscarEtiqueta(id)?.let { id to it } }
        }.toMap()

        _estado.update {
            it.copy(
                remessa = remessa,
                sessoes = sessoes,
                etiquetas = etiquetas,
                resumos = sessoes.associate { s -> s.sessao.id to repo.resumir(s) },
                custodia = repo.eventosCustodia(remessaId),
                // Sem verificação por NFC agora: os estados saem como PRESUMIDOS.
                estados = sessoes.associate { s ->
                    s.sessao.id to alertas.estadoDaEtiqueta(s, registrandoAgora = null)
                },
                ocorrencias = alertas.ocorrenciasDaRemessa(remessaId),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemessaScreen(
    remessaId: String,
    vm: RemessaViewModel,
    aoVoltar: () -> Unit,
    aoLer: (volumeId: String, tipo: TipoLeitura) -> Unit,
    aoAbrirRelatorio: () -> Unit,
    aoAbrirOcorrencia: (String) -> Unit,
) {
    val e by vm.estado.collectAsStateWithLifecycle()
    LifecycleResumeEffect(remessaId) {
        vm.carregar(remessaId)
        onPauseOrDispose { }
    }
    // Mantém a etiqueta sob controle do app; a operação é escolhida no volume.
    rememberNfcOperator { }
    var notaManual by remember(remessaId) { mutableStateOf(false) }
    val scannerNota = rememberScannerDocumento { vm.anexarDocumento(remessaId, it) }
    if (notaManual) DialogoNotaManual(
        aoFechar = { notaManual = false },
        aoSalvar = { vm.anexarDocumento(remessaId, it) },
    )

    val remessa = e.remessa
    val formato = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault())

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TechTopAppBar(
                title = remessa?.remessa?.codigo ?: "Remessa",
                onBack = aoVoltar,
                eyebrow = "COLD CHAIN  /  SHIPMENT",
                actions = {
                    IconButton(onClick = aoAbrirRelatorio) {
                        Icon(Icons.Default.Download, "Laudo")
                    }
                },
            )
        }
    ) { padding ->
        if (remessa == null) {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { Text("Carregando…") }
            return@Scaffold
        }

        val perfil = PerfilTermico.porCodigo(remessa.remessa.perfilTermicoCodigo)

        Column(
            Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Secao("Remessa") {
                LinhaInfo("Situação", remessa.remessa.status.rotulo, destaque = true)
                LinhaInfo("Destinatário", remessa.remessa.destinatario)
                LinhaInfo("Transportadora", remessa.remessa.transportadora.ifBlank { "—" })
                LinhaInfo("Perfil térmico", "${perfil.rotulo}")
                LinhaInfo("Intervalo", "${remessa.remessa.intervaloSegundos / 60} min")
                remessa.documentos.forEach {
                    LinhaInfo(it.tipo.rotulo, it.numero.ifBlank { "—" })
                    if (it.digitadoManualmente) Text("Informado manualmente", style = MaterialTheme.typography.labelSmall)
                }
                if (remessa.documentos.isEmpty()) {
                    Button(onClick = { scannerNota.escanear() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Bipar nota fiscal")
                    }
                    OutlinedButton(onClick = { notaManual = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Digitar nota")
                    }
                }
                e.mensagem?.let { Text(it) }
            }

            Text(
                "Volumes monitorados",
                style = MaterialTheme.typography.titleMedium,
            )
            Text("Para coletar novamente, toque em Checkpoint e aproxime a etiqueta.",
                style = MaterialTheme.typography.bodyMedium)

            remessa.volumes.filter { it.monitorado }.forEach { volume ->
                val sessao = e.sessoes.firstOrNull { it.sessao.volumeId == volume.id }
                CartaoVolume(
                    volume = volume,
                    etiqueta = volume.etiquetaId?.let { e.etiquetas[it] },
                    sessao = sessao,
                    resumo = sessao?.let { e.resumos[it.sessao.id] },
                    estado = sessao?.let { e.estados[it.sessao.id] } ?: EstadoEtiqueta.NAO_ATIVADA,
                    aoLer = { tipo -> aoLer(volume.id, tipo) },
                )
            }

            if (e.ocorrencias.isNotEmpty()) {
                Secao("Ocorrências (${e.ocorrencias.size})") {
                    e.ocorrencias.forEach { item ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { aoAbrirOcorrencia(item.ocorrencia.id) }
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(item.ocorrencia.titulo,
                                    style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    item.ocorrencia.status.rotulo +
                                        if (item.acoes.isEmpty()) " · sem ação registrada"
                                        else " · ${item.acoes.size} ação(ões)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            item.ocorrencia.gravidade?.let { SeloGravidade(it) }
                        }
                    }
                }
            }

            // ---- Linha do tempo: custódia E coletas, no mesmo eixo ----
            //
            // Antes só a custódia aparecia aqui, e as coletas ficavam
            // escondidas dentro do cartão do volume. Quem confere uma carga
            // pergunta "o que aconteceu com ela, em que ordem" — e a resposta
            // mistura troca de responsável com leitura de etiqueta. Separar os
            // dois em lugares diferentes da tela obrigava o operador a montar
            // a ordem de cabeça.
            val eventos = remember(e.custodia, e.sessoes, e.remessa, e.resumos) {
                montarLinhaDoTempo(e)
            }
            if (eventos.isNotEmpty()) {
                Secao("Linha do tempo") {
                    eventos.forEach { evento ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Box(
                                Modifier.padding(top = 5.dp).size(8.dp)
                                    .clip(CircleShape).background(evento.cor)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(evento.titulo, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    formato.format(Instant.ofEpochMilli(evento.instanteMillis)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                evento.detalhe?.let {
                                    Text(it, style = MaterialTheme.typography.labelSmall)
                                }
                                // A diferença entre os dois instantes é dado de
                                // auditoria, não detalhe de implementação.
                                evento.lancadoEmMillis?.let { lancado ->
                                    if (lancado - evento.instanteMillis > 60_000) {
                                        Text(
                                            "lançado em ${formato.format(Instant.ofEpochMilli(lancado))}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = CinzaNeutro,
                                        )
                                    }
                                }
                                evento.observacao?.let {
                                    Text(it, style = MaterialTheme.typography.labelSmall)
                                }
                                // Sem isto, a divergência só aparecia na tela de
                                // coleta, no momento em que acontece. Quem abre a
                                // remessa depois — que é quem assina o laudo —
                                // não via nada.
                                evento.alerta?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AmbarAlerta,
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                Secao("Linha do tempo") {
                    Text(
                        "Nada registrado ainda. A ativação e cada coleta concluída " +
                            "aparecem aqui, em ordem.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Button(onClick = aoAbrirRelatorio, modifier = Modifier.fillMaxWidth()) {
                Text("Ver laudo e exportar Excel")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CartaoVolume(
    volume: VolumeEntity,
    etiqueta: EtiquetaEntity?,
    sessao: SessaoComLeituras?,
    resumo: ResumoTermico?,
    estado: EstadoEtiqueta,
    aoLer: (TipoLeitura) -> Unit,
) {
    val leituras = sessao?.leituras.orEmpty()
    val checkpoints = leituras.count { it.tipo == TipoLeitura.CHECKPOINT }
    val temFinal = leituras.any { it.tipo == TipoLeitura.FINAL }
    val ativada = sessao != null
    val coletasAConferir = remember(leituras) {
        leituras.count { !com.thermotrace.app.nfc.Reconciliacao.comparar(it.respostaBruta, it.temperaturas).conferida }
    }

    Secao("Volume ${volume.sequencia}${volume.codigoExterno?.let { " · $it" } ?: ""}") {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                etiqueta?.serial ?: "Sem etiqueta vinculada",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (coletasAConferir > 0) Text("A conferir", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelLarge)
            else SeloResultado(resumo?.resultado ?: ResultadoTermico.SEM_LEITURA)
        }
        if (coletasAConferir > 0) Text("$coletasAConferir coleta(s) precisam de conferência. Consulte o histórico abaixo.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)

        if (estado.situacao != com.thermotrace.app.domain.SituacaoEtiqueta.NUNCA_ATIVADA) {
            Spacer(Modifier.height(12.dp))
            // "A etiqueta está ativa?" — o cartão diz o estado E o quanto ele
            // é confiável. Verificar de perto exige aproximar o telefone, o que
            // o operador faz pelo botão de Checkpoint.
            CartaoEstadoEtiqueta(estado = estado)
        }

        Spacer(Modifier.height(14.dp))
        TrilhoLeituras(
            ativacaoFeita = ativada,
            checkpoints = checkpoints,
            finalFeita = temFinal,
            etapaAtual = null,
        )
        Spacer(Modifier.height(14.dp))

        // Status da última coleta.
        //
        // Sem isto, "ja bipei este volume, e quando?" so se responde entrando
        // na tela de leitura — e o operador de doca decide isso olhando o
        // cartao do volume, nao navegando. A temperatura mostrada aqui e a
        // medida instantanea daquela aproximacao, nao a serie: e o valor que
        // ele pode conferir contra um termometro aferido na hora.
        leituras.maxByOrNull { it.lidaEmMillis }?.let { ultima ->
            val quando = remember(ultima.id) {
                DateTimeFormatter.ofPattern("dd/MM HH:mm")
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochMilli(ultima.lidaEmMillis))
            }
            LinhaInfo("Última coleta", "${ultima.tipo.rotulo} · $quando")
            ultima.temperaturaInstantaneaC?.let { t ->
                LinhaInfo("Temperatura na coleta", "%.1f °C".format(t))
            }
            LinhaInfo("Coletas feitas", "${leituras.size}")
            Spacer(Modifier.height(10.dp))
        }

        resumo?.takeIf { it.quantidadeMedicoes > 0 }?.let {
            LinhaInfo("Registros", "${it.quantidadeMedicoes}")
            LinhaInfo(
                "Mínima / máxima",
                "%.1f / %.1f °C".format(it.minimaC ?: 0.0, it.maximaC ?: 0.0)
            )
            it.mktC?.let { mkt -> LinhaInfo("MKT", "%.2f °C".format(mkt)) }
            if (it.tempoForaFaixaSegundos > 0) {
                LinhaInfo(
                    "Tempo fora da faixa",
                    RegrasTermicas.formatarDuracao(it.tempoForaFaixaSegundos),
                    destaque = true,
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        // As três ações, sempre visíveis, habilitadas conforme a regra.
        // Deixar o botão desabilitado à vista é melhor que escondê-lo: o
        // operador aprende o ciclo olhando a tela.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!ativada) {
                Button(
                    onClick = { aoLer(TipoLeitura.ATIVACAO) },
                    modifier = Modifier.weight(1f),
                ) { Text("Ativar") }
            } else {
                OutlinedButton(
                    onClick = { aoLer(TipoLeitura.CHECKPOINT) },
                    enabled = !temFinal,
                    modifier = Modifier.weight(1f),
                ) { Text("Checkpoint") }
                Button(
                    onClick = { aoLer(TipoLeitura.FINAL) },
                    enabled = !temFinal,
                    modifier = Modifier.weight(1f),
                ) { Text(if (temFinal) "Encerrado" else "Leitura final") }
            }
        }

        if (volume.status == StatusVolume.SEM_ETIQUETA) {
            Spacer(Modifier.height(6.dp))
            Text(
                "A etiqueta é escolhida pelo código impresso, e o celular confere o UID " +
                    "antes de gravar qualquer coisa.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        sessao?.let {
            HistoricoColetas(leituras, it.sessao.minConfiguradoC, it.sessao.maxConfiguradoC)
        }
    }
}

/**
 * Um evento na linha do tempo da remessa.
 *
 * Custódia e coleta têm formas diferentes no banco — uma é troca de
 * responsável, a outra é leitura de etiqueta — mas para quem confere a carga
 * são a mesma coisa: algo que aconteceu, numa hora, com um responsável.
 */
private data class EventoLinhaDoTempo(
    val instanteMillis: Long,
    val titulo: String,
    val detalhe: String? = null,
    val lancadoEmMillis: Long? = null,
    val observacao: String? = null,
    val cor: androidx.compose.ui.graphics.Color,
    /** Divergência entre o cabeçalho da etiqueta e a série, quando houver. */
    val alerta: String? = null,
)

/**
 * Funde custódia e coletas num eixo único, em ordem cronológica.
 *
 * A cor separa os dois tipos sem precisar de rótulo: azul para custódia,
 * verde para coleta conforme, vermelho para coleta com excursão.
 */
private fun montarLinhaDoTempo(e: RemessaUiState): List<EventoLinhaDoTempo> {
    val rotuloDoVolume = e.remessa?.volumes.orEmpty().associate { v ->
        v.id to ("Volume " + v.sequencia + (v.codigoExterno?.let { " · $it" } ?: ""))
    }

    val custodia = e.custodia.map { evento ->
        EventoLinhaDoTempo(
            instanteMillis = evento.ocorridoEmMillis,
            titulo = "Custódia → ${evento.para}",
            lancadoEmMillis = evento.registradoEmMillis,
            observacao = evento.observacao,
            cor = AzulClaro,
        )
    }

    val coletas = e.sessoes.flatMap { sessao ->
        val volume = rotuloDoVolume[sessao.sessao.volumeId] ?: "Volume"
        sessao.leituras.map { leitura ->
            // Cada coleta carrega o que foi baixado naquela aproximação e o
            // que o termômetro dizia na hora. É o que permite reconstruir a
            // história sem abrir o banco.
            val partes = buildList {
                add("$volume")
                if (leitura.quantidadeMedida > 0) add("${leitura.quantidadeMedida} registros")
                leitura.temperaturaInstantaneaC?.let { add("%.1f °C".format(it)) }
            }
            // Recalculada do bruto imutável, não lida de coluna: assim vale
            // para leitura antiga, gravada antes de a conferência existir.
            // Mesmo criterio do contador "coleta(s) precisam de conferencia"
            // do cartao do volume: "nao avaliavel" tambem conta, porque tambem
            // significa que ninguem conferiu aquela leitura.
            val conferencia = runCatching {
                com.thermotrace.app.nfc.Reconciliacao.comparar(leitura.respostaBruta, leitura.temperaturas)
            }.getOrNull()
            val pendente = conferencia?.conferida == false
            val motivo = conferencia?.takeIf { !it.conferida }?.explicacao
            EventoLinhaDoTempo(
                instanteMillis = leitura.lidaEmMillis,
                titulo = leitura.tipo.rotulo,
                detalhe = partes.joinToString(" · "),
                cor = when {
                    pendente -> AmbarAlerta
                    e.resumos[sessao.sessao.id]?.resultado == ResultadoTermico.EXCURSAO -> VermelhoExcursao
                    else -> VerdeConforme
                },
                alerta = motivo,
            )
        }
    }

    return (custodia + coletas).sortedBy { it.instanteMillis }
}
