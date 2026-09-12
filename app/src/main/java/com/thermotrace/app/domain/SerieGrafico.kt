package com.thermotrace.app.domain

import java.time.Duration

data class PontoGrafico(val medicao: Medicao, val fracaoTempo: Float, val iniciaTrecho: Boolean)

/** Geometria temporal sem Android, compartilhada pelo desenho e seus testes. */
fun prepararSerieGrafico(medicoes: List<Medicao>, intervaloEsperadoSegundos: Int? = null): Result<List<PontoGrafico>> = runCatching {
    require(intervaloEsperadoSegundos == null || intervaloEsperadoSegundos > 0) { "Intervalo inválido." }
    require(medicoes.all { it.temperaturaC.isFinite() && it.indice >= 0 }) { "Histórico contém medições inválidas." }
    require(medicoes.map { it.indice }.distinct().size == medicoes.size) { "Histórico contém índices repetidos." }
    val ordenadas = medicoes.sortedBy { it.indice }
    require(ordenadas.zipWithNext().all { (a, b) -> b.instante > a.instante }) { "Horários inconsistentes no histórico." }
    val primeiro = ordenadas.firstOrNull()?.instante ?: return@runCatching emptyList()
    val duracao = Duration.between(primeiro, ordenadas.last().instante).toMillis()
    ordenadas.mapIndexed { i, m ->
        val anterior = ordenadas.getOrNull(i - 1)
        val lacuna = anterior == null || m.indice.toLong() != anterior.indice.toLong() + 1 ||
            (intervaloEsperadoSegundos != null && Duration.between(anterior.instante, m.instante).toMillis() > intervaloEsperadoSegundos * 1500L)
        PontoGrafico(m, if (duracao == 0L) 0.5f else (Duration.between(primeiro, m.instante).toMillis().toDouble() / duracao).toFloat(), lacuna)
    }
}
