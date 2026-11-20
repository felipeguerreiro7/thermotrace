package com.thermotrace.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thermotrace.app.data.conta.SituacaoViagem
import com.thermotrace.app.data.conta.ViagemServidor
import com.thermotrace.app.domain.RegrasTermicas
import com.thermotrace.app.ui.theme.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val ptBR: java.util.Locale = java.util.Locale.forLanguageTag("pt-BR")
private val horario = DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.systemDefault())

private fun graus(v: Double) = "%.1f °C".format(ptBR, v)

/** Cor de fundo e de texto do selo; sempre acompanhada do rótulo escrito, nunca só a cor. */
private fun cores(s: SituacaoViagem): Pair<Color, Color> = when (s) {
    SituacaoViagem.CONFORME -> VerdeFundo to VerdeConforme
    SituacaoViagem.EXCURSAO -> VermelhoFundo to VermelhoExcursao
    SituacaoViagem.DIVERGENTE -> AmbarFundo to AmbarAlerta
    SituacaoViagem.EM_ANDAMENTO -> AzulGelo to CianoTrace
    SituacaoViagem.SEM_DADOS -> AzulGelo to CinzaNeutro
}

/**
 * TT-040: a viagem como o servidor a vê, somando o que todos os celulares enviaram.
 * Pode diferir do gráfico local quando outro aparelho coletou depois; por isso o cartão
 * diz de onde vem e quantas coletas considerou.
 */
/** Estado de uma consulta da viagem; a tela da remessa guarda um por sessão. */
data class EstadoViagemServidor(val viagem: ViagemServidor? = null, val carregando: Boolean = false, val erro: String? = null)

/**
 * [compacto]: na tela da remessa, mostra só a situação e os alertas graves até o operador
 * pedir os detalhes. Simulação e integridade quebrada nunca ficam escondidas.
 */
@Composable
fun ViagemServidorCard(viagem: ViagemServidor?, carregando: Boolean, erro: String?, aoAtualizar: () -> Unit,
                       compacto: Boolean = false) {
    var aberto by rememberSaveable { mutableStateOf(!compacto) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Viagem no servidor", style = if (compacto) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium)
            if (aberto) Text("Consolidada com as coletas recebidas de todos os celulares desta carga.",
                style = MaterialTheme.typography.bodySmall)
            if (carregando) LinearProgressIndicator(Modifier.fillMaxWidth())
            erro?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            viagem?.let { v ->
                Selo(v)
                if (aberto) Detalhes(v)
                else v.resumo?.let { r ->
                    Text("${"%.1f".format(ptBR, r.percentualNaFaixa)}% na faixa · " +
                        if (r.incluiLeituraFinal) "com leitura final" else "sem leitura final",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (compacto && viagem != null) TextButton(onClick = { aberto = !aberto }) {
                    Text(if (aberto) "Ocultar detalhes" else "Ver detalhes")
                }
                OutlinedButton(onClick = aoAtualizar, enabled = !carregando) {
                    Text(if (viagem == null) "Consultar viagem" else "Atualizar")
                }
            }
        }
    }
}

@Composable
private fun Selo(v: ViagemServidor) {
    val (fundo, texto) = cores(v.situacao)
    Box(Modifier.background(fundo, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp)
        .semantics { contentDescription = "Situação no servidor: ${v.situacao.rotulo}" }) {
        Text(v.situacao.rotulo, color = texto, fontWeight = FontWeight.Bold)
    }
    if (v.simulacao) Text("Viagem de SIMULAÇÃO: dados de demonstração, não de uma etiqueta real.",
        color = AmbarAlerta, fontWeight = FontWeight.Bold)
    if (!v.integridadeCadeia) Text("A conferência de integridade das coletas falhou no servidor. Não use esta viagem em laudo; avise o suporte.",
        color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
}

@Composable
private fun Detalhes(v: ViagemServidor) {
    Text(v.situacao.explicacao, style = MaterialTheme.typography.bodyMedium)

    val r = v.resumo
    if (r != null) {
        Text("${"%.1f".format(ptBR, r.percentualNaFaixa)}% das medições na faixa " +
            "(${graus(v.minC)} a ${graus(v.maxC)})")
        Text("Tempo fora da faixa: ${RegrasTermicas.formatarDuracao(r.tempoForaNominalS).let { if (it == "—") "nenhum" else it }}")
        Text("Mínima ${graus(r.minimaC)} · máxima ${graus(r.maximaC)} · ${r.quantidade} medições")
        GraficoTemperatura(v.medicoes, v.minC, v.maxC, intervaloEsperadoSegundos = v.intervaloSegundos, altura = 180.dp)
        v.excursoes.forEach { e ->
            Text("${if (e.acima) "Acima" else "Abaixo"} da faixa de ${horario.format(e.inicio)} a ${horario.format(e.fim)} · " +
                "${RegrasTermicas.formatarDuracao(e.duracaoNominalS)} · extremo ${graus(e.extremoC)}",
                color = VermelhoExcursao, style = MaterialTheme.typography.bodySmall)
        }
        Text("${r.coletasConsideradas} de ${v.coletasRecebidas} coleta(s) recebida(s) com medições" +
            if (r.incluiLeituraFinal) ", incluindo a leitura final." else "; leitura final ainda não recebida.",
            style = MaterialTheme.typography.bodySmall)
    }
    if (v.divergencias > 0) Text("${v.divergencias} medição(ões) em que as coletas discordam. Nada foi corrigido; confira no portal.",
        color = AmbarAlerta, style = MaterialTheme.typography.bodySmall)
    v.avisos.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    Text("Sem validação física: esta tela não libera a carga.", style = MaterialTheme.typography.bodySmall)
    Text("Hash da viagem: ${v.hashProjecao.take(16)}…", style = MaterialTheme.typography.bodySmall)
}
