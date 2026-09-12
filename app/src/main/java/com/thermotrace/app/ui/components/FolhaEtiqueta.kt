package com.thermotrace.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.thermotrace.app.domain.PerfilTermico
import com.thermotrace.app.domain.RegrasTermicas
import com.thermotrace.app.ui.theme.AmbarAlerta
import com.thermotrace.app.ui.theme.CinzaNeutro
import com.thermotrace.app.ui.theme.VerdeConforme
import com.thermotrace.app.ui.theme.VerdeFundo
import com.thermotrace.app.ui.theme.VermelhoExcursao
import com.thermotrace.app.ui.theme.VermelhoFundo

/**
 * O que aparece quando o operador encosta uma etiqueta.
 *
 * **A folha tem exatamente um botão grande.** Qual botão é, a própria etiqueta
 * responde: parada e livre → *Ativar*; com remessa aberta → *Finalizar*. O
 * operador não escolhe entre ações, não preenche formulário e não decide
 * perfil térmico com a caixa aberta na frente dele — isso já foi decidido uma
 * vez, nos Ajustes.
 *
 * O ciclo inteiro em campo virou:
 *
 *     bipar → Ativar        (um toque; o celular vibra quando confirma)
 *     bipar a nota fiscal   (câmera, na tela inicial)
 *     bipar → Finalizar     (um toque; sai o veredito)
 *
 * O formulário não sumiu: virou "Alterar", fechado por padrão. Quem precisa de
 * um perfil diferente do padrão continua alcançando em um toque; quem não
 * precisa — que é o caso normal — nunca vê.
 */

/** O que o operador escolheu fazer com a etiqueta. */
sealed interface AcaoEtiqueta {
    /** Liga usando o perfil e a duração que já estão na folha. */
    data object Ativar : AcaoEtiqueta
    /** Baixa o histórico e fecha a remessa. STOP é uma ação separada. */
    data object Finalizar : AcaoEtiqueta
    data object Parar : AcaoEtiqueta
    data object CriarRemessa : AcaoEtiqueta
    data object AbrirRemessa : AcaoEtiqueta
    data object Fechar : AcaoEtiqueta
}

