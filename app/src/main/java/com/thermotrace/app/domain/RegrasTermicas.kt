package com.thermotrace.app.domain

import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Motor de regras térmicas.
 *
 * Espelha `db/02_funcoes_auditoria.sql`. O app calcula localmente para poder
 * mostrar o resultado na hora, sem rede — mas **o veredito que vale é o do
 * servidor**. Se os dois divergirem, o servidor manda: é ele que guarda o
 * dado bruto e a versão da regra que produziu cada avaliação.
 *
 * Por isso `VERSAO` existe e viaja junto de tudo que este arquivo calcula.
 * Se a regra mudar em 2027, os laudos de 2026 continuam reproduzíveis.
 */
object RegrasTermicas {

    const val VERSAO = "rules-2026.08.1"

    /**
     * Temperatura Cinética Média (Arrhenius).
     *
     *              ΔH / R
     * MKT = ------------------------------
     *        -ln( (1/n) Σ exp(-ΔH/(R·Tᵢ)) )
     *
     * ΔH = 83,144 kJ/mol (valor convencional da indústria farmacêutica)
     * R  = 8,314 J/(mol·K)   →   ΔH/R = 10.000 K
     *
     * É a métrica que decide se um lote continua utilizável. Uma média
     * aritmética esconde picos; a MKT os pesa exponencialmente.
     *
     * Pressupõe amostras equiespaçadas — verdadeiro aqui, o logger grava em
     * intervalo fixo.
     */
    fun mkt(temperaturasC: List<Double>, deltaHSobreR: Double = 10_000.0): Double? {
        if (temperaturasC.isEmpty()) return null
        var soma = 0.0
        var n = 0
        for (t in temperaturasC) {
            val kelvin = t + 273.15
            if (kelvin <= 0) continue          // leitura impossível
            soma += exp(-deltaHSobreR / kelvin)
            n++
        }
        if (n == 0 || soma <= 0) return null
        return deltaHSobreR / (-ln(soma / n)) - 273.15
    }

    /**
     * Detecta excursões como SEGMENTOS CONTÍNUOS.
     *
     * A diferença em relação a contar pontos não é cosmética:
     *  - abrir a caixa por 2 min gera 1 ponto fora → parece uma excursão;
     *  - 4 h a 9 °C gera 24 pontos → parece 24 problemas, não um problema grave.
     *
     * `toleranciaSegundos` descarta o primeiro caso sem esconder o segundo.
     */
    fun detectarExcursoes(
        medicoes: List<Medicao>,
        minC: Double,
        maxC: Double,
        toleranciaSegundos: Int,
        intervaloSegundos: Int,
        torMaxSegundos: Int,
    ): List<Excursao> {
        if (medicoes.isEmpty()) return emptyList()

        val resultado = mutableListOf<Excursao>()
        var tipoAtual: TipoExcursao? = null
        var inicioSegmento = 0
        var pico = 0.0

        fun classificar(t: Double): TipoExcursao? = when {
            t > maxC -> TipoExcursao.ACIMA
            t < minC -> TipoExcursao.ABAIXO
            else -> null
        }

        // Percorre até n+1: a sentinela fecha um segmento que vai até o fim.
        for (i in 0..medicoes.size) {
            val tipo = if (i < medicoes.size) classificar(medicoes[i].temperaturaC) else null

            if (tipo != tipoAtual) {
                if (tipoAtual != null) {
                    val ultimo = i - 1
                    val pontos = ultimo - inicioSegmento + 1
                    // O desvio persiste até a amostra seguinte voltar à faixa.
                    val duracao = Duration.ofSeconds(pontos.toLong() * intervaloSegundos)
                    if (duracao.seconds > toleranciaSegundos) {
                        val limite = if (tipoAtual == TipoExcursao.ACIMA) maxC else minC
                        resultado += Excursao(
                            tipo = tipoAtual,
                            gravidade = classificarGravidade(duracao, pico, limite, torMaxSegundos),
                            inicio = medicoes[inicioSegmento].instante,
                            fim = medicoes[ultimo].instante.plusSeconds(intervaloSegundos.toLong()),
                            duracao = duracao,
                            quantidadePontos = pontos,
                            picoC = pico,
                            limiteC = limite,
                        )
                    }
                }
                tipoAtual = tipo
                inicioSegmento = i
                if (i < medicoes.size) pico = medicoes[i].temperaturaC
            } else if (tipoAtual != null && i < medicoes.size) {
                val t = medicoes[i].temperaturaC
                pico = if (tipoAtual == TipoExcursao.ACIMA) maxOf(pico, t) else minOf(pico, t)
            }
        }
        return resultado
    }

