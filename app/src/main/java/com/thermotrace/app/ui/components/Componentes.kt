package com.thermotrace.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thermotrace.app.domain.GravidadeExcursao
import com.thermotrace.app.domain.Medicao
import com.thermotrace.app.domain.ResultadoTermico
import com.thermotrace.app.domain.TipoLeitura
import com.thermotrace.app.ui.theme.AmbarAlerta
import com.thermotrace.app.ui.theme.AmbarFundo
import com.thermotrace.app.ui.theme.CianoTrace
import com.thermotrace.app.ui.theme.CinzaNeutro
import com.thermotrace.app.ui.theme.VerdeConforme
import com.thermotrace.app.ui.theme.VerdeFundo
import com.thermotrace.app.ui.theme.VermelhoExcursao
import com.thermotrace.app.ui.theme.VermelhoFundo
import kotlin.math.max
import kotlin.math.min

// ---------------------------------------------------------------------
// Veredito
// ---------------------------------------------------------------------

@Composable
fun Selo(texto: String, cor: Color, fundo: Color, modifier: Modifier = Modifier) {
    val forma = RoundedCornerShape(50)
    Box(
        modifier
            .clip(forma)
            .background(fundo)
            .border(1.dp, cor.copy(alpha = 0.34f), forma)
            .padding(horizontal = 11.dp, vertical = 5.dp)
    ) {
        Text(
            texto.uppercase(),
            color = cor,
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 0.65.sp,
        )
    }
}

@Composable
fun SeloResultado(resultado: ResultadoTermico, modifier: Modifier = Modifier) {
    val (cor, fundo) = when (resultado) {
        ResultadoTermico.CONFORME -> VerdeConforme to VerdeFundo
        ResultadoTermico.ALERTA -> AmbarAlerta to AmbarFundo
        ResultadoTermico.EXCURSAO -> VermelhoExcursao to VermelhoFundo
        ResultadoTermico.SEM_LEITURA -> CinzaNeutro to Color(0x22808080)
    }
    Selo(resultado.rotulo, cor, fundo, modifier)
}

@Composable
fun SeloGravidade(gravidade: GravidadeExcursao, modifier: Modifier = Modifier) {
    val (cor, fundo) = when (gravidade) {
        GravidadeExcursao.ALERTA -> AmbarAlerta to AmbarFundo
        GravidadeExcursao.ACAO -> VermelhoExcursao to VermelhoFundo
        GravidadeExcursao.CRITICA -> Color.White to VermelhoExcursao
    }
    Selo(gravidade.rotulo, cor, fundo, modifier)
}

// ---------------------------------------------------------------------
// Blocos de layout
// ---------------------------------------------------------------------

@Composable
fun Secao(titulo: String, modifier: Modifier = Modifier, conteudo: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(13.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                titulo.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.05.sp,
            )
        }
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.54f)
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            shape = RoundedCornerShape(18.dp),
        ) {
            Column(Modifier.padding(16.dp)) { conteudo() }
        }
    }
}

@Composable
fun LinhaInfo(rotulo: String, valor: String, destaque: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            rotulo,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            valor,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (destaque) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.2f),
        )
    }
}

