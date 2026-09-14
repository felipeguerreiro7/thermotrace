package com.thermotrace.app.data.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.thermotrace.app.data.db.CustodiaEntity
import com.thermotrace.app.data.db.EtiquetaEntity
import com.thermotrace.app.data.db.RemessaCompleta
import com.thermotrace.app.data.db.SessaoComLeituras
import com.thermotrace.app.data.db.VolumeEntity
import com.thermotrace.app.domain.Medicao
import com.thermotrace.app.domain.PerfilTermico
import com.thermotrace.app.domain.RegrasTermicas
import com.thermotrace.app.domain.ResumoTermico
import com.thermotrace.app.nfc.Reconciliacao
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Gera o laudo em Excel a partir do banco local.
 *
 * As abas espelham as visões `v_export_*` de `db/04_export_excel.sql`, para
 * que a planilha do aparelho e a do servidor sejam a mesma coisa. Se um dia
 * divergirem, a do servidor manda — ela é gerada a partir do dado bruto
 * preservado, e pode ser regerada anos depois.
 */
class ExportadorLaudo(private val context: Context) {

    data class Entrada(
        val remessa: RemessaCompleta,
        val sessoes: List<SessaoComLeituras>,
        val etiquetas: Map<String, EtiquetaEntity>,
        val resumos: Map<String, ResumoTermico>,
        val medicoes: Map<String, List<Medicao>>,
    )

    private val zona: ZoneId = ZoneId.systemDefault()
    private val formatoDataHora = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(zona)
    private val formatoData = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(zona)

