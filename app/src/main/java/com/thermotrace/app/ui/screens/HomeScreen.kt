package com.thermotrace.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thermotrace.app.data.db.RemessaEntity
import com.thermotrace.app.data.repo.Repositorio
import com.thermotrace.app.data.repo.RepositorioAlertas
import com.thermotrace.app.domain.PerfilTermico
import com.thermotrace.app.domain.StatusRemessa
import com.thermotrace.app.nfc.NfcOperator
import com.thermotrace.app.nfc.rememberNfcOperator
import com.thermotrace.app.ui.components.AvisoNfc
import com.thermotrace.app.ui.components.CartaoEntregaConcluida
import com.thermotrace.app.ui.components.CartaoNotaFiscal
import com.thermotrace.app.ui.components.rememberScannerDocumento
import com.thermotrace.app.ui.components.DialogoNotaManual
import com.thermotrace.app.ui.components.FolhaEtiqueta
import com.thermotrace.app.ui.components.PainelNfc
import com.thermotrace.app.ui.components.Selo
import com.thermotrace.app.ui.components.TechTopAppBar
import com.thermotrace.app.ui.theme.AmbarAlerta
import com.thermotrace.app.ui.theme.AmbarFundo
import com.thermotrace.app.ui.theme.AzulGelo
import com.thermotrace.app.ui.theme.CianoTrace
import com.thermotrace.app.ui.theme.CinzaNeutro
import com.thermotrace.app.ui.theme.VerdeConforme
import com.thermotrace.app.ui.theme.VerdeFundo
import com.thermotrace.app.ui.theme.VermelhoExcursao
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    aoAbrirRemessa: (String) -> Unit,
    aoNovaRemessa: () -> Unit,
    aoAbrirEtiquetas: () -> Unit,
    aoAbrirOcorrencias: () -> Unit,
    aoAbrirAjustes: () -> Unit,
    aoAbrirDiagnostico: () -> Unit,
    aoAbrirTutorial: () -> Unit,
    aoAbrirLaudo: (String) -> Unit,
    aoAbrirConta: () -> Unit,
    contaVinculada: Boolean = false,
) {
    val remessas by vm.remessas.collectAsStateWithLifecycle()
    val visiveis by vm.remessasVisiveis.collectAsStateWithLifecycle()
    val contagem by vm.contagem.collectAsStateWithLifecycle()
    val pendentes by vm.pendentesEnvio.collectAsStateWithLifecycle()
    val ocorrencias by vm.ocorrenciasAbertas.collectAsStateWithLifecycle()
    val estado by vm.estado.collectAsStateWithLifecycle()
    var menuAberto by remember { mutableStateOf(false) }

    var alvoNota by remember { mutableStateOf<String?>(null) }
    var notaManual by remember { mutableStateOf(false) }
    val scannerNota = rememberScannerDocumento { doc ->
        alvoNota?.let { vm.anexarDocumento(doc, it) }
    }
    val biparNota: () -> Unit = {
        alvoNota = estado.emAberto?.id
        if (alvoNota != null) scannerNota.escanear()
    }
    val digitarNota: () -> Unit = {
        alvoNota = estado.emAberto?.id
        notaManual = alvoNota != null
    }
    if (notaManual) DialogoNotaManual(
        aoFechar = { notaManual = false },
        aoSalvar = { doc -> alvoNota?.let { vm.anexarDocumento(doc, it) } },
    )

    // O reader mode fica ligado enquanto a Home estiver visível, sem botão:
    // a etiqueta está na mão do operador, e exigir um toque na tela antes de
    // encostar inverte a ordem natural do gesto. Foi o app do fabricante que
    // mostrou isso.
    val operador = rememberNfcOperator { vm.aoEventoNfc(it) }

    // Descoberta livre até haver etiqueta na folha; a partir daí só ela pode
    // receber gravação.
    LaunchedEffect(estado.bipada?.uid) { operador.expectedUid = vm.uidEsperado() }

    LaunchedEffect(estado.operacaoPendente) {
        estado.operacaoPendente?.let {
            operador.request(it)
            vm.operacaoConsumida()
        }
    }

    // A Home promete que basta encostar, então a identificação precisa estar
    // armada desde a primeira composição e novamente depois de cada ciclo.
    // `awaitNextTag` não reaproveita o handle anterior; assim a mesma etiqueta
    // não reabre a folha antes de o operador conseguir bipar a nota.
    LaunchedEffect(estado.bipada == null) {
        if (estado.bipada == null) {
            operador.cancelarPendente()
            operador.expectedUid = null
            operador.awaitNextTag(NfcOperator.Operation.Identify)
        }
    }
    LaunchedEffect(estado.remessaParaAbrir) {
        estado.remessaParaAbrir?.let {
            vm.consumirNavegacao()
            aoAbrirRemessa(it)
        }
    }

    // Confirmacao antes de apagar: a remessa some com sessao, leituras e
    // custodia junto (CASCADE). Nao ha desfazer.
    estado.remessaParaApagar?.let { alvo ->
        AlertDialog(
            onDismissRequest = vm::cancelarApagar,
            title = { Text("Apagar ${alvo.codigo}?") },
            text = {
                Text(
                    "Some com a remessa, o volume, a sessão, as leituras e a linha do " +
                        "tempo. Não dá para desfazer.\n\n" +
                        "A etiqueta NÃO para de gravar por causa disso — se ela ainda " +
                        "estiver ativa, encoste e use Parar registro."
                )
            },
            confirmButton = {
                TextButton(onClick = vm::confirmarApagar) {
                    Text("Apagar", color = VermelhoExcursao)
                }
            },
            dismissButton = {
                TextButton(onClick = vm::cancelarApagar) { Text("Cancelar") }
            },
        )
    }

    estado.bipada?.let { bipada ->
        FolhaEtiqueta(
            etiqueta = bipada,
            serialDigitado = estado.serialDigitado,
            aoDigitarSerial = vm::alterarSerial,
            perfilEscolhido = estado.perfil,
            aoEscolherPerfil = vm::escolherPerfil,
            horas = estado.horas,
            aoMudarHoras = vm::alterarHoras,
            intervaloSegundos = estado.intervaloSegundos,
            fatorSeguranca = estado.fatorSeguranca,
            tensaoMinimaV = estado.tensaoMinimaV,
            aoBiparNota = biparNota,
            aoDigitarNota = digitarNota,
            aoAgir = vm::agir,
        )
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TechTopAppBar(
                title = "ThermoTrace",
                branded = true,
                actions = {
                    IconButton(onClick = aoAbrirOcorrencias) {
                        if (ocorrencias > 0) {
                            BadgedBox(badge = { Badge { Text("$ocorrencias") } }) {
                                Icon(Icons.Default.WarningAmber, "Ocorrências")
                            }
                        } else {
                            Icon(Icons.Default.WarningAmber, "Ocorrências")
                        }
                    }
                    IconButton(onClick = aoAbrirEtiquetas) {
                        Icon(Icons.Default.Sell, "Etiquetas")
                    }
                    // Diagnóstico e Ajustes ficam no menu, não na barra: são
                    // usados uma vez por semana, e cada ícone permanente a
                    // mais rouba espaço do que se usa trinta vezes por dia.
                    IconButton(onClick = { menuAberto = true }) {
                        Icon(Icons.Default.MoreVert, "Mais opções")
                    }
                    DropdownMenu(
                        expanded = menuAberto,
                        onDismissRequest = { menuAberto = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Conta e cargas") },
                            onClick = { menuAberto = false; aoAbrirConta() },
                        )
                        DropdownMenuItem(
                            text = { Text("Tutorial de uso") },
                            onClick = { menuAberto = false; aoAbrirTutorial() },
                        )
                        DropdownMenuItem(
                            text = { Text("Diagnóstico de etiqueta") },
                            onClick = { menuAberto = false; aoAbrirDiagnostico() },
                        )
                        DropdownMenuItem(
                            text = { Text("Ajustes") },
                            onClick = { menuAberto = false; aoAbrirAjustes() },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = aoNovaRemessa,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Nova remessa") },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TextButton(onClick = aoAbrirConta, modifier = Modifier.fillMaxWidth()) {
                Text(if (contaVinculada) "Histórico desta conta · envio online em integração"
                    else "Modo local · registros sem vínculo com empresa")
            }

            if (pendentes > 0) {
                // O app funciona sem rede de propósito. Mas o operador precisa
                // saber que ainda há evidência só no aparelho.
                Card(
                    Modifier.fillMaxWidth().padding(16.dp, 8.dp),
                    colors = CardDefaults.cardColors(containerColor = AmbarFundo),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        "$pendentes registro(s) preservados neste aparelho. " +
                            "O envio à conta online ainda está em integração; conexão sozinha não envia esta fila.",
                        Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AmbarAlerta,
                    )
                }
            }

            // Antes do painel de leitura: se o NFC estiver desligado, encostar
            // a etiqueta nao faz nada e o operador conclui que o app quebrou.
            AvisoNfc(Modifier.padding(16.dp, 8.dp))

            // ---- passo 3: o veredito, logo depois do bipe de encerramento --
            //
            // Vem antes do passo 2 na tela porque é o mais recente: quem
            // acabou de encerrar uma entrega quer ver o resultado, não o
            // lembrete de nota de outra remessa.
            estado.resultado?.let { r ->
                CartaoEntregaConcluida(
                    codigoRemessa = r.codigoRemessa,
                    veredito = r.veredito,
                    quantidadeMedicoes = r.quantidadeMedicoes,
                    minimaC = r.minimaC,
                    maximaC = r.maximaC,
                    mktC = r.mktC,
                    tempoForaFaixaSegundos = r.tempoForaFaixaSegundos,
                    ocorrenciasNovas = r.ocorrenciasNovas,
                    etiquetaLiberada = r.etiquetaLiberada,
                    aoVerLaudo = {
                        vm.dispensarResultado()
                        aoAbrirLaudo(r.remessaId)
                    },
                    aoDispensar = vm::dispensarResultado,
                    modifier = Modifier.padding(16.dp, 4.dp),
                )
            }

            // ---- passo 2: a nota fiscal da remessa recém-ligada ------------
            estado.emAberto?.let { aberta ->
                CartaoNotaFiscal(
                    codigoRemessa = aberta.codigo,
                    documento = aberta.documento,
                    etiquetaSerial = aberta.etiquetaSerial,
                    aoBiparNota = biparNota,
                    aoDigitarNota = digitarNota,
                    aoAbrirRemessa = {
                        vm.dispensarEmAberto()
                        aoAbrirRemessa(aberta.id)
                    },
                    aoDispensar = vm::dispensarEmAberto,
                    modifier = Modifier.padding(16.dp, 4.dp),
                )
            }

            // O painel de leitura fica no TOPO e sempre ativo. É o ponto de
            // partida do fluxo: bipar → ver estado → ligar → registrar.
            PainelNfc(
                titulo = "Aproxime a etiqueta",
                instrucao = "O celular lê o estado sem gravar nada. Cada aproximação " +
                    "executa uma única ação, como no aplicativo original.",
                aguardando = estado.bipada == null,
                modifier = Modifier.padding(16.dp, 12.dp),
            )

            estado.aviso?.let {
                Card(
                    Modifier.fillMaxWidth().padding(16.dp, 0.dp, 16.dp, 8.dp),
                    colors = CardDefaults.cardColors(containerColor = AmbarFundo),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        it, Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AmbarAlerta,
                    )
                }
            }

            if (remessas.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Nenhuma remessa ainda",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Encoste uma etiqueta para começar. Ela é cadastrada, ligada e " +
                            "vira uma remessa na mesma aproximação.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                // A busca só aparece quando a lista já é grande o bastante
                // para justificar: numa lista de três remessas ela é ruído
                // ocupando a altura de um cartão.
                if (remessas.size >= LIMIAR_BUSCA) {
                    OutlinedTextField(
                        value = estado.busca,
                        onValueChange = vm::buscar,
                        placeholder = { Text("Código, destinatário, transportadora, NF-e") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = {
                            if (estado.busca.isNotBlank()) {
                                IconButton(onClick = { vm.buscar("") }) {
                                    Icon(Icons.Default.Close, "Limpar busca")
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(16.dp, 0.dp, 16.dp, 8.dp),
                    )
                }

                LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 16.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(FiltroRemessa.entries.toList(), key = { it.name }) { filtro ->
                        val quantas = contagem.de(filtro)
                        FilterChip(
                            selected = estado.filtro == filtro,
                            onClick = { vm.filtrar(filtro) },
                            // O contador no chip evita o toque inútil: dá para
                            // ver que não há nada aguardando leitura sem trocar
                            // de recorte para descobrir.
                            label = { Text("${filtro.rotulo} · $quantas") },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))

                if (visiveis.isEmpty()) {
                    Column(
                        Modifier.fillMaxWidth().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "Nada neste recorte",
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (estado.busca.isBlank())
                                "Nenhuma remessa em \"${estado.filtro.rotulo}\"."
                            else
                                "Nenhuma remessa combina com \"${estado.busca}\" " +
                                    "em \"${estado.filtro.rotulo}\".",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        if (estado.filtro != FiltroRemessa.TODAS || estado.busca.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = {
                                vm.buscar("")
                                vm.filtrar(FiltroRemessa.TODAS)
                            }) { Text("Mostrar todas") }
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(visiveis, key = { it.id }) { remessa ->
                            CartaoRemessa(
                                remessa = remessa,
                                aoClicar = { aoAbrirRemessa(remessa.id) },
                                aoSegurar = { vm.pedirParaApagar(remessa) },
                            )
                        }
                        item { Spacer(Modifier.height(72.dp)) }
                    }
                }
            }
        }
    }
}

/** A partir de quantas remessas o campo de busca compensa o espaço que ocupa. */
private const val LIMIAR_BUSCA = 5

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CartaoRemessa(
    remessa: RemessaEntity,
    aoClicar: () -> Unit,
    aoSegurar: () -> Unit,
) {
    val perfil = PerfilTermico.porCodigo(remessa.perfilTermicoCodigo)
    val formato = DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.systemDefault())

    val (cor, fundo) = when (remessa.status) {
        StatusRemessa.CONCLUIDA -> VerdeConforme to VerdeFundo
        StatusRemessa.EM_PREPARACAO -> CinzaNeutro to AzulGelo
        StatusRemessa.ENTREGUE_AGUARDANDO_LEITURA -> AmbarAlerta to AmbarFundo
        else -> CianoTrace to AzulGelo
    }

    Card(
        Modifier.fillMaxWidth().combinedClickable(
            onClick = aoClicar,
            // Segurar apaga. Nao ha botao de lixeira no cartao de proposito:
            // o toque acidental numa lista de remessas em transporte seria
            // caro demais.
            onLongClick = aoSegurar,
        ),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)
        ),
        border = BorderStroke(1.dp, cor.copy(alpha = 0.20f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(remessa.codigo, style = MaterialTheme.typography.titleMedium)
                Selo(remessa.status.rotulo, cor, fundo)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                remessa.destinatario.ifBlank { "Destinatário não informado" },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "${perfil.rotulo} · ${remessa.transportadora.ifBlank { "sem transportadora" }}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            remessa.previsaoEntregaMillis?.let {
                Text(
                    "Entrega prevista: ${formato.format(Instant.ofEpochMilli(it))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
