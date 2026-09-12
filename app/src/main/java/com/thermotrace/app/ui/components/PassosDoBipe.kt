package com.thermotrace.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.thermotrace.app.domain.RegrasTermicas
import com.thermotrace.app.domain.ResultadoTermico
import com.thermotrace.app.ui.theme.AmbarAlerta
import com.thermotrace.app.ui.theme.AmbarFundo
import com.thermotrace.app.ui.theme.CinzaNeutro
import com.thermotrace.app.ui.theme.VerdeConforme
import com.thermotrace.app.ui.theme.VerdeFundo
import com.thermotrace.app.ui.theme.VermelhoExcursao
import com.thermotrace.app.ui.theme.VermelhoFundo

/**
 * Os passos 2 e 3 do fluxo de campo, na própria tela inicial.
 *
 * O ciclo é: bipar para ligar → bipar a nota → bipar para encerrar. O passo 1
 * e o 3 acontecem na folha da etiqueta; estes dois cartões são o que fica na
 * tela entre um bipe e outro.
 *
 * Ficam na Home, e não numa tela de detalhe, por um motivo prático: mandar o
 * operador para outra tela logo depois de ligar a etiqueta era o que fazia
 * ele deixar a nota fiscal "para depois" — e depois a caixa já saiu.
 */

/**
 * Passo 2 — a remessa está ligada e precisa da nota.
 *
 * Enquanto `documento` for nulo, este cartão é a única coisa colorida na
 * tela: a remessa existe, a etiqueta está gravando, e falta o documento que
 * dá identidade a ela para o cliente e para a fiscalização.
 */
@Composable
fun CartaoNotaFiscal(
    codigoRemessa: String,
    documento: String?,
    etiquetaSerial: String?,
    aoBiparNota: () -> Unit,
    aoDigitarNota: () -> Unit,
    aoAbrirRemessa: () -> Unit,
    aoDispensar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pronta = documento != null
    val cor = if (pronta) VerdeConforme else AmbarAlerta
    val fundo = if (pronta) VerdeFundo else AmbarFundo

    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = fundo),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Selo(if (pronta) "PRONTA PARA SEGUIR" else "FALTA A NOTA", cor, fundo)
                Text(
                    codigoRemessa,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                if (pronta) "Etiqueta ligada e nota anexada"
                else "Etiqueta ligada. Falta bipar a nota fiscal.",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (pronta)
                    "$documento · ${etiquetaSerial ?: "etiqueta"}. No destino, encoste a " +
                        "mesma etiqueta e toque em Finalizar entrega."
                else
                    "Aponte a câmera para o QR ou o código de barras da DANFE. É a nota " +
                        "que amarra a carga ao laudo — sem ela o laudo não tem dono.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.height(12.dp))
            if (pronta) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = aoAbrirRemessa,
                        modifier = Modifier.weight(1f),
                    ) { Text("Abrir remessa") }
                    TextButton(onClick = aoDispensar, modifier = Modifier.weight(1f)) {
                        Text("Ok")
                    }
                }
            } else {
                Button(
                    onClick = aoBiparNota,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Bipar nota fiscal") }
                OutlinedButton(onClick = aoDigitarNota, modifier = Modifier.fillMaxWidth()) { Text("Digitar nota") }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = aoAbrirRemessa, modifier = Modifier.weight(1f)) {
                        Text("Abrir remessa")
                    }
                    TextButton(onClick = aoDispensar, modifier = Modifier.weight(1f)) {
                        Text("Depois")
                    }
                }
            }
        }
    }
}

/**
 * Passo 3 — o veredito, logo depois do bipe de encerramento.
 *
 * Aparece no lugar em que o operador está olhando, com o resultado em uma
 * palavra. Os números vêm abaixo porque alguém vai querer conferir, mas a
 * decisão — aceitar ou segregar a carga — se toma pela cor.
 */
@Composable
fun CartaoEntregaConcluida(
    codigoRemessa: String,
    veredito: ResultadoTermico,
    quantidadeMedicoes: Int,
    minimaC: Double?,
    maximaC: Double?,
    mktC: Double?,
    tempoForaFaixaSegundos: Long,
    ocorrenciasNovas: Int,
    etiquetaLiberada: Boolean?,
    aoVerLaudo: () -> Unit,
    aoDispensar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (cor, fundo) = when (veredito) {
        ResultadoTermico.CONFORME -> VerdeConforme to VerdeFundo
        ResultadoTermico.ALERTA -> AmbarAlerta to AmbarFundo
        ResultadoTermico.EXCURSAO -> VermelhoExcursao to VermelhoFundo
        ResultadoTermico.SEM_LEITURA -> CinzaNeutro to Color(0x22808080)
    }

    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = fundo),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Entrega encerrada", style = MaterialTheme.typography.titleMedium)
                SeloResultado(veredito)
            }
            Text(
                codigoRemessa,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Metrica("mínima", minimaC?.let { "%.1f°".format(it) } ?: "—")
                Metrica("máxima", maximaC?.let { "%.1f°".format(it) } ?: "—")
                Metrica("MKT", mktC?.let { "%.1f°".format(it) } ?: "—")
                Metrica(
                    "fora da faixa",
                    RegrasTermicas.formatarDuracao(tempoForaFaixaSegundos),
                    cor = if (tempoForaFaixaSegundos > 0) VermelhoExcursao else null,
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                buildString {
                    append("$quantidadeMedicoes registros baixados da etiqueta")
                    if (etiquetaLiberada == true) append(" · etiqueta desligada e liberada")
                    if (ocorrenciasNovas > 0) {
                        append(" · $ocorrenciasNovas ocorrência(s) nova(s) na fila de alerta")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (etiquetaLiberada != true) {
                Spacer(Modifier.height(8.dp))
                Text(
                    if (etiquetaLiberada == null)
                        "Histórico salvo. Para liberar a etiqueta, encoste-a novamente " +
                            "e use Parar registro."
                    else
                        "A etiqueta não aceitou desligar. Encoste de novo e use Parar " +
                            "registro, senão ela não aceita um novo uso.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AmbarAlerta,
                )
            }

            if (veredito == ResultadoTermico.EXCURSAO) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Houve excursão. Não libere a carga sem a avaliação do responsável " +
                        "técnico — e registre a ação tomada em Ocorrências.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cor,
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = aoVerLaudo, modifier = Modifier.weight(1f)) {
                    Text("Ver laudo")
                }
                TextButton(onClick = aoDispensar, modifier = Modifier.weight(1f)) {
                    Text("Fechar")
                }
            }
        }
    }
}
