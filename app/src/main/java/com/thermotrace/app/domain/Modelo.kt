package com.thermotrace.app.domain

import java.time.Duration
import java.time.Instant

/**
 * Vocabulário do domínio.
 *
 * O ciclo de vida de um volume monitorado tem exatamente três momentos de
 * contato com a etiqueta, e a diferença entre eles é o coração do produto:
 *
 *   1. ATIVAÇÃO   — obrigatória, uma só vez, na origem. ESCREVE na etiqueta.
 *   2. CHECKPOINT — opcional, quantas vezes for possível, no trajeto. Só lê.
 *   3. FINAL      — obrigatória, no recebimento. Só lê e encerra.
 *
 * Um volume sem ativação nunca entra em transporte. Um volume sem leitura
 * final nunca conclui. É essa assimetria que faz o dado ser auditável.
 */
enum class TipoLeitura(val rotulo: String, val obrigatoria: Boolean, val escreveNaEtiqueta: Boolean) {
    ATIVACAO("Ativação", obrigatoria = true, escreveNaEtiqueta = true),
    CHECKPOINT("Checkpoint", obrigatoria = false, escreveNaEtiqueta = false),
    FINAL("Leitura final", obrigatoria = true, escreveNaEtiqueta = false);

    val descricao: String
        get() = when (this) {
            ATIVACAO -> "Configura a faixa térmica e inicia o registro autônomo."
            CHECKPOINT -> "Baixa o histórico parcial sem interromper o registro."
            FINAL -> "Baixa o histórico completo e fecha o monitoramento."
        }
}

/** Estados da remessa. Segue a especificação funcional §10. */
enum class StatusRemessa(val rotulo: String) {
    EM_PREPARACAO("Em preparação"),
    AGUARDANDO_ACEITE("Aguardando aceite da transportadora"),
    AGUARDANDO_COLETA("Aguardando coleta"),
    EM_TRANSPORTE("Em transporte"),
    ENTREGUE_AGUARDANDO_LEITURA("Entregue — aguardando leitura final"),
    CONCLUIDA("Concluída"),
    CANCELADA("Cancelada");

    val aceitaCheckpoint: Boolean
        get() = this == EM_TRANSPORTE || this == AGUARDANDO_COLETA
}

enum class StatusVolume(val rotulo: String) {
    SEM_ETIQUETA("Sem etiqueta"),
    VINCULADO("Etiqueta vinculada"),
    MONITORANDO("Monitorando"),
    ENCERRADO("Encerrado");
}

enum class ResultadoTermico(val rotulo: String) {
    SEM_LEITURA("Sem leitura"),
    CONFORME("Conforme"),
    ALERTA("Alerta"),
    EXCURSAO("Excursão térmica"),
}

enum class GravidadeExcursao(val rotulo: String) {
    ALERTA("Alerta"),
    ACAO("Requer ação"),
    CRITICA("Crítica"),
}

enum class TipoExcursao(val rotulo: String) {
    ACIMA("Acima do limite"),
    ABAIXO("Abaixo do limite"),
}

/**
 * Perfil térmico. Nunca string livre.
 *
 * A v0.1 comparava `thermalProfile.contains("15–25")` com travessão. Um hífen
 * comum no lugar do travessão fazia a comparação falhar em silêncio e a
 * etiqueta ser configurada como 2–8 °C — excursão falsa em 100% dos casos.
 *
 * `toleranciaSegundos`: desvio mais curto que isso é ruído operacional
 * (abrir a caixa, transferir de doca) e não vira ocorrência. Sem essa
 * tolerância o sistema dispara alarme a cada manuseio e o cliente para de
 * olhar os alertas — o pior desfecho possível.
 *
 * As faixas seguem as praticadas na cadeia do frio: 2 a 8 °C para
 * medicamentos, 7 a 14 °C para frutas, vegetais e laticínios, 14 a 24 °C
 * para frescos de ambiente, -18 a 0 °C para carnes e pescados congelados e
 * -30 a -18 °C para ultracongelados. Faltavam três dessas: quem transporta
 * laticínio estava escolhendo entre 2 a 8 °C (excursão falsa o tempo todo)
 * e 15 a 25 °C (não acusaria nada).
 *
 * **A etiqueta não mede umidade.** A cadeia do frio controla os dois
 * parâmetros, e o laudo precisa dizer que cobre só um — senão o cliente
 * presume que está coberto e não está.
 */
