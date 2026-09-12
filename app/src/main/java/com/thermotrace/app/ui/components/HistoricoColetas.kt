package com.thermotrace.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.thermotrace.app.data.db.LeituraEntity
import com.thermotrace.app.nfc.Reconciliacao
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HistoricoColetas(leituras: List<LeituraEntity>, minC: Double, maxC: Double) {
    var aberto by remember { mutableStateOf(false) }
    val ordenadas = remember(leituras) { leituras.sortedWith(compareByDescending<LeituraEntity> { it.lidaEmMillis }.thenBy { it.id }) }
    TextButton(onClick = { aberto = !aberto }, modifier = Modifier.fillMaxWidth()) {
        Text(if (aberto) "Ocultar coletas" else "Ver todas as coletas (${leituras.size})")
    }
    if (aberto) {
        Text("Cada coleta pode baixar novamente o mesmo histórico. As contagens não devem ser somadas.",
            style = MaterialTheme.typography.bodySmall)
        if (leituras.isEmpty()) Text("Nenhuma coleta registrada neste volume.")
        ordenadas.forEach { leitura ->
            key(leitura.id) { DetalheColeta(leitura, minC, maxC) }
        }
    }
}

@Composable
private fun DetalheColeta(l: LeituraEntity, minC: Double, maxC: Double) {
    var detalhes by remember(l.id) { mutableStateOf(false) }
    val conferencia = remember(l) { Reconciliacao.comparar(l.respostaBruta, l.temperaturas) }
    val zona = ZoneId.systemDefault()
    val formato = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss XXX").withZone(zona)
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizontalDivider()
        Text("${l.tipo.rotulo} · ${formato.format(Instant.ofEpochMilli(l.lidaEmMillis))}",
            style = MaterialTheme.typography.titleSmall)
        LinhaInfo("Temperatura no bipe", l.temperaturaInstantaneaC?.takeIf { it.isFinite() }?.let { "%.2f °C".format(it) }
            ?: "Não disponível")
        LinhaInfo("Registros baixados", "${l.quantidadeMedida}")
        val faixa = when {
            l.temperaturas.isEmpty() -> "Sem amostras"
            l.temperaturas.any { !it.isFinite() } -> "Não avaliável"
            l.temperaturas.any { it < minC || it > maxC } -> "Há temperaturas fora da faixa"
            else -> "Temperaturas dentro da faixa"
        }
        LinhaInfo("Série baixada", faixa)
        Text(conferencia.explicacao ?: conferencia.rotulo,
            color = if (conferencia.conferida) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { detalhes = !detalhes }) {
            Text(if (detalhes) "Ocultar detalhes" else "Detalhes da evidência")
        }
        if (detalhes) {
            LinhaInfo("Faixa da sessão", "%.1f a %.1f °C".format(minC, maxC))
            LinhaInfo("Fuso exibido", zona.id)
            LinhaInfo("Leitura menos último ponto", "${l.derivaRelogioSegundos} s")
            Text("Essa diferença inclui o intervalo entre registros e a espera para coletar. Não mede deriva do sensor.", style = MaterialTheme.typography.bodySmall)
            LinhaInfo("Desvio do relógio do celular", l.desvioRelogioMs?.let { "$it ms" } ?: "Não aferido")
            LinhaInfo("SDK na coleta", l.versaoSdk)
            LinhaInfo("Decodificador na coleta", l.versaoDecodificador)
            LinhaInfo("Conferência recalculada", Reconciliacao.VERSION)
            LinhaInfo("Envio ao servidor", if (l.sincronizada) "Confirmado" else "Pendente")
            Text("Coleta ${l.id}", style = MaterialTheme.typography.bodySmall)
            Text("SHA-256 do bruto: ${l.hashPayload}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            Text("O Excel contém esta coleta e seus campos originais na aba Evidência bruta. Coerência interna não comprova calibração nem libera a carga.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