@Composable
fun Metrica(rotulo: String, valor: String, modifier: Modifier = Modifier, cor: Color? = null) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            valor,
            style = MaterialTheme.typography.titleLarge,
            color = cor ?: MaterialTheme.colorScheme.onSurface,
        )
        Text(
            rotulo,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------------
// O trilho dos três momentos de leitura
// ---------------------------------------------------------------------

/**
 * A espinha dorsal visual do app.
 *
 * Aparece em toda tela ligada a um volume monitorado, sempre igual, para que
 * o operador saiba em qualquer momento onde está no ciclo e o que falta.
 * Ativação e Final são cheios (obrigatórios); Checkpoint é tracejado
 * (opcional) — a forma comunica a regra antes de qualquer texto.
 */
@Composable
fun TrilhoLeituras(
    ativacaoFeita: Boolean,
    checkpoints: Int,
    finalFeita: Boolean,
    etapaAtual: TipoLeitura?,
    modifier: Modifier = Modifier,
) {
    val corAtiva = MaterialTheme.colorScheme.primary
    val corInativa = MaterialTheme.colorScheme.outline

    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Etapa(
            numero = "1",
            titulo = "Ativação",
            legenda = if (ativacaoFeita) "Concluída" else "Obrigatória",
            concluida = ativacaoFeita,
            atual = etapaAtual == TipoLeitura.ATIVACAO,
            opcional = false,
            modifier = Modifier.weight(1f),
        )
        Conector(ativacaoFeita, corAtiva, corInativa)
        Etapa(
            numero = if (checkpoints > 0) "$checkpoints" else "2",
            titulo = "Trajeto",
            legenda = when {
                checkpoints == 1 -> "1 checkpoint"
                checkpoints > 1 -> "$checkpoints checkpoints"
                else -> "Opcional"
            },
            concluida = checkpoints > 0,
            atual = etapaAtual == TipoLeitura.CHECKPOINT,
            opcional = true,
            modifier = Modifier.weight(1f),
        )
        Conector(finalFeita, corAtiva, corInativa)
        Etapa(
            numero = "3",
            titulo = "Recebimento",
            legenda = if (finalFeita) "Concluída" else "Obrigatória",
            concluida = finalFeita,
            atual = etapaAtual == TipoLeitura.FINAL,
            opcional = false,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Etapa(
    numero: String,
    titulo: String,
    legenda: String,
    concluida: Boolean,
    atual: Boolean,
    opcional: Boolean,
    modifier: Modifier = Modifier,
) {
    val cor = when {
        concluida -> VerdeConforme
        atual -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(34.dp).clip(CircleShape)
                .background(if (concluida || atual) cor else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            if (!concluida && !atual) {
                Canvas(Modifier.size(34.dp)) {
                    drawCircle(
                        color = cor,
                        style = Stroke(
                            width = 2.dp.toPx(),
                            pathEffect = if (opcional)
                                PathEffect.dashPathEffect(floatArrayOf(8f, 8f)) else null,
                        ),
                    )
                }
            }
            Text(
                if (concluida) "✓" else numero,
                color = if (concluida || atual) Color.White else cor,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(titulo, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
        Text(
            legenda,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Conector(ativo: Boolean, corAtiva: Color, corInativa: Color) {
    Box(
        Modifier.padding(top = 16.dp).width(20.dp).height(2.dp)
            .background(if (ativo) corAtiva else corInativa.copy(alpha = 0.5f))
    )
}

// ---------------------------------------------------------------------
// Gráfico
// ---------------------------------------------------------------------

/**
 * Temperatura × tempo.
 *
 * Canvas puro, sem biblioteca de gráficos: são 4.864 pontos no pior caso e a
 * única coisa que precisa aparecer é a linha, a faixa aceita e onde ela foi
 * violada. Uma dependência de charting aqui custaria mais do que resolve.
 *
 * A faixa é desenhada como área, não como duas linhas: o olho lê "dentro" e
 * "fora" sem precisar comparar valores no eixo.
 */
@Composable
fun GraficoTemperatura(
    medicoes: List<Medicao>,
    minC: Double,
    maxC: Double,
    modifier: Modifier = Modifier,
    altura: androidx.compose.ui.unit.Dp = 220.dp,
    intervaloEsperadoSegundos: Int? = null,
) {
    val serie = androidx.compose.runtime.remember(medicoes, intervaloEsperadoSegundos) {
        com.thermotrace.app.domain.prepararSerieGrafico(medicoes, intervaloEsperadoSegundos)
    }
    val pontos = serie.getOrNull().orEmpty()
    if (pontos.isEmpty() || !minC.isFinite() || !maxC.isFinite() || minC >= maxC) {
        Box(modifier.fillMaxWidth().height(altura), contentAlignment = Alignment.Center) {
            Text(
                if (medicoes.isEmpty()) "Sem medições ainda" else "Histórico ou faixa inválidos. Revise a leitura.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    // ---- Escala ancorada na faixa aprovada ----
    //
    // A versão anterior calculava a escala a partir dos extremos da série. No
    // ensaio de 12/09/2026 um ponto de −29,8 °C esticou a escala para 63 °C e
    // espremeu a faixa de 7 a 14 °C em 11% da altura: o critério que decide se
    // a carga passa virou uma tira fina. A faixa é o que o laudo julga, então é
    // ela que manda na escala. Extremos além disso são desenhados na borda com
    // marca de recorte e o valor real anotado — nunca escondidos.
    val folga = ((maxC - minC) * 0.35).coerceAtLeast(1.5)
    val escalaMin = minC - folga
    val escalaMax = maxC + folga
    val amplitude = escalaMax - escalaMin
    val minSerie = pontos.minOf { it.medicao.temperaturaC }
    val maxSerie = pontos.maxOf { it.medicao.temperaturaC }
    val recortados = pontos.count { it.medicao.temperaturaC < escalaMin || it.medicao.temperaturaC > escalaMax }

    val corFaixa = VerdeConforme.copy(alpha = 0.12f)
    val corLimite = VermelhoExcursao.copy(alpha = 0.55f)
    val corExcursao = VermelhoExcursao.copy(alpha = 0.14f)
    val corGrade = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val corTexto = MaterialTheme.colorScheme.onSurfaceVariant
    val zona = java.time.ZoneId.systemDefault()
    val formatoLongo = java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm:ss").withZone(zona)
    val formatoCurto = java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(zona)
    val lacunas = pontos.drop(1).any { it.iniciaTrecho }

    // ---- Leitura por toque ----
    //
    // Sem isto o gráfico só mostra forma: dá para ver que subiu, não quanto nem
    // quando. Arrastar move a linha de leitura e o valor aparece no topo, que é
    // onde o olho já está — não num balão que o próprio dedo cobre.
    var selecionado by androidx.compose.runtime.remember(pontos) {
        androidx.compose.runtime.mutableStateOf<Int?>(null)
    }
    val ponto = selecionado?.let { pontos.getOrNull(it) } ?: pontos.last()
    val temperatura = ponto.medicao.temperaturaC
    val foraDaFaixa = temperatura < minC || temperatura > maxC

    val densidade = androidx.compose.ui.platform.LocalDensity.current
    val paintRotulo = androidx.compose.runtime.remember(corTexto, densidade) {
        android.graphics.Paint().apply {
            color = corTexto.toArgb()
            textSize = with(densidade) { 9.sp.toPx() }
            isAntiAlias = true
        }
    }

    Column(modifier.fillMaxWidth()) {
        // Leitura de topo: quem só quer o número não precisa interpretar desenho.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "%.1f °C".format(temperatura),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (foraDaFaixa) VermelhoExcursao else VerdeConforme,
            )
            Text(
                (if (selecionado == null) "último · " else "ponto ${ponto.medicao.indice + 1} · ") +
                    formatoCurto.format(ponto.medicao.instante),
                style = MaterialTheme.typography.labelSmall,
                color = corTexto,
            )
        }
        Text(
            if (foraDaFaixa) "Fora da faixa aprovada" else "Dentro da faixa aprovada",
            style = MaterialTheme.typography.labelSmall,
            color = if (foraDaFaixa) VermelhoExcursao else corTexto,
        )
        Spacer(Modifier.height(6.dp))

        Canvas(
            Modifier.fillMaxWidth().height(altura)
                .pointerInput(pontos) {
                    fun maisProximo(x: Float): Int {
                        val fracao = (x / size.width).coerceIn(0f, 1f)
                        return pontos.indices.minByOrNull { kotlin.math.abs(pontos[it].fracaoTempo - fracao) } ?: 0
                    }
                    detectTapGestures { selecionado = maisProximo(it.x) }
                }
                .pointerInput(pontos) {
                    fun maisProximo(x: Float): Int {
                        val fracao = (x / size.width).coerceIn(0f, 1f)
                        return pontos.indices.minByOrNull { kotlin.math.abs(pontos[it].fracaoTempo - fracao) } ?: 0
                    }
                    detectHorizontalDragGestures(
                        onDragStart = { selecionado = maisProximo(it.x) },
                    ) { mudanca, _ -> selecionado = maisProximo(mudanca.position.x) }
                }
        ) {
            val margemEsquerda = 34.dp.toPx()
            val w = size.width - margemEsquerda
            val h = size.height
            fun x(fracao: Float) = margemEsquerda + w * fracao
            fun y(valor: Double) =
                (h * (1 - (valor.coerceIn(escalaMin, escalaMax) - escalaMin) / amplitude)).toFloat()

            val topo = y(maxC)
            val base = y(minC)

            // Trechos fora da faixa, sombreados: o resumo diz que houve excursão;
            // o gráfico passa a dizer ONDE.
            var i = 0
            while (i < pontos.size) {
                val fora = pontos[i].medicao.temperaturaC !in minC..maxC
                if (fora) {
                    var j = i
                    while (j + 1 < pontos.size && pontos[j + 1].medicao.temperaturaC !in minC..maxC) j++
                    val x0 = x(pontos[i].fracaoTempo)
                    val x1 = x(pontos[j].fracaoTempo)
                    drawRect(corExcursao, Offset(x0, 0f), Size((x1 - x0).coerceAtLeast(2.dp.toPx()), h))
                    i = j + 1
                } else i++
            }

            drawRect(corFaixa, Offset(margemEsquerda, topo), Size(w, base - topo))
            val tracejado = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
            drawLine(corLimite, Offset(margemEsquerda, topo), Offset(size.width, topo), 1.5.dp.toPx(), pathEffect = tracejado)
            drawLine(corLimite, Offset(margemEsquerda, base), Offset(size.width, base), 1.5.dp.toPx(), pathEffect = tracejado)
            drawLine(corGrade, Offset(margemEsquerda, h), Offset(size.width, h), 1.dp.toPx())
            drawLine(corGrade, Offset(margemEsquerda, 0f), Offset(margemEsquerda, h), 1.dp.toPx())

            // Eixo Y rotulado. Sem isto não se lê valor nenhum no gráfico.
            drawContext.canvas.nativeCanvas.apply {
                drawText("%.0f".format(escalaMax), 2f, paintRotulo.textSize, paintRotulo)
                drawText("%.0f".format(maxC), 2f, topo + paintRotulo.textSize / 3, paintRotulo)
                drawText("%.0f".format(minC), 2f, base + paintRotulo.textSize / 3, paintRotulo)
                drawText("%.0f".format(escalaMin), 2f, h - 2f, paintRotulo)
            }

            val caminho = Path().apply {
                pontos.forEach { p ->
                    val px = x(p.fracaoTempo)
                    val py = y(p.medicao.temperaturaC)
                    if (p.iniciaTrecho) moveTo(px, py) else lineTo(px, py)
                }
            }
            drawPath(caminho, CianoTrace, style = Stroke(width = 2.dp.toPx()))

            pontos.forEachIndexed { idx, p ->
                val valor = p.medicao.temperaturaC
                val fora = valor < minC || valor > maxC
                val recortado = valor < escalaMin || valor > escalaMax
                val px = x(p.fracaoTempo)
                val py = y(valor)
                if (fora || recortado || pontos.size == 1 || p.iniciaTrecho ||
                    pontos.getOrNull(idx + 1)?.iniciaTrecho == true
                ) {
                    drawCircle(if (fora) VermelhoExcursao else CianoTrace, radius = 3.dp.toPx(), center = Offset(px, py))
                }
                // Marca de recorte: o ponto saiu da escala, e isso precisa
                // aparecer. Esconder um extremo é pior que comprimir o gráfico.
                if (recortado) {
                    val sentido = if (valor > escalaMax) 1f else -1f
                    drawLine(VermelhoExcursao, Offset(px - 5.dp.toPx(), py + sentido * 4.dp.toPx()),
                        Offset(px, py), 2.dp.toPx())
                    drawLine(VermelhoExcursao, Offset(px + 5.dp.toPx(), py + sentido * 4.dp.toPx()),
                        Offset(px, py), 2.dp.toPx())
                }
            }

            // Linha de leitura.
            if (selecionado != null) {
                val px = x(ponto.fracaoTempo)
                drawLine(CianoTrace.copy(alpha = 0.7f), Offset(px, 0f), Offset(px, h), 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                drawCircle(if (foraDaFaixa) VermelhoExcursao else CianoTrace,
                    radius = 5.dp.toPx(), center = Offset(px, y(temperatura)))
            }
        }

        // Marcas de tempo intermediárias: numa viagem de dias, início e fim não
        // localizam quando a excursão aconteceu.
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            val primeiro = pontos.first().medicao.instante
            val ultimo = pontos.last().medicao.instante
            val meio = primeiro.plusSeconds((ultimo.epochSecond - primeiro.epochSecond) / 2)
            Text(formatoCurto.format(primeiro), style = MaterialTheme.typography.labelSmall, color = corTexto)
            Text(formatoCurto.format(meio), style = MaterialTheme.typography.labelSmall, color = corTexto)
            Text(formatoCurto.format(ultimo), style = MaterialTheme.typography.labelSmall, color = corTexto)
        }
        Text(
            "${zona.id} · faixa ${"%.1f".format(minC)} a ${"%.1f".format(maxC)} °C · ${pontos.size} registros · " +
                "série ${"%.1f".format(minSerie)} a ${"%.1f".format(maxSerie)} °C",
            style = MaterialTheme.typography.labelSmall,
            color = corTexto,
        )
        Text(
            "Toque ou arraste sobre o gráfico para ler cada ponto.",
            style = MaterialTheme.typography.labelSmall,
            color = corTexto,
        )
        if (recortados > 0) Text(
            "$recortados ponto(s) fora da escala, desenhados na borda com marca de recorte. " +
                "Extremo da série: ${"%.1f".format(minSerie)} a ${"%.1f".format(maxSerie)} °C.",
            style = MaterialTheme.typography.labelSmall, color = VermelhoExcursao,
        )
        if (lacunas) Text("Lacunas no histórico: os trechos não foram unidos.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
    }
}

// ---------------------------------------------------------------------
// Painel NFC
// ---------------------------------------------------------------------

@Composable
fun PainelNfc(
    titulo: String,
    instrucao: String,
    aguardando: Boolean,
    erro: String? = null,
    modifier: Modifier = Modifier,
) {
    val cor = when {
        erro != null -> MaterialTheme.colorScheme.error
        aguardando -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (erro != null) VermelhoFundo
            else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.68f)
        ),
        border = BorderStroke(
            1.dp,
            if (erro != null) VermelhoExcursao.copy(alpha = 0.48f)
            else MaterialTheme.colorScheme.primary.copy(alpha = 0.32f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (erro != null) "NFC  /  LINK ERROR" else "NFC  /  LIVE LINK",
                style = MaterialTheme.typography.labelSmall,
                color = cor,
                letterSpacing = 1.2.sp,
            )
            Spacer(Modifier.height(10.dp))
            Canvas(Modifier.size(56.dp)) {
                // Ondas de NFC. Três arcos, do menor para o maior.
                listOf(0.35f, 0.62f, 0.9f).forEach { escala ->
                    drawArc(
                        color = cor.copy(alpha = if (aguardando) 1f else 0.4f),
                        startAngle = -50f, sweepAngle = 100f, useCenter = false,
                        topLeft = Offset(
                            size.width * (1 - escala) / 2,
                            size.height * (1 - escala) / 2
                        ),
                        size = Size(size.width * escala, size.height * escala),
                        style = Stroke(width = 3.dp.toPx()),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(titulo, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(
                erro ?: instrucao,
                style = MaterialTheme.typography.bodyMedium,
                color = if (erro != null) VermelhoExcursao
                else MaterialTheme.colorScheme.onPrimaryContainer,
                textAlign = TextAlign.Center,
                fontSize = 14.sp,
            )
        }
    }
}