    fun gerar(entrada: Entrada): File {
        val perfil = PerfilTermico.porCodigo(entrada.remessa.remessa.perfilTermicoCodigo)
        val escritor = EscritorXlsx()

        abaLaudo(escritor, entrada, perfil)
        abaResumo(escritor, entrada, perfil)
        abaMedicoes(escritor, entrada, perfil)
        abaExcursoes(escritor, entrada)
        abaCustodia(escritor, entrada)
        abaAuditoria(escritor, entrada)
        EvidenciaBrutaXlsx.adicionar(escritor, entrada.sessoes.flatMap { it.leituras })

        val pasta = File(context.cacheDir, "laudos").apply { mkdirs() }
        // Nunca sobrescrever um arquivo já compartilhado, inclusive para códigos iguais entre contas.
        val codigoSeguro = entrada.remessa.remessa.codigo.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64)
        val arquivo = File.createTempFile("laudo_${codigoSeguro}_", ".xlsx", pasta)
        arquivo.outputStream().use { escritor.escrever(it) }
        return arquivo
    }

    fun compartilhar(arquivo: File): Intent {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", arquivo
        )
        return Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, arquivo.nameWithoutExtension)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Enviar laudo"
        )
    }

    // -----------------------------------------------------------------

    private fun texto(v: String?) = EscritorXlsx.Celula.Texto(v ?: "—")
    private fun numero(v: Double?) =
        if (v == null || !v.isFinite()) EscritorXlsx.Celula.Vazia else EscritorXlsx.Celula.Numero(v)
    private fun numero(v: Int?) =
        if (v == null) EscritorXlsx.Celula.Vazia else EscritorXlsx.Celula.Numero(v.toDouble())
    /**
     * Campo do cabeçalho da resposta da etiqueta, por índice.
     *
     * O contrato está em `nfc/SessionDecoder`: 6 e 7 são a mínima e a máxima
     * que a própria etiqueta registrou, 10 e 11 são quantos pontos ela contou
     * fora da faixa. São números calculados pela etiqueta, não pela nossa
     * decodificação — por isso valem como confronto.
     */
    private fun campoBruto(bruto: List<String>, indice: Int): Double? =
        bruto.getOrNull(indice)?.toDoubleOrNull()?.takeIf { it.isFinite() }

    /**
     * Confronta o que a etiqueta declara com a série que virou laudo.
     *
     * Origem: ensaio de 12/09/2026, coleta com mínima de −29,8 °C e a etiqueta
     * sobre a mesa. Não corrige nada — nomeia a divergência, como as regras do
     * cofre exigem para diferença não reconciliada.
     */
    private fun instante(ms: Long?) =
        if (ms == null || ms <= 0) EscritorXlsx.Celula.Vazia
        else EscritorXlsx.Celula.Texto(formatoDataHora.format(Instant.ofEpochMilli(ms)))

    private fun abaLaudo(e: EscritorXlsx, dados: Entrada, perfil: PerfilTermico) {
        val r = dados.remessa.remessa
        val conferencias = dados.sessoes.flatMap { it.leituras }.map { Reconciliacao.comparar(it.respostaBruta, it.temperaturas) }
        val pendencias = conferencias.count { !it.conferida }
        val vereditos = dados.resumos.values.map { it.resultado }
        val geral = when {
            vereditos.isEmpty() -> "SEM LEITURA"
            vereditos.any { it == com.thermotrace.app.domain.ResultadoTermico.SEM_LEITURA } ->
                "INCONCLUSIVO — há volume monitorado sem leitura final"
            vereditos.any { it == com.thermotrace.app.domain.ResultadoTermico.EXCURSAO } ->
                "EXCURSÃO TÉRMICA DETECTADA"
            vereditos.any { it == com.thermotrace.app.domain.ResultadoTermico.ALERTA } ->
                "DESVIO EM NÍVEL DE ALERTA"
            else -> "CONFORME"
        }

        val linhas = listOf(
            listOf(texto("Remessa"), texto(r.codigo)),
            listOf(texto("Situação"), texto(r.status.rotulo)),
            listOf(texto("Remetente"), texto(r.remetente)),
            listOf(texto("Transportadora"), texto(r.transportadora)),
            listOf(texto("Destinatário"), texto(r.destinatario)),
            listOf(texto("Destino"), texto(r.destinoEndereco)),
            listOf(texto("Documentos"), texto(
                dados.remessa.documentos.joinToString(" | ") { "${it.tipo.rotulo} ${it.numero}" }
                    .ifBlank { "—" })),
            listOf(texto("Carga"), texto(r.descricaoCarga)),
            listOf(texto("Perfil térmico"), texto("${perfil.rotulo} (${perfil.faixa})")),
            listOf(texto("Intervalo de registro"), texto("${r.intervaloSegundos / 60} min")),
            listOf(texto("Volumes monitorados"),
                numero(dados.remessa.volumes.count { it.monitorado })),
            listOf(texto("RESULTADO GERAL"), texto(if (pendencias > 0) "PENDENTE DE CONFERÊNCIA" else geral)),
            listOf(texto("Resultado térmico calculado"), texto(geral)),
            listOf(texto("Conferência da evidência"), texto(
                if (conferencias.isEmpty()) "Sem coletas para conferir"
                else if (pendencias > 0) "$pendencias coleta(s) com divergência ou conferência não avaliável. Resultado pendente de conferência."
                else "Cabeçalhos e séries coerentes. Isso não comprova calibração nem libera a carga.")),
            listOf(texto("Versão da conferência"), texto(Reconciliacao.VERSION)),
            listOf(texto("Resposta bruta"), texto("Na aba Evidência bruta: agrupar por ID da coleta e índice do campo, ordenar as partes, concatenar e decodificar a string JSON. Índices a partir de zero; nenhum campo é substituído pelo resumo.")),
            listOf(EscritorXlsx.Celula.Vazia, EscritorXlsx.Celula.Vazia),
            listOf(texto("Versão da regra"), texto(RegrasTermicas.VERSAO)),
            listOf(texto("Gerado em"), texto(formatoDataHora.format(Instant.now()))),
            listOf(texto("Observação"), texto(
                "Os horários derivam do relógio do aparelho que ativou a etiqueta. " +
                    "A diferença entre coleta e último ponto está na aba Auditoria; não mede deriva do sensor.")),
        )
        e.aba("Laudo", listOf("Campo", "Valor"), linhas)
    }

    private fun abaResumo(e: EscritorXlsx, dados: Entrada, perfil: PerfilTermico) {
        val cabecalho = listOf(
            "Volume", "Identificação da caixa", "Etiqueta", "UID NFC",
            "Certificado de calibração", "Início", "Fim", "Registros",
            "Mínima (°C)", "Máxima (°C)", "Média (°C)", "MKT (°C)",
            "Tempo acima (min)", "Tempo abaixo (min)", "Maior excursão (min)",
            "Excursões", "Resultado",
        )
        val linhas = dados.sessoes.mapNotNull { sessao ->
            val volume = dados.remessa.volumes.firstOrNull { it.id == sessao.sessao.volumeId }
                ?: return@mapNotNull null
            val etiqueta = dados.etiquetas[sessao.sessao.etiquetaId]
            val resumo = dados.resumos[sessao.sessao.id] ?: ResumoTermico.VAZIO
            listOf(
                numero(volume.sequencia),
                texto(volume.codigoExterno),
                texto(etiqueta?.serial),
                texto(etiqueta?.uidNfc),
                texto(etiqueta?.certificadoCalibracao),
                instante(resumo.inicio?.toEpochMilli()),
                instante(resumo.fim?.toEpochMilli()),
                numero(resumo.quantidadeMedicoes),
                numero(resumo.minimaC), numero(resumo.maximaC),
                numero(resumo.mediaC), numero(resumo.mktC),
                numero(resumo.tempoAcimaSegundos / 60.0),
                numero(resumo.tempoAbaixoSegundos / 60.0),
                numero(resumo.maiorExcursaoSegundos / 60.0),
                numero(resumo.excursoes.size),
                texto(resumo.resultado.rotulo),
            )
        }
        e.aba("Resumo", cabecalho, linhas)
    }

    /** A aba que o auditor abre primeiro. "Situação" já vem resolvida. */
    private fun abaMedicoes(e: EscritorXlsx, dados: Entrada, perfil: PerfilTermico) {
        val cabecalho = listOf(
            "Volume", "Etiqueta", "Nº", "Data/hora", "Temperatura (°C)",
            "Mínimo (°C)", "Máximo (°C)", "Situação",
        )
        val linhas = mutableListOf<List<EscritorXlsx.Celula>>()
        dados.sessoes.forEach { sessao ->
            val volume = dados.remessa.volumes.firstOrNull { it.id == sessao.sessao.volumeId }
            val etiqueta = dados.etiquetas[sessao.sessao.etiquetaId]
            val min = sessao.sessao.minConfiguradoC
            val max = sessao.sessao.maxConfiguradoC
            dados.medicoes[sessao.sessao.id].orEmpty().forEach { m ->
                linhas += listOf(
                    numero(volume?.sequencia),
                    texto(etiqueta?.serial),
                    numero(m.indice + 1),
                    EscritorXlsx.Celula.Texto(formatoDataHora.format(m.instante)),
                    numero(m.temperaturaC),
                    numero(min), numero(max),
                    texto(
                        when {
                            m.temperaturaC > max -> "Acima"
                            m.temperaturaC < min -> "Abaixo"
                            else -> "Na faixa"
                        }
                    ),
                )
            }
        }
        e.aba("Medições", cabecalho, linhas)
    }

    private fun abaExcursoes(e: EscritorXlsx, dados: Entrada) {
        val cabecalho = listOf(
            "Volume", "Etiqueta", "Tipo", "Gravidade", "Início", "Fim",
            "Duração (min)", "Registros", "Pico (°C)", "Limite (°C)",
            "Desvio (°C)", "Versão da regra",
        )
        val linhas = mutableListOf<List<EscritorXlsx.Celula>>()
        dados.sessoes.forEach { sessao ->
            val volume = dados.remessa.volumes.firstOrNull { it.id == sessao.sessao.volumeId }
            val etiqueta = dados.etiquetas[sessao.sessao.etiquetaId]
            dados.resumos[sessao.sessao.id]?.excursoes.orEmpty().forEach { ex ->
                linhas += listOf(
                    numero(volume?.sequencia),
                    texto(etiqueta?.serial),
                    texto(ex.tipo.rotulo),
                    texto(ex.gravidade.rotulo),
                    EscritorXlsx.Celula.Texto(formatoDataHora.format(ex.inicio)),
                    EscritorXlsx.Celula.Texto(formatoDataHora.format(ex.fim)),
                    numero(ex.duracao.seconds / 60.0),
                    numero(ex.quantidadePontos),
                    numero(ex.picoC), numero(ex.limiteC), numero(ex.desvioC),
                    texto(RegrasTermicas.VERSAO),
                )
            }
        }
        e.aba("Excursões", cabecalho, linhas)
    }

    private fun abaCustodia(e: EscritorXlsx, dados: Entrada) {
        val cabecalho = listOf(
            "De", "Para", "Quando aconteceu", "Quando foi lançado",
            "Atraso do lançamento (min)", "Origem do registro",
            "Volumes", "Recebedor", "Local", "Observação",
        )
        val linhas = dados.remessa.custodia
            .sortedBy { it.ocorridoEmMillis }
            .map { c: CustodiaEntity ->
                listOf(
                    texto(c.de), texto(c.para),
                    instante(c.ocorridoEmMillis), instante(c.registradoEmMillis),
                    numero((c.registradoEmMillis - c.ocorridoEmMillis) / 60000.0),
                    texto(
                        when (c.origem) {
                            "automatico_nfc" -> "Automático (NFC)"
                            "confirmado_tempo_real" -> "Confirmado em tempo real"
                            else -> "Informado posteriormente"
                        }
                    ),
                    numero(c.volumesConfirmados), texto(c.recebedor),
                    texto(c.local), texto(c.observacao),
                )
            }
        e.aba("Custódia", cabecalho, linhas)
    }

    /** Sem esta aba a planilha é um gráfico bonito. Com ela, é evidência. */
    private fun abaAuditoria(e: EscritorXlsx, dados: Entrada) {
        val cabecalho = listOf(
            "Volume", "Etiqueta", "UID NFC", "Tipo de leitura", "Lido em",
            "Desvio do relógio (ms)", "Base de tempo", "Ativado por",
            "Ativação confirmada", "Leitura menos último ponto (s)", "Horários corrigidos",
            "Bateria (V)", "Registros", "Intervalo (s)", "SDK", "Decodificador",
            "SHA-256 do bruto", "SHA-256 encadeado", "Sincronizada",
            // Confronto cabeçalho x série. Sem estas colunas, um laudo com
            // extremo absurdo sai igual a um laudo conferido: o número da
            // etiqueta e o número da série nunca aparecem lado a lado.
            "Mínima declarada pela etiqueta (°C)", "Máxima declarada pela etiqueta (°C)",
            "Pontos abaixo (etiqueta)", "Pontos acima (etiqueta)",
            "Conferência cabeçalho e série", "Resposta bruta da etiqueta", "Versão da conferência", "ID da coleta",
            // Posição do CELULAR no instante do fix, não da carga. Precisão e
            // idade vão junto: sem elas a coordenada sugere uma exatidão que o
            // dado não tem.
            "Latitude", "Longitude", "Precisão (m)", "Provedor do fix", "Fix obtido em",
        )
        val linhas = mutableListOf<List<EscritorXlsx.Celula>>()
        dados.sessoes.forEach { sessao ->
            val volume = dados.remessa.volumes.firstOrNull { it.id == sessao.sessao.volumeId }
            val etiqueta = dados.etiquetas[sessao.sessao.etiquetaId]
            sessao.leituras.sortedBy { it.lidaEmMillis }.forEach { l ->
                linhas += listOf(
                    numero(volume?.sequencia),
                    texto(etiqueta?.serial),
                    texto(etiqueta?.uidNfc),
                    texto(l.tipo.rotulo),
                    instante(l.lidaEmMillis),
                    numero(l.desvioRelogioMs?.toDouble()),
                    texto(sessao.sessao.baseDeTempo),
                    texto(sessao.sessao.plataformaAtivacao),
                    texto(if (sessao.sessao.ativacaoConfirmada) "Sim" else "NÃO"),
                    numero(l.derivaRelogioSegundos.toDouble()),
                    texto(if (l.horariosCorrigidos) "Sim" else "Não"),
                    numero(l.tensaoV),
                    numero(l.quantidadeMedida),
                    numero(l.intervaloRelatado),
                    texto(l.versaoSdk),
                    texto(l.versaoDecodificador),
                    texto(l.hashPayload),
                    texto(l.hashEncadeado),
                    texto(if (l.sincronizada) "Sim" else "Pendente"),
                    numero(campoBruto(l.respostaBruta, 6)),
                    numero(campoBruto(l.respostaBruta, 7)),
                    numero(campoBruto(l.respostaBruta, 10)?.toInt()),
                    numero(campoBruto(l.respostaBruta, 11)?.toInt()),
                    texto(Reconciliacao.comparar(l.respostaBruta, l.temperaturas).let { it.explicacao ?: it.rotulo }),
                    // Resumo para leitura humana. A reconstrução exata usa os campos
                    // ordenados e codificados em JSON da aba Evidência bruta.
                    texto(l.respostaBruta.joinToString("|").takeIf { it.length <= 30000 } ?: "Resposta longa: consulte a aba Evidência bruta"),
                    texto(Reconciliacao.VERSION), texto(l.id),
                    numero(l.latitude),
                    numero(l.longitude),
                    numero(l.precisaoMetros),
                    texto(l.provedorLocal),
                    instante(l.localizadoEmMillis),
                )
            }
        }
        e.aba("Auditoria", cabecalho, linhas)
    }
}