enum class PerfilTermico(
    val codigo: String,
    val rotulo: String,
    val minC: Int,
    val maxC: Int,
    val toleranciaSegundos: Int,
    val torMaxSegundos: Int,
    /** O que anda nesta faixa. Aparece na tela para o operador nao errar. */
    val exemplos: String,
) {
    REFRIGERADO_2_8(
        "REFRIG_2_8", "Refrigerado · 2 a 8 °C", 2, 8, 600, 3 * 3600,
        "Medicamentos, vacinas, hemocomponentes",
    ),
    RESFRIADO_7_14(
        "RESFRIADO_7_14", "Resfriado · 7 a 14 °C", 7, 14, 900, 6 * 3600,
        "Frutas, vegetais e laticínios",
    ),
    AMBIENTE_15_25(
        "AMBIENTE_15_25", "Ambiente controlado · 15 a 25 °C", 15, 25, 1800, 8 * 3600,
        "Produtos frescos de temperatura ambiente",
    ),
    CONGELADO_M18_0(
        "CONGELADO_M18_0", "Congelado · -18 a 0 °C", -18, 0, 600, 2 * 3600,
        "Carnes e frutos do mar congelados",
    ),
    CONGELADO(
        "CONGELADO_M25_M15", "Congelado · -25 a -15 °C", -25, -15, 300, 3600,
        "Congelados com faixa estreita",
    ),
    ULTRACONGELADO_M30_M18(
        "CONGELADO_M30_M18", "Ultracongelado · -30 a -18 °C", -30, -18, 300, 3600,
        "Carnes e frutos do mar ultracongelados",
    ),
    ULTRACONGELADO(
        "CONGELADO_M80", "Ultracongelado · -80 a -60 °C", -80, -60, 0, 1800,
        "Vacinas de mRNA e material biológico",
    );

    val faixa: String get() = "$minC a $maxC °C"

    companion object {
        fun porCodigo(codigo: String): PerfilTermico =
            entries.firstOrNull { it.codigo == codigo } ?: REFRIGERADO_2_8
    }
}

enum class TipoDocumento(val rotulo: String) {
    NFE("NF-e"), CTE("CT-e"), AWB("AWB"), PEDIDO("Pedido"),
    OUTRO("Outro"), SEM_DOCUMENTO("Sem documento");
}

/** Um ponto da série térmica, já com instante resolvido. */
data class Medicao(
    val indice: Int,
    val instante: Instant,
    val temperaturaC: Double,
)

/** Um desvio CONTÍNUO, não um ponto solto. Ver RegrasTermicas. */
data class Excursao(
    val tipo: TipoExcursao,
    val gravidade: GravidadeExcursao,
    val inicio: Instant,
    val fim: Instant,
    val duracao: Duration,
    val quantidadePontos: Int,
    val picoC: Double,
    val limiteC: Double,
) {
    val desvioC: Double get() = kotlin.math.abs(picoC - limiteC)
}

/**
 * O que o laudo mostra por volume.
 *
 * `mkt` e `tempoForaFaixa` estão aqui porque contar PONTOS fora da faixa —
 * como a especificação original fazia — dá a resposta errada nos dois
 * sentidos: superestima uma abertura de caixa de 2 minutos e subestima um
 * desvio de 4 horas.
 */
data class ResumoTermico(
    val quantidadeMedicoes: Int,
    val inicio: Instant?,
    val fim: Instant?,
    val minimaC: Double?,
    val maximaC: Double?,
    val mediaC: Double?,
    val mktC: Double?,
    val tempoAcimaSegundos: Long,
    val tempoAbaixoSegundos: Long,
    val maiorExcursaoSegundos: Long,
    val excursoes: List<Excursao>,
    val resultado: ResultadoTermico,
) {
    val tempoForaFaixaSegundos: Long get() = tempoAcimaSegundos + tempoAbaixoSegundos

    companion object {
        val VAZIO = ResumoTermico(
            0, null, null, null, null, null, null, 0, 0, 0, emptyList(),
            ResultadoTermico.SEM_LEITURA
        )
    }
}