    private fun classificarGravidade(
        duracao: Duration,
        picoC: Double,
        limiteC: Double,
        torMaxSegundos: Int,
    ): GravidadeExcursao = when {
        abs(picoC - limiteC) >= 5.0 || duracao.seconds >= 4 * 3600 -> GravidadeExcursao.CRITICA
        duracao.seconds >= torMaxSegundos -> GravidadeExcursao.ACAO
        duracao.seconds >= 30 * 60 -> GravidadeExcursao.ACAO
        else -> GravidadeExcursao.ALERTA
    }

    fun resumir(
        medicoes: List<Medicao>,
        perfil: PerfilTermico,
        intervaloSegundos: Int,
    ): ResumoTermico {
        if (medicoes.isEmpty()) return ResumoTermico.VAZIO

        val temperaturas = medicoes.map { it.temperaturaC }
        val excursoes = detectarExcursoes(
            medicoes = medicoes,
            minC = perfil.minC.toDouble(),
            maxC = perfil.maxC.toDouble(),
            toleranciaSegundos = perfil.toleranciaSegundos,
            intervaloSegundos = intervaloSegundos,
            torMaxSegundos = perfil.torMaxSegundos,
        )

        val acima = excursoes.filter { it.tipo == TipoExcursao.ACIMA }.sumOf { it.duracao.seconds }
        val abaixo = excursoes.filter { it.tipo == TipoExcursao.ABAIXO }.sumOf { it.duracao.seconds }

        val resultado = when {
            excursoes.any { it.gravidade != GravidadeExcursao.ALERTA } -> ResultadoTermico.EXCURSAO
            excursoes.isNotEmpty() -> ResultadoTermico.ALERTA
            else -> ResultadoTermico.CONFORME
        }

        return ResumoTermico(
            quantidadeMedicoes = medicoes.size,
            inicio = medicoes.first().instante,
            fim = medicoes.last().instante,
            minimaC = temperaturas.min(),
            maximaC = temperaturas.max(),
            mediaC = temperaturas.average(),
            mktC = mkt(temperaturas),
            tempoAcimaSegundos = acima,
            tempoAbaixoSegundos = abaixo,
            maiorExcursaoSegundos = excursoes.maxOfOrNull { it.duracao.seconds } ?: 0,
            excursoes = excursoes,
            resultado = resultado,
        )
    }

    /**
     * Quantos registros a etiqueta precisa comportar.
     *
     * Substitui o `loggingCount = 1000` fixo da v0.1. Com intervalo de 10 min,
     * 1000 pontos cobrem 6,9 dias — a etiqueta simplesmente parava de gravar
     * depois disso, sem avisar ninguém.
     */
    fun planejarQuantidade(
        duracaoPrevista: Duration,
        intervaloSegundos: Int,
        fatorSeguranca: Double = 1.5,
    ): Int {
        val necessarios = Math.ceil(
            duracaoPrevista.seconds * fatorSeguranca / intervaloSegundos
        ).toInt()
        return necessarios.coerceAtLeast(12)
    }

    fun formatarDuracao(segundos: Long): String {
        if (segundos <= 0) return "—"
        val h = segundos / 3600
        val m = (segundos % 3600) / 60
        return when {
            h >= 24 -> "${h / 24}d ${h % 24}h"
            h > 0 -> "${h}h ${m}min"
            else -> "${m}min"
        }
    }

    fun instanteDaMedicao(primeiroInstante: Instant, indice: Int, intervaloSegundos: Int): Instant =
        primeiroInstante.plusSeconds(indice.toLong() * intervaloSegundos)
}
