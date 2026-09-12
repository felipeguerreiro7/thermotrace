package com.thermotrace.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.thermotrace.app.domain.Confianca
import com.thermotrace.app.domain.EstadoEtiqueta
import com.thermotrace.app.domain.Excursao
import com.thermotrace.app.domain.GravidadeExcursao
import com.thermotrace.app.domain.Medicao
import com.thermotrace.app.domain.PerfilTermico
import com.thermotrace.app.domain.RegrasTermicas
import com.thermotrace.app.domain.ResultadoTermico
import com.thermotrace.app.domain.SituacaoEtiqueta
import com.thermotrace.app.domain.TipoExcursao
import com.thermotrace.app.domain.TipoLeitura
import com.thermotrace.app.ui.components.CartaoEstadoEtiqueta
import com.thermotrace.app.ui.components.GraficoTemperatura
import com.thermotrace.app.ui.components.LinhaInfo
import com.thermotrace.app.ui.components.Metrica
import com.thermotrace.app.ui.components.PainelNfc
import com.thermotrace.app.ui.components.Secao
import com.thermotrace.app.ui.components.SeloGravidade
import com.thermotrace.app.ui.components.SeloResultado
import com.thermotrace.app.ui.components.TrilhoLeituras
import com.thermotrace.app.nfc.NfcOperator
import com.thermotrace.app.ui.screens.VereditoIPhone
import com.thermotrace.app.ui.theme.TemaThermoTrace
import com.thermotrace.app.ui.theme.VermelhoExcursao
import java.time.Duration
import java.time.Instant

/**
 * Prévias para o painel de Design do Android Studio.
 *
 * Servem para ver e ajustar a interface sem aparelho, sem emulador e sem
 * compilar o APK inteiro — o ciclo passa de minutos para segundos.
 *
 * As telas completas (`LeituraScreen`, `RemessaScreen`…) dependem de
 * ViewModel e de banco, então não entram aqui. O que entra são os
 * componentes reais que elas usam, montados com dados fabricados. Como é o
 * mesmo código de produção, o que aparece na prévia é o que aparece no
 * celular.
 */

// ---------------------------------------------------------------------
// Dados de exemplo — uma remessa 2–8 °C com uma excursão de 50 minutos
// ---------------------------------------------------------------------

private val INICIO: Instant = Instant.parse("2026-08-20T08:00:00Z")

private val MEDICOES: List<Medicao> = List(180) { i ->
    val t = when (i) {
        in 100..104 -> 10.5 + (i - 100) * 0.4      // excursão
        else -> 4.0 + kotlin.math.sin(i / 7.0) * 1.2
    }
    Medicao(i, INICIO.plusSeconds(i * 600L), t)
}

private val RESUMO = RegrasTermicas.resumir(MEDICOES, PerfilTermico.REFRIGERADO_2_8, 600)

private val EXCURSAO = Excursao(
    tipo = TipoExcursao.ACIMA,
    gravidade = GravidadeExcursao.CRITICA,
    inicio = INICIO.plusSeconds(100 * 600L),
    fim = INICIO.plusSeconds(105 * 600L),
    duracao = Duration.ofMinutes(50),
    quantidadePontos = 5,
    picoC = 12.1,
    limiteC = 8.0,
)

// ---------------------------------------------------------------------
// O trilho dos três momentos de leitura
// ---------------------------------------------------------------------

@Preview(name = "Trilho · nada feito", showBackground = true, widthDp = 380)
@Composable
private fun PreviewTrilhoInicio() = TemaThermoTrace {
    Surface { Column(Modifier.padding(16.dp)) {
        TrilhoLeituras(false, 0, false, TipoLeitura.ATIVACAO)
    } }
}

@Preview(name = "Trilho · em trânsito", showBackground = true, widthDp = 380)
@Composable
private fun PreviewTrilhoTransito() = TemaThermoTrace {
    Surface { Column(Modifier.padding(16.dp)) {
        TrilhoLeituras(true, 2, false, TipoLeitura.CHECKPOINT)
    } }
}

@Preview(name = "Trilho · concluído", showBackground = true, widthDp = 380)
@Composable
private fun PreviewTrilhoFim() = TemaThermoTrace {
    Surface { Column(Modifier.padding(16.dp)) {
        TrilhoLeituras(true, 3, true, null)
    } }
}