data class EtiquetaBipada(
    val uid: String,
    val serial: String?,          // null = ainda não cadastrada
    val registrando: Boolean,
    val temperaturaC: Double?,
    val tensaoV: Double?,
    val remessaVinculada: String?,  // código da remessa, se já estiver em uso
    val documentoVinculado: Boolean = false,
    val tecnologia: String? = null, // "Type-A  14443" | "Type-V  15693"
    val ocupada: Boolean = false,   // executando algo agora
    val mensagem: String? = null,
) {
    /** Tem remessa aberta esperando encerramento. */
    val podeFinalizar: Boolean get() = remessaVinculada != null && documentoVinculado
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FolhaEtiqueta(
    etiqueta: EtiquetaBipada,
    serialDigitado: String,
    aoDigitarSerial: (String) -> Unit,
    perfilEscolhido: PerfilTermico,
    aoEscolherPerfil: (PerfilTermico) -> Unit,
    horas: String,
    aoMudarHoras: (String) -> Unit,
    intervaloSegundos: Int,
    fatorSeguranca: Double,
    tensaoMinimaV: Double,
    aoBiparNota: () -> Unit,
    aoDigitarNota: () -> Unit,
    aoAgir: (AcaoEtiqueta) -> Unit,
) {
    val estado = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var opcoesAbertas by remember { mutableStateOf(false) }

    val bateriaBaixa = etiqueta.tensaoV != null && etiqueta.tensaoV < tensaoMinimaV

    ModalBottomSheet(
        onDismissRequest = { aoAgir(AcaoEtiqueta.Fechar) },
        sheetState = estado,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                // Sem isto o conteudo e cortado em tela pequena: com as opcoes
                // abertas, o botao principal fica abaixo da dobra e o operador
                // nao consegue alcancar.
                .verticalScroll(rememberScrollState())
                .padding(20.dp, 0.dp, 20.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ---- 1. é esta mesmo? -------------------------------------
            Text(
                etiqueta.serial ?: "Etiqueta nova",
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                "UID ${etiqueta.uid}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ---- 2. está gravando? ------------------------------------
            val (cor, fundo, rotulo) = when {
                etiqueta.registrando -> Triple(VerdeConforme, VerdeFundo, "REGISTRANDO")
                else -> Triple(CinzaNeutro, VermelhoFundo.copy(alpha = 0.4f), "PARADA")
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Selo(rotulo, cor, fundo)
                Text(
                    // O estado foi lido agora, não presumido — e a tela diz isso.
                    "estado lido agora, na etiqueta",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LinhaLeitura(etiqueta, tensaoMinimaV)

            // ---- 3. o botão -------------------------------------------
            when {
                // A sequência operacional é etiqueta -> nota -> entrega.
                // Se o app reiniciou entre os passos, a pendência reaparece
                // aqui a partir do banco e a câmera continua acessível.
                etiqueta.remessaVinculada != null && !etiqueta.documentoVinculado -> {
                    BotaoPrincipal(
                        texto = "Bipar nota fiscal",
                        habilitado = !etiqueta.ocupada,
                        aoClicar = aoBiparNota,
                    )
                    OutlinedButton(onClick = aoDigitarNota, enabled = !etiqueta.ocupada,
                        modifier = Modifier.fillMaxWidth()) { Text("Digitar nota") }
                    Text(
                        "A ${etiqueta.remessaVinculada} já está monitorando. Vincule a " +
                            "DANFE antes de finalizar a entrega.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { aoAgir(AcaoEtiqueta.AbrirRemessa) },
                        enabled = !etiqueta.ocupada,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Abrir ${etiqueta.remessaVinculada}") }
                    if (etiqueta.registrando) {
                        TextButton(
                            onClick = { aoAgir(AcaoEtiqueta.Parar) },
                            enabled = !etiqueta.ocupada,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Parar registro sem baixar", color = AmbarAlerta) }
                    }
                }

                // Tem remessa aberta: o que falta é encerrar. Vale tanto para
                // a etiqueta ainda gravando quanto para a que já parou — nos
                // dois casos existe histórico dentro do chip para baixar.
                etiqueta.podeFinalizar -> {
                    BotaoPrincipal(
                        texto = if (etiqueta.ocupada) "Mantenha encostada…"
                        else "Finalizar entrega",
                        habilitado = !etiqueta.ocupada,
                    ) { aoAgir(AcaoEtiqueta.Finalizar) }

                    Text(
                        "Baixa o histórico da ${etiqueta.remessaVinculada}, desliga a " +
                            "etiqueta e emite o veredito — tudo neste encostar. " +
                            "Mantenha o celular parado até o segundo bipe.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    OutlinedButton(
                        onClick = { aoAgir(AcaoEtiqueta.AbrirRemessa) },
                        enabled = !etiqueta.ocupada,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Abrir ${etiqueta.remessaVinculada}") }

                    if (etiqueta.registrando) {
                        TextButton(
                            onClick = { aoAgir(AcaoEtiqueta.Parar) },
                            enabled = !etiqueta.ocupada,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Parar registro sem baixar", color = AmbarAlerta) }
                    }
                }

                // Gravando, mas sem remessa neste aparelho: foi ligada em
                // outro celular, ou a remessa foi apagada. Nao ha o que
                // finalizar aqui — o que resta e liberar a etiqueta.
                etiqueta.registrando -> {
                    Secao("Gravando sem remessa neste aparelho") {
                        Text(
                            "Esta etiqueta está registrando, mas não há remessa para ela " +
                                "aqui. Pode ter sido ligada em outro celular.\n\n" +
                                "Parar libera a etiqueta para um novo ciclo — e descarta o " +
                                "que ela gravou até agora.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { aoAgir(AcaoEtiqueta.Parar) },
                            enabled = !etiqueta.ocupada,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (etiqueta.ocupada) "Aproxime para parar…"
                                else "Parar registro"
                            )
                        }
                    }
                }

                // Parada e livre: o caminho normal, e o mais curto possivel.
                else -> {
                    BotaoPrincipal(
                        texto = when {
                            etiqueta.ocupada -> "Mantenha encostada…"
                            bateriaBaixa -> "Bateria baixa — troque a etiqueta"
                            else -> "Ativar etiqueta"
                        },
                        habilitado = !etiqueta.ocupada && !bateriaBaixa &&
                            horas.toIntOrNull()?.let { it > 0 } == true,
                    ) { aoAgir(AcaoEtiqueta.Ativar) }

                    ResumoDoPlano(
                        perfil = perfilEscolhido,
                        horas = horas,
                        intervaloSegundos = intervaloSegundos,
                        fatorSeguranca = fatorSeguranca,
                        aberto = opcoesAbertas,
                        aoAlternar = { opcoesAbertas = !opcoesAbertas },
                    )

                    AnimatedVisibility(visible = opcoesAbertas) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Secao("Alterar só desta vez") {
                                if (etiqueta.serial == null) {
                                    // Etiqueta nova nao exige digitacao: sem
                                    // codigo informado, ele sai do proprio UID.
                                    // Pedir o codigo impresso com a caixa aberta
                                    // na frente do operador era o passo que mais
                                    // travava o fluxo.
                                    OutlinedTextField(
                                        value = serialDigitado,
                                        onValueChange = aoDigitarSerial,
                                        label = { Text("Código impresso (opcional)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Spacer(Modifier.height(12.dp))
                                }

                                Text(
                                    "Perfil térmico",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                Spacer(Modifier.height(6.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    PerfilTermico.entries.forEach { p ->
                                        FilterChip(
                                            selected = perfilEscolhido == p,
                                            onClick = { aoEscolherPerfil(p) },
                                            label = { Text(p.faixa) },
                                        )
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    perfilEscolhido.exemplos,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )

                                Spacer(Modifier.height(12.dp))
                                OutlinedTextField(
                                    value = horas,
                                    onValueChange = {
                                        aoMudarHoras(it.filter { c -> c.isDigit() })
                                    },
                                    label = { Text("Duração prevista (horas)") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Number
                                    ),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }

                            // Nem todo caso e "ligar agora": as vezes o operador
                            // so quer abrir a remessa e ligar a etiqueta na hora
                            // de fechar a caixa.
                            OutlinedButton(
                                onClick = { aoAgir(AcaoEtiqueta.CriarRemessa) },
                                enabled = !etiqueta.ocupada,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Criar remessa sem ligar agora") }
                        }
                    }
                }
            }

            etiqueta.mensagem?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * O botão que resolve o bipe.
 *
 * Alto de propósito: é apertado de luva, com a outra mão segurando a caixa e
 * às vezes com o celular de lado. Um alvo de 44 dp erra.
 */
@Composable
private fun BotaoPrincipal(texto: String, habilitado: Boolean, aoClicar: () -> Unit) {
    Button(
        onClick = aoClicar,
        enabled = habilitado,
        modifier = Modifier.fillMaxWidth().height(60.dp),
    ) {
        Text(texto, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun LinhaLeitura(etiqueta: EtiquetaBipada, tensaoMinimaV: Double) {
    Secao("Leitura") {
        etiqueta.temperaturaC?.let {
            LinhaInfo("Temperatura", "%.2f °C".format(it), destaque = true)
        }
        etiqueta.tensaoV?.let {
            LinhaInfo("Bateria", "%.2f V".format(it), destaque = it < tensaoMinimaV)
            if (it < tensaoMinimaV) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Abaixo do mínimo de %.2f V. Não ative esta etiqueta."
                        .format(tensaoMinimaV),
                    style = MaterialTheme.typography.labelSmall,
                    color = VermelhoExcursao,
                )
            }
        }
        etiqueta.tecnologia?.let { LinhaInfo("Interface", it) }
        etiqueta.remessaVinculada?.let { LinhaInfo("Remessa", it, destaque = true) }
    }
}

/**
 * O que vai ser gravado, numa linha só.
 *
 * A v0.3 mostrava faixa, intervalo, registros e cobertura em quatro linhas,
 * sempre visíveis. Está tudo aqui ainda — resumido, com o detalhe abrindo
 * quando alguém pede. A prévia importa (a v0.1 gravava 1000 registros fixos e
 * a etiqueta parava no meio da viagem), mas ela é conferência, não decisão.
 */
@Composable
private fun ResumoDoPlano(
    perfil: PerfilTermico,
    horas: String,
    intervaloSegundos: Int,
    fatorSeguranca: Double,
    aberto: Boolean,
    aoAlternar: () -> Unit,
) {
    val h = horas.toIntOrNull()?.takeIf { it > 0 }
    val registros = RegrasTermicas.planejarQuantidade(
        java.time.Duration.ofHours((h ?: 1).toLong()),
        intervaloSegundos,
        fatorSeguranca,
    )

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (h == null) "Informe a duração prevista"
                else "${perfil.faixa} · ${intervaloSegundos / 60} min · $h h",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "$registros registros · cobre " +
                    RegrasTermicas.formatarDuracao(registros * intervaloSegundos.toLong()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = aoAlternar) { Text(if (aberto) "Fechar" else "Alterar") }
    }
}
