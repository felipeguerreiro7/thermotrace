package com.thermotrace.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.thermotrace.app.domain.Confianca
import com.thermotrace.app.domain.EstadoEtiqueta
import com.thermotrace.app.domain.RegrasTermicas
import com.thermotrace.app.domain.SituacaoEtiqueta
import com.thermotrace.app.ui.theme.AmbarAlerta
import com.thermotrace.app.ui.theme.AmbarFundo
import com.thermotrace.app.ui.theme.CinzaNeutro
import com.thermotrace.app.ui.theme.VerdeConforme
import com.thermotrace.app.ui.theme.VerdeFundo
import com.thermotrace.app.ui.theme.VermelhoExcursao
import com.thermotrace.app.ui.theme.VermelhoFundo
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * "A etiqueta está ativa?"
 *
 * O ponto pulsa **apenas** quando o estado foi confirmado agora, encostando o
 * telefone. Quando é presunção, o ponto fica parado e o texto diz há quanto
 * tempo foi a última verificação.
 *
 * Essa diferença é deliberada e é o núcleo da funcionalidade: um indicador que
 * pulsa "ao vivo" sem ter verificado nada seria mentira de interface — e a
 * mentira só apareceria no destino, com o laudo vazio.
 */
@Composable
fun CartaoEstadoEtiqueta(
    estado: EstadoEtiqueta,
    aoVerificar: (() -> Unit)? = null,
    verificando: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val (cor, fundo) = when (estado.situacao) {
        SituacaoEtiqueta.REGISTRANDO ->
            if (estado.verificacaoEnvelhecida) AmbarAlerta to AmbarFundo
            else VerdeConforme to VerdeFundo
        SituacaoEtiqueta.ENCERRADA -> CinzaNeutro to Color(0x22808080)
        SituacaoEtiqueta.NUNCA_ATIVADA -> CinzaNeutro to Color(0x22808080)
        SituacaoEtiqueta.START_NAO_CONFIRMADO,
        SituacaoEtiqueta.CAPACIDADE_ESGOTADA -> AmbarAlerta to AmbarFundo
        SituacaoEtiqueta.PARADA,
        SituacaoEtiqueta.BATERIA_BAIXA -> VermelhoExcursao to VermelhoFundo
    }

    val aoVivo = estado.confianca == Confianca.CONFIRMADA &&
        estado.situacao == SituacaoEtiqueta.REGISTRANDO

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(fundo)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PontoEstado(cor = cor, pulsando = aoVivo && !verificando)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    estado.situacao.rotulo,
                    style = MaterialTheme.typography.titleMedium,
                    color = cor,
                )
                Text(
                    when (estado.confianca) {
                        Confianca.CONFIRMADA -> "Confirmado agora, na etiqueta"
                        Confianca.PRESUMIDA -> estado.desdeVerificacao?.let {
                            "Presumido · última verificação há " +
                                RegrasTermicas.formatarDuracao(it.seconds)
                        } ?: "Presumido"
                        Confianca.DESCONHECIDA -> "Nunca verificado na etiqueta"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (estado.situacao != SituacaoEtiqueta.NUNCA_ATIVADA) {
            Spacer(Modifier.height(12.dp))

            // Ocupação da memória. A etiqueta para de gravar quando enche —
            // e isso acontece em silêncio, então precisa estar à vista.
            LinearProgressIndicator(
                progress = { estado.ocupacao },
                modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                color = cor,
                trackColor = cor.copy(alpha = 0.18f),
            )
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "${estado.registrosEsperados} de ${estado.capacidadeProgramada} registros",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                estado.autonomiaRestante?.let {
                    Text(
                        if (it.isZero) "memória cheia"
                        else "cabe mais ${RegrasTermicas.formatarDuracao(it.seconds)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            estado.ativadaEm?.let {
                LinhaInfo(
                    "Ativada em",
                    DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                        .withZone(ZoneId.systemDefault()).format(it)
                )
            }
            LinhaInfo("Intervalo", "${estado.intervaloSegundos / 60} min")
            estado.tensaoV?.let {
                LinhaInfo(
                    "Bateria",
                    "%.2f V".format(it),
                    destaque = it < EstadoEtiqueta.TENSAO_MINIMA_V,
                )
            }
            estado.ultimaTemperaturaC?.let {
                LinhaInfo("Última temperatura lida", "%.2f °C".format(it))
            }

            if (estado.verificacaoEnvelhecida &&
                estado.situacao == SituacaoEtiqueta.REGISTRANDO
            ) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Faz mais de um dia que ninguém confere esta etiqueta. Um checkpoint " +
                        "leva alguns segundos e transforma presunção em fato.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AmbarAlerta,
                )
            }

            if (aoVerificar != null) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = aoVerificar,
                    enabled = !verificando,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (verificando) "Aproxime a etiqueta…" else "Verificar agora")
                }
            }
        }
    }
}

@Composable
private fun PontoEstado(cor: Color, pulsando: Boolean) {
    val transicao = rememberInfiniteTransition(label = "pulso")
    val escala by transicao.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha",
    )

    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
        if (pulsando) {
            Box(
                Modifier.size(22.dp).alpha(1f - escala)
                    .clip(CircleShape).background(cor)
            )
        }
        Box(Modifier.size(11.dp).clip(CircleShape).background(cor))
    }
}