// ---------------------------------------------------------------------
// Etiqueta ativa — os quatro estados que mais importam
// ---------------------------------------------------------------------

private fun estado(
    situacao: SituacaoEtiqueta,
    confianca: Confianca,
    verificadoHaHoras: Long = 0,
    tensao: Double? = 1.48,
    esperados: Int = 180,
) = EstadoEtiqueta(
    situacao = situacao,
    confianca = confianca,
    verificadoEm = if (confianca == Confianca.DESCONHECIDA) null
    else Instant.now().minus(Duration.ofHours(verificadoHaHoras)),
    ativadaEm = Instant.now().minus(Duration.ofHours(30)),
    intervaloSegundos = 600,
    capacidadeProgramada = 648,
    registrosEsperados = esperados,
    registrosBaixados = esperados,
    tensaoV = tensao,
    ultimaTemperaturaC = 4.25,
)

@Preview(name = "Etiqueta · confirmada agora", showBackground = true, widthDp = 380)
@Composable
private fun PreviewEtiquetaConfirmada() = TemaThermoTrace {
    Surface { Column(Modifier.padding(16.dp)) {
        CartaoEstadoEtiqueta(
            estado(SituacaoEtiqueta.REGISTRANDO, Confianca.CONFIRMADA),
            aoVerificar = {},
        )
    } }
}

@Preview(name = "Etiqueta · presumida há 2 dias", showBackground = true, widthDp = 380)
@Composable
private fun PreviewEtiquetaPresumida() = TemaThermoTrace {
    Surface { Column(Modifier.padding(16.dp)) {
        CartaoEstadoEtiqueta(
            estado(SituacaoEtiqueta.REGISTRANDO, Confianca.PRESUMIDA, verificadoHaHoras = 49),
            aoVerificar = {},
        )
    } }
}

@Preview(name = "Etiqueta · parada", showBackground = true, widthDp = 380)
@Composable
private fun PreviewEtiquetaParada() = TemaThermoTrace {
    Surface { Column(Modifier.padding(16.dp)) {
        CartaoEstadoEtiqueta(
            estado(SituacaoEtiqueta.PARADA, Confianca.CONFIRMADA),
            aoVerificar = {},
        )
    } }
}

@Preview(name = "Etiqueta · bateria baixa", showBackground = true, widthDp = 380)
@Composable
private fun PreviewEtiquetaBateria() = TemaThermoTrace {
    Surface { Column(Modifier.padding(16.dp)) {
        CartaoEstadoEtiqueta(
            estado(SituacaoEtiqueta.BATERIA_BAIXA, Confianca.CONFIRMADA, tensao = 1.31),
            aoVerificar = {},
        )
    } }
}

// ---------------------------------------------------------------------
// Painel NFC
// ---------------------------------------------------------------------

@Preview(name = "NFC · aguardando", showBackground = true, widthDp = 380)
@Composable
private fun PreviewNfcAguardando() = TemaThermoTrace {
    Surface { Column(Modifier.padding(16.dp)) {
        PainelNfc(
            titulo = "Aproxime o celular da etiqueta",
            instrucao = "Não afaste durante a gravação. A sequência escreve a faixa térmica, " +
                "o intervalo e o relógio, e depois confere.",
            aguardando = true,
        )
    } }
}

@Preview(name = "NFC · etiqueta errada", showBackground = true, widthDp = 380)
@Composable
private fun PreviewNfcErrada() = TemaThermoTrace {
    Surface { Column(Modifier.padding(16.dp)) {
        PainelNfc(
            titulo = "Leitura recusada",
            instrucao = "",
            aguardando = false,
            erro = "Etiqueta errada. Esperada 04A83B7291500, encontrada 049C11A47820. " +
                "Nada foi gravado.",
        )
    } }
}

// ---------------------------------------------------------------------
// Gráfico e laudo
// ---------------------------------------------------------------------

@Preview(name = "Gráfico · com excursão", showBackground = true, widthDp = 380)
@Composable
private fun PreviewGrafico() = TemaThermoTrace {
    Surface { Column(Modifier.padding(16.dp)) {
        GraficoTemperatura(MEDICOES, 2.0, 8.0)
    } }
}

