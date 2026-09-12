package com.thermotrace.app.nfc

/** Conferência interna. Cabeçalho e série usam o mesmo SDK; concordância não comprova exatidão. */
data class Reconciliacao(
    val minimaSerie: Double?,
    val maximaSerie: Double?,
    val minimaEtiqueta: Double,
    val maximaEtiqueta: Double,
    val abaixoSerie: Int,
    val acimaSerie: Int,
    val abaixoEtiqueta: Int,
    val acimaEtiqueta: Int,
    val motivoNaoAvaliavel: String? = null,
) {
    val avaliavel: Boolean get() = motivoNaoAvaliavel == null && minimaSerie != null && maximaSerie != null &&
        minimaSerie.isFinite() && maximaSerie.isFinite() && minimaEtiqueta.isFinite() && maximaEtiqueta.isFinite() &&
        minimaEtiqueta <= maximaEtiqueta && abaixoEtiqueta >= 0 && acimaEtiqueta >= 0
    private fun difere(a: Double?, b: Double) = a != null && b.isFinite() && kotlin.math.abs(a - b) > 0.150000001
    val extremosDivergem: Boolean get() = avaliavel && (difere(minimaSerie, minimaEtiqueta) || difere(maximaSerie, maximaEtiqueta))
    val contagensDivergem: Boolean get() = avaliavel && (abaixoSerie != abaixoEtiqueta || acimaSerie != acimaEtiqueta)
    val naoReconciliado: Boolean get() = extremosDivergem || contagensDivergem
    val conferida: Boolean get() = avaliavel && !naoReconciliado
    val rotulo: String get() = when {
        !avaliavel -> "Não avaliável"
        naoReconciliado -> "Divergência encontrada"
        else -> "Cabeçalho e série coerentes"
    }
    val explicacao: String? get() = when {
        !avaliavel -> "Conferência não avaliável: ${motivoNaoAvaliavel ?: "faltam dados válidos"}."
        !naoReconciliado -> null
        else -> "Não reconciliado: cabeçalho %.1f a %.1f °C, %d abaixo e %d acima; série %.1f a %.1f °C, %d abaixo e %d acima.".format(
            minimaEtiqueta, maximaEtiqueta, abaixoEtiqueta, acimaEtiqueta,
            minimaSerie, maximaSerie, abaixoSerie, acimaSerie)
    }

    companion object {
        const val VERSION = "tt-reconciliacao-1.1"

        /** Usa os valores preservados; não corrige amostras nem reescreve a leitura. */
        fun comparar(bruto: List<String>, serie: List<Double>): Reconciliacao {
            fun numero(i: Int) = bruto.getOrNull(i)?.toDoubleOrNull()?.takeIf { it.isFinite() }
            val min = numero(6)
            val max = numero(7)
            val limiteMin = numero(8)
            val limiteMax = numero(9)
            val abaixo = bruto.getOrNull(10)?.toIntOrNull()
            val acima = bruto.getOrNull(11)?.toIntOrNull()
            val contagem = bruto.getOrNull(3)?.toIntOrNull()
            val motivo = when {
                bruto.size < 12 -> "cabeçalho incompleto"
                serie.isEmpty() -> "sem amostras para comparar"
                serie.any { !it.isFinite() } -> "série contém valor inválido"
                min == null || max == null || min > max -> "extremos do cabeçalho inválidos"
                limiteMin == null || limiteMax == null || limiteMin >= limiteMax -> "limites do cabeçalho inválidos"
                abaixo == null || acima == null || abaixo < 0 || acima < 0 -> "contagens do cabeçalho inválidas"
                contagem == null || contagem != serie.size || bruto.size - 12 != contagem -> "quantidade de amostras não confere"
                abaixo.toLong() + acima.toLong() > contagem -> "contagem fora da faixa excede as amostras"
                else -> null
            }
            return Reconciliacao(
                serie.filter { it.isFinite() }.minOrNull(), serie.filter { it.isFinite() }.maxOrNull(),
                min ?: Double.NaN, max ?: Double.NaN,
                if (limiteMin != null) serie.count { it < limiteMin } else 0,
                if (limiteMax != null) serie.count { it > limiteMax } else 0,
                abaixo ?: -1, acima ?: -1, motivo,
            )
        }
    }
}
