package com.thermotrace.app.data.alerta

import android.content.Intent
import android.net.Uri
import com.thermotrace.app.domain.AlertaTermico
import com.thermotrace.app.domain.GravidadeExcursao
import com.thermotrace.app.domain.RegrasTermicas
import com.thermotrace.app.domain.TipoExcursao
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Texto do alerta.
 *
 * Fica no app, e não só no backend, porque o mesmo conteúdo precisa sair por
 * três caminhos: o servidor (normal), o app de e-mail do operador (quando não
 * há backend ou não há rede e alguém precisa avisar agora) e o laudo.
 *
 * A redação tem uma regra que vale mais que o layout: **nunca dizer que a
 * excursão está acontecendo agora.** Ela foi *detectada* agora. Pode ter
 * ocorrido há horas. Um e-mail que confunde as duas coisas manda a
 * transportadora correr atrás de uma carga que já chegou — e queima a
 * credibilidade do sistema na primeira semana.
 */
object AlertaEmail {

    private val dataHora = DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm")
        .withZone(ZoneId.systemDefault())

    fun assunto(a: AlertaTermico): String {
        val marcador = when (a.gravidade) {
            GravidadeExcursao.CRITICA -> "[CRÍTICO]"
            GravidadeExcursao.ACAO -> "[AÇÃO NECESSÁRIA]"
            GravidadeExcursao.ALERTA -> "[ALERTA]"
        }
        val sentido = if (a.tipo == TipoExcursao.ACIMA) "acima" else "abaixo"
        return "$marcador ${a.codigoRemessa} · temperatura $sentido da faixa " +
            "(${"%.1f".format(a.picoC)} °C) — ${a.documento}"
    }

    fun corpo(a: AlertaTermico): String = buildString {
        appendLine("EXCURSÃO TÉRMICA DETECTADA")
        appendLine("=" .repeat(52))
        appendLine()

        // O bloco mais importante do e-mail. Vem primeiro de propósito.
        appendLine("QUANDO ACONTECEU:  ${dataHora.format(a.ocorridoEm)}")
        appendLine("QUANDO DETECTAMOS: ${dataHora.format(a.detectadoEm)}")
        if (a.atrasoDeteccao.toMinutes() > 5) {
            appendLine(
                "                   (${RegrasTermicas.formatarDuracao(a.atrasoDeteccao.seconds)} " +
                    "depois — a etiqueta registra sozinha e só é lida por aproximação)"
            )
        }
        appendLine()

        appendLine("REMESSA")
        appendLine("  Documento .......: ${a.documento}")
        appendLine("  Remessa .........: ${a.codigoRemessa}")
        appendLine("  Volume ..........: ${a.volume}")
        appendLine("  Etiqueta ........: ${a.etiquetaSerial}")
        appendLine("  Transportadora ..: ${a.transportadora}")
        appendLine("  Destinatário ....: ${a.destinatario}")
        appendLine()

        appendLine("O DESVIO")
        val sentido = if (a.tipo == TipoExcursao.ACIMA) "Acima do máximo" else "Abaixo do mínimo"
        appendLine("  Perfil ..........: ${a.perfil.rotulo} (${a.perfil.faixa})")
        appendLine("  Tipo ............: $sentido de ${"%.1f".format(a.limiteC)} °C")
        appendLine("  Pico ............: ${"%.1f".format(a.picoC)} °C")
        appendLine("  Duração .........: ${RegrasTermicas.formatarDuracao(a.duracao.seconds)}")
        appendLine("  Gravidade .......: ${a.gravidade.rotulo}")
        appendLine()

        appendLine("A REMESSA ATÉ AGORA")
        appendLine("  Excursões .......: ${a.quantidadeExcursoes}")
        appendLine(
            "  Tempo fora faixa : ${RegrasTermicas.formatarDuracao(a.tempoForaFaixaTotal.seconds)}"
        )
        a.mktC?.let { appendLine("  MKT .............: ${"%.2f".format(it)} °C") }
        appendLine()

        appendLine("O QUE FAZER")
        if (a.acaoAindaUtil) {
            appendLine("  A carga provavelmente ainda está em trânsito. Ações possíveis:")
            appendLine("    · trocar os elementos refrigerantes")
            appendLine("    · reacondicionar ou transferir de embalagem")
            appendLine("    · levar para câmara fria na próxima parada")
            appendLine("    · comunicar o cliente e o responsável técnico")
        } else {
            appendLine("  O desvio foi detectado ${RegrasTermicas.formatarDuracao(a.atrasoDeteccao.seconds)}")
            appendLine("  depois de ocorrer. Ação corretiva no transporte provavelmente já não")
            appendLine("  se aplica — o caso é de avaliação do produto pelo responsável técnico.")
        }
        appendLine()
        appendLine("  Registre a ação tomada no aplicativo, em Ocorrências. O registro entra")
        appendLine("  no laudo com a data em que a ação ocorreu e a data em que foi lançada.")
        appendLine()

        appendLine("-".repeat(52))
        appendLine("Regra de avaliação: ${RegrasTermicas.VERSAO}")
        appendLine("Ocorrência: ${a.ocorrenciaId}")
        appendLine()
        appendLine("Este sistema registra fatos e custódia. Não atribui responsabilidade.")
    }

    /**
     * Contingência: abre o app de e-mail do operador com tudo preenchido.
     *
     * Existe porque o alerta que importa é o que sai hoje. Enquanto o backend
     * não estiver de pé — ou quando a doca não tem sinal e alguém precisa
     * avisar assim que pegar rede — este caminho resolve. O envio pelo
     * servidor continua sendo o normal, e é o que fica registrado como tal.
     */
    fun intentEmail(a: AlertaTermico): Intent {
        val destinatarios = a.destinatarios
            .filter { it.recebe(a.gravidade) }
            .map { it.email }
            .toTypedArray()

        return Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, destinatarios)
            putExtra(Intent.EXTRA_SUBJECT, assunto(a))
            putExtra(Intent.EXTRA_TEXT, corpo(a))
        }
    }

    /** Payload para o backend. Mesma chave de idempotência da ocorrência. */
    fun payload(a: AlertaTermico): JSONObject = JSONObject().apply {
        put("ocorrencia_id", a.ocorrenciaId)
        put("remessa", a.codigoRemessa)
        put("documento", a.documento)
        put("volume", a.volume)
        put("etiqueta", a.etiquetaSerial)
        put("gravidade", a.gravidade.name)
        put("tipo", a.tipo.name)
        put("pico_c", a.picoC)
        put("limite_c", a.limiteC)
        put("duracao_segundos", a.duracao.seconds)
        put("ocorrido_em", a.ocorridoEm.toString())
        put("detectado_em", a.detectadoEm.toString())
        put("registrado_em", Instant.now().toString())
        put("tempo_fora_faixa_segundos", a.tempoForaFaixaTotal.seconds)
        put("mkt_c", a.mktC ?: JSONObject.NULL)
        put("quantidade_excursoes", a.quantidadeExcursoes)
        put("versao_regra", RegrasTermicas.VERSAO)
        put("assunto", assunto(a))
        put("corpo", corpo(a))
        put("destinatarios", JSONArray().apply {
            a.destinatarios.filter { it.recebe(a.gravidade) }.forEach { d ->
                put(JSONObject().apply {
                    put("nome", d.nome)
                    put("email", d.email)
                    put("papel", d.papel.name)
                })
            }
        })
    }
}
