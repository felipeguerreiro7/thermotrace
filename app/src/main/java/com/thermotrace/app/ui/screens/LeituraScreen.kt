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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thermotrace.app.domain.RegrasTermicas
import com.thermotrace.app.domain.TipoLeitura
import com.thermotrace.app.ui.components.CartaoEstadoEtiqueta
import com.thermotrace.app.ui.components.GraficoTemperatura
import com.thermotrace.app.ui.components.LinhaInfo
import com.thermotrace.app.ui.components.PainelNfc
import com.thermotrace.app.ui.components.Secao
import com.thermotrace.app.ui.components.SeloResultado
import com.thermotrace.app.ui.components.TrilhoLeituras
import com.thermotrace.app.ui.theme.VerdeConforme
import com.thermotrace.app.ui.components.TechTopAppBar
import com.thermotrace.app.nfc.NfcOperator
import com.thermotrace.app.nfc.rememberNfcOperator

/**
 * A tela de leitura. Uma só, para os três momentos.
 *
 * Por que uma tela e não três: o gesto físico é idêntico — conferir a
 * identidade e encostar o telefone. O que muda é o que acontece depois, e
 * isso o `tipo` resolve. Três telas quase iguais divergiriam na primeira
 * correção, e a checagem de identidade acabaria implementada em duas delas.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeituraScreen(
    volumeId: String,
    tipo: TipoLeitura,
    aoVoltar: () -> Unit,
    aoConcluir: () -> Unit,
    vm: LeituraViewModel,
) {
    val estado by vm.estado.collectAsStateWithLifecycle()

    LaunchedEffect(volumeId, tipo) { vm.carregar(volumeId, tipo) }

    // O reader mode acompanha o ciclo de vida da tela: ligado enquanto ela
    // esta resumida, desligado quando pausa. Ver NfcCompose.
    val operador = rememberNfcOperator { evento -> vm.aoEventoNfc(evento) }

    LaunchedEffect(estado.uidEsperado, estado.operacaoPendente) {
        operador.expectedUid = estado.uidEsperado
        estado.operacaoPendente?.let { operador.request(it) }
    }
    LaunchedEffect(estado.concluido) { if (estado.concluido) aoConcluir() }

    // Guarda de saída.
    //
    // Baixar o histórico não registra nada: a evidência só é persistida em
    // `confirmar()`. No ensaio de 12/09/2026 várias coletas foram baixadas,
    // conferidas na tela e perdidas na volta — a etiqueta gravando, o app
    // dizendo SEM LEITURA, e nenhum aviso no caminho. Sair com coleta pendente
    // passa a exigir uma confirmação explícita.
    var confirmandoSaida by remember { mutableStateOf(false) }
    val temColetaPendente = estado.podeFinalizar && !estado.registrada && !estado.concluido
    val ocupado = estado.processando || estado.salvando
    val sairComCuidado = {
        if (!ocupado) {
            if (temColetaPendente) confirmandoSaida = true else aoVoltar()
        }
    }
    BackHandler(enabled = temColetaPendente || ocupado) {
        if (!ocupado) confirmandoSaida = true
    }

    if (confirmandoSaida) {
        AlertDialog(
            onDismissRequest = { confirmandoSaida = false },
            title = { Text("Coleta não registrada") },
            text = {
                Text(
                    "O histórico foi baixado da etiqueta, mas ainda não foi registrado. " +
                        "Se você sair agora, esta coleta é descartada e não entra no laudo " +
                        "nem na linha do tempo. A etiqueta continua registrando."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmandoSaida = false; vm.confirmar() }) {
                    Text("Registrar e sair")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmandoSaida = false; aoVoltar() }) {
                    Text("Descartar")
                }
            },
        )
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TechTopAppBar(
                title = tipo.rotulo,
                onBack = sairComCuidado,
                eyebrow = "NFC  /  SECURE CAPTURE",
            )
        },
        // A ação de registrar não pode depender de rolagem. Ela fechava a
        // coluna, depois do resultado e do gráfico: numa tela longa, quem
        // conferia o veredito e voltava perdia a coleta. Barra fixa resolve
        // sem reordenar o conteúdo, que tem ordem própria (ler, conferir,
        // assinar).
        bottomBar = {
            if (estado.podeFinalizar) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(
                            "Coleta baixada e ainda NÃO registrada.",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = vm::confirmar, enabled = !ocupado, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                when (tipo) {
                                    TipoLeitura.ATIVACAO -> "Concluir ativação"
                                    TipoLeitura.CHECKPOINT -> "Registrar checkpoint"
                                    TipoLeitura.FINAL -> "Encerrar monitoramento"
                                }
                            )
                        }
                    }
                }
            } else if (estado.registrada && !estado.concluido) {
                // Checkpoint grava sozinho no bipe. A barra deixa de cobrar
                // ação e passa a confirmar o que já aconteceu, com a saída
                // explícita — o operador afasta o telefone e segue.
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(
                            "Coleta registrada.",
                            style = MaterialTheme.typography.labelLarge,
                            color = VerdeConforme,
                        )
                        if (tipo == TipoLeitura.FINAL) {
                            Text(when {
                                ocupado -> "Solicitando parada da etiqueta…"
                                estado.aguardandoTag -> "Aproxime a etiqueta para concluir a parada."
                                estado.etiquetaLiberada == true -> "STOP aceito pela etiqueta."
                                else -> "Parada não confirmada. O histórico está salvo."
                            }, style = MaterialTheme.typography.labelLarge)
                            if (!ocupado && !estado.aguardandoTag && estado.etiquetaLiberada != true) {
                                OutlinedButton(onClick = vm::pararRegistro, modifier = Modifier.fillMaxWidth()) {
                                    Text("Tentar parar a etiqueta")
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = aoVoltar, enabled = !ocupado, modifier = Modifier.fillMaxWidth()) {
                            Text("Voltar para a remessa")
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TrilhoLeituras(
                ativacaoFeita = estado.ativacaoFeita,
                checkpoints = estado.quantidadeCheckpoints,
                finalFeita = estado.finalFeita,
                etapaAtual = tipo,
            )

            Text(tipo.descricao, style = MaterialTheme.typography.bodyMedium)

            // "A etiqueta está ativa?" — com botão de verificar, que é uma
            // aproximação curta: lê só o bit de status, sem baixar a série.
            if (estado.ativacaoFeita && tipo != TipoLeitura.ATIVACAO) {
                CartaoEstadoEtiqueta(
                    estado = estado.estadoEtiqueta,
                    aoVerificar = vm::verificarEtiqueta,
                    verificando = estado.verificando,
                )
            }

            Secao("Volume") {
                LinhaInfo("Remessa", estado.codigoRemessa)
                LinhaInfo("Volume", estado.rotuloVolume)
                LinhaInfo("Perfil térmico", estado.perfil?.rotulo ?: "—")
                estado.etiquetaSerial?.let { LinhaInfo("Etiqueta", it, destaque = true) }
                estado.uidEsperado?.let { LinhaInfo("UID esperado", it) }
            }

            // ---- Passo 1 (só na ativação): identificar a etiqueta pelo QR ----
            if (tipo == TipoLeitura.ATIVACAO && estado.uidEsperado == null) {
                Secao("Identificar a etiqueta") {
                    Text(
                        "Escaneie ou digite o código impresso na etiqueta. O celular só " +
                            "vai gravar depois de confirmar que a etiqueta encostada é esta.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = estado.qrDigitado,
                        onValueChange = vm::alterarQr,
                        label = { Text("Código da etiqueta (ex.: TT-A7K9P2X4)") },
                        singleLine = true,
                        isError = estado.erroVinculo != null,
                        supportingText = estado.erroVinculo?.let { { Text(it) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = vm::vincular,
                            enabled = estado.qrDigitado.isNotBlank(),
                            modifier = Modifier.weight(1f),
                        ) { Text("Vincular ao volume") }
                    }
                }
            }

            // ---- Passo 2: encostar a etiqueta ----
            if (estado.uidEsperado != null || tipo != TipoLeitura.ATIVACAO) {
                PainelNfc(
                    // "Pronto" servia para dois momentos opostos: nada
                    // armado e coleta concluída. Cada estado tem nome próprio.
                    titulo = when {
                        estado.salvando -> "Registrando a coleta…"
                        estado.processando -> "Lendo… mantenha o celular parado"
                        estado.aguardandoTag -> "Aproxime o celular da etiqueta"
                        estado.registrada -> "Coleta registrada — pode afastar o celular"
                        estado.podeFinalizar -> "Histórico baixado — confira e registre abaixo"
                        else -> "Pronto para ler"
                    },
                    instrucao = when (tipo) {
                        TipoLeitura.ATIVACAO ->
                            "Não afaste durante a gravação. A sequência escreve a faixa " +
                                "térmica, o intervalo e o relógio, e depois confere."
                        TipoLeitura.CHECKPOINT ->
                            "O registro não é interrompido. Só baixamos o que já foi gravado."
                        TipoLeitura.FINAL ->
                            "Leia o histórico e toque em Encerrar monitoramento. O app salva " +
                                "a coleta antes de pedir a parada da etiqueta."
                    },
                    aguardando = estado.aguardandoTag || estado.processando,
                    erro = estado.erroNfc,
                )

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (tipo == TipoLeitura.ATIVACAO) {
                        OutlinedButton(
                            onClick = vm::identificar,
                            enabled = !ocupado && !estado.podeFinalizar,
                            modifier = Modifier.weight(1f),
                        ) { Text("Conferir etiqueta") }
                        Button(
                            onClick = vm::ativar,
                            enabled = estado.podeAtivar && !ocupado && !estado.podeFinalizar,
                            modifier = Modifier.weight(1f),
                        ) { Text("Ativar") }
                    } else {
                        Button(
                            onClick = vm::baixar,
                            enabled = !ocupado && !estado.podeFinalizar && !estado.finalFeita,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Baixar histórico") }
                    }
                }
            }

            // ---- Estado técnico da etiqueta ----
            estado.leituraInstantanea?.let { snapshot ->
                Secao("Etiqueta encostada") {
                    LinhaInfo("UID lido", snapshot.uid, destaque = true)
                    LinhaInfo(
                        "Temperatura agora",
                        snapshot.temperatureC?.let { "%.2f °C".format(it) } ?: "—"
                    )
                    LinhaInfo(
                        "Bateria",
                        snapshot.voltageV?.let { "%.2f V".format(it) } ?: "—",
                        destaque = (snapshot.voltageV ?: Double.MAX_VALUE) < estado.tensaoMinimaV,
                    )
                }
            }

            // ---- Plano de ativação, mostrado ANTES de gravar ----
            if (tipo == TipoLeitura.ATIVACAO) {
                estado.plano?.let { plano ->
                    Secao("O que será gravado na etiqueta") {
                        LinhaInfo("Faixa", "${plano.minC} a ${plano.maxC} °C")
                        LinhaInfo("Intervalo", "${plano.intervalSeconds / 60} min")
                        LinhaInfo("Capacidade programada", "${plano.loggingCount} registros")
                        LinhaInfo(
                            "Cobertura",
                            RegrasTermicas.formatarDuracao((plano.coverageHours * 3600).toLong()),
                            destaque = true,
                        )
                        LinhaInfo("Modo de armazenamento", plano.storageMode.name)
                    }
                }
            }

            // ---- Resultado da leitura ----
            // ---- STOP físico: só na leitura final, e sempre explícito ----
            //
            // Fechar a remessa no banco não prova que a etiqueta parou. Se o
            // STOP falhar, o chip continua gravando e não pode ser reusado —
            // e a única coisa pior que isso é o operador não saber.
            if (tipo == TipoLeitura.FINAL && estado.registrada) {
                Secao("Etiqueta") {
                    when (estado.etiquetaLiberada) {
                        true -> Text(
                            "STOP aceito pela etiqueta. Confira o estado antes do próximo ciclo.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = VerdeConforme,
                        )
                        false -> {
                            Text(
                                "O histórico está salvo, mas a parada da etiqueta não foi confirmada.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = vm::pararRegistro,
                                enabled = !ocupado,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Parar registro") }
                        }
                        null -> {
                            Text(
                                "O STOP não foi confirmado nesta leitura. Encoste a etiqueta " +
                                    "e pare o registro antes de liberá-la.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = vm::pararRegistro,
                                enabled = !ocupado,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Parar registro") }
                        }
                    }
                }
            }

            estado.resumo?.let { resumo ->
                Secao("Resultado") {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Veredito", style = MaterialTheme.typography.bodyMedium)
                        SeloResultado(resumo.resultado)
                    }
                    Spacer(Modifier.height(8.dp))
                    LinhaInfo("Registros baixados", "${resumo.quantidadeMedicoes}")
                    LinhaInfo(
                        "Mínima / máxima",
                        "%.1f / %.1f °C".format(resumo.minimaC ?: 0.0, resumo.maximaC ?: 0.0)
                    )
                    LinhaInfo("MKT", resumo.mktC?.let { "%.2f °C".format(it) } ?: "—")
                    LinhaInfo(
                        "Tempo fora da faixa",
                        RegrasTermicas.formatarDuracao(resumo.tempoForaFaixaSegundos),
                        destaque = resumo.tempoForaFaixaSegundos > 0,
                    )
                    LinhaInfo("Excursões", "${resumo.excursoes.size}")
                    estado.derivaSegundos?.let {
                        LinhaInfo("Diferença leitura/último ponto", "$it s")
                    }

                    // Confronto cabeçalho x série. Aparece só quando os dois
                    // discordam — um aviso que nunca cala é um aviso que
                    // ninguém lê. Ver Reconciliacao em SessionDecoder.
                    estado.reconciliacao?.explicacao?.let { texto ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            texto,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            "Esta conferência não permite concluir que os dados são coerentes. " +
                                "Investigue a evidência bruta antes de considerar o resultado conferido.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    GraficoTemperatura(
                        medicoes = estado.medicoes,
                        minC = estado.perfil?.minC?.toDouble() ?: 2.0,
                        maxC = estado.perfil?.maxC?.toDouble() ?: 8.0,
                    )
                }
            }

            if (estado.ocorrenciasNovas > 0) {
                Secao("Alerta") {
                    Text(
                        "${estado.ocorrenciasNovas} ocorrência(s) nova(s) registrada(s) " +
                            "e colocada(s) na fila de alerta. Abra em Ocorrências para " +
                            "enviar o e-mail e registrar a ação tomada.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            if (estado.mensagem != null) {
                Text(estado.mensagem!!, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
