package com.thermotrace.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
@Composable
fun ViagemServidorCard(viagem: ViagemServidor?, carregando: Boolean, erro: String?, aoAtualizar: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Viagem no servidor", style = MaterialTheme.typography.titleMedium)
            Text("Consolidada com as coletas recebidas de todos os celulares desta carga.",
                style = MaterialTheme.typography.bodySmall)
            if (carregando) LinearProgressIndicator(Modifier.fillMaxWidth())
            erro?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            viagem?.let { Conteudo(it) }
            OutlinedButton(onClick = aoAtualizar, enabled = !carregando) {
                Text(if (viagem == null) "Consultar viagem" else "Atualizar viagem")
            }
        }
    }
}

@Composable
private fun Conteudo(v: ViagemServidor) {
    val (fundo, texto) = cores(v.situacao)
    Box(Modifier.background(fundo, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp)
        .semantics { contentDescription = "Situação: ${v.situacao.rotulo}" }) {
        Text(v.situacao.rotulo, color = texto, fontWeight = FontWeight.Bold)
    }
    Text(v.situacao.explicacao, style = MaterialTheme.typography.bodyMedium)
    if (v.simulacao) Text("Viagem de SIMULAÇÃO: dados de demonstração, não de uma etiqueta real.",
        color = AmbarAlerta, fontWeight = FontWeight.Bold)
    if (!v.integridadeCadeia) Text("A conferência de integridade das coletas falhou no servidor. Não use esta viagem em laudo; avise o suporte.",
        color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)

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