@Preview(name = "Laudo · resumo do volume", showBackground = true, widthDp = 380, heightDp = 620)
@Composable
private fun PreviewResumoVolume() = TemaThermoTrace {
    Surface {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Secao("Volume 1 · TT-A7K9P2X4") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Veredito", style = MaterialTheme.typography.bodyMedium)
                    SeloResultado(RESUMO.resultado)
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Metrica("mínima", "%.1f°".format(RESUMO.minimaC ?: 0.0))
                    Metrica("máxima", "%.1f°".format(RESUMO.maximaC ?: 0.0))
                    Metrica("MKT", "%.1f°".format(RESUMO.mktC ?: 0.0))
                    Metrica(
                        "fora da faixa",
                        RegrasTermicas.formatarDuracao(RESUMO.tempoForaFaixaSegundos),
                        cor = VermelhoExcursao,
                    )
                }
                Spacer(Modifier.height(12.dp))
                GraficoTemperatura(MEDICOES, 2.0, 8.0, altura = 150.dp)
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Acima do limite · pico %.1f °C".format(EXCURSAO.picoC),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    SeloGravidade(EXCURSAO.gravidade)
                }
                LinhaInfo("Deriva do relógio", "+214 s")
                LinhaInfo("Ativação confirmada", "Sim")
                LinhaInfo("SHA-256", "a3f91c07e2…")
            }
        }
    }
}

// ---------------------------------------------------------------------
// Selos, lado a lado
// ---------------------------------------------------------------------

@Preview(name = "Selos de veredito", showBackground = true, widthDp = 380)
@Composable
private fun PreviewSelos() = TemaThermoTrace {
    Surface {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ResultadoTermico.entries.forEach { SeloResultado(it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GravidadeExcursao.entries.forEach { SeloGravidade(it) }
            }
        }
    }
}

// ---------------------------------------------------------------------
// Tema escuro: a mesma tela, para conferir contraste
// ---------------------------------------------------------------------

@Preview(name = "Escuro · etiqueta e gráfico", showBackground = true, widthDp = 380, heightDp = 560)
@Composable
private fun PreviewEscuro() = TemaThermoTrace(escuro = true) {
    Surface {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TrilhoLeituras(true, 2, false, TipoLeitura.CHECKPOINT)
            CartaoEstadoEtiqueta(estado(SituacaoEtiqueta.REGISTRANDO, Confianca.CONFIRMADA))
            Secao("Resultado") {
                GraficoTemperatura(MEDICOES, 2.0, 8.0, altura = 150.dp)
            }
        }
    }
}

// ---------------------------------------------------------------------
// O veredito que decide se existe app de iPhone
//
// Sai do diagnóstico, e é a tela que mais vale dinheiro no app: se a
// etiqueta comprada não expuser ISO 15693, a versão iPhone não é questão
// de esforço — ela não é possível com API pública da Apple.
// ---------------------------------------------------------------------

private fun laudo(iso15693: Boolean) = NfcOperator.DiagnosticoEtiqueta(
    uid = "E00401502F3A19C4",
    tecnologias = if (iso15693) listOf("NfcV", "Ndef") else listOf("NfcA", "Ndef"),
    rotuloInterface = if (iso15693) "Type-V  15693" else "Type-A  14443",
    suportaIso15693 = iso15693,
    suportaIso14443a = !iso15693,
    versaoSdk = "fmsh-nfcinstruct-1.0.0",
    acordada = true,
    registrando = true,
    campo = "7",
    temperaturaC = 5.25,
    voltageV = 1.52,
)

@Preview(name = "Veredito · iPhone viável", showBackground = true, widthDp = 380)
@Composable
private fun PreviewVereditoOk() = TemaThermoTrace {
    Surface { Column(Modifier.padding(16.dp)) { VereditoIPhone(laudo(true)) } }
}

@Preview(name = "Veredito · só 14443-A", showBackground = true, widthDp = 380)
@Composable
private fun PreviewVereditoRuim() = TemaThermoTrace {
    Surface { Column(Modifier.padding(16.dp)) { VereditoIPhone(laudo(false)) } }
}
