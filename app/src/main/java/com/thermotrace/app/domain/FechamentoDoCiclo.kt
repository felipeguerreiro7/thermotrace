package com.thermotrace.app.domain

/**
 * Em que pé está o fechamento do ciclo START/STOP de um volume.
 *
 * Existe porque o app tinha duas palavras para coisas diferentes e usava uma
 * só. `SessaoEntity.encerradaEmMillis` diz que a leitura final foi gravada no
 * celular; `SessaoEntity.loggerParadoEmMillis` diz que a etiqueta respondeu ao
 * STOP. Só a segunda é afirmação sobre o hardware, e era justamente a que não
 * sobrevivia à saída da tela de coleta: o volume passava a ENCERRADO e nada
 * mais contradizia a palavra.
 *
 * A regra mora aqui, e não na tela, porque a tela da remessa, a exportação e o
 * laudo precisam responder a mesma pergunta. Três cópias da mesma condição é
 * como duas delas começam a discordar.
 */
enum class FechamentoDoCiclo {
    /** Sem leitura final: o ciclo nem chegou ao fim lógico. */
    EM_ANDAMENTO,

    /**
     * Leitura final gravada, STOP não confirmado pela etiqueta.
     *
     * Inclui de propósito dois casos que não se distinguem: STOP recusado e
     * sessão anterior a esta coluna. Os dois pedem a mesma conduta — encostar
     * a etiqueta e conferir — e tratar o segundo como "parada" seria supor
     * sobre hardware que ninguém consultou.
     */
    FINAL_SEM_STOP,

    /** Leitura final gravada e STOP confirmado pela própria etiqueta. */
    FECHADO;

    val pendente: Boolean get() = this == FINAL_SEM_STOP

    companion object {
        fun de(encerradaEmMillis: Long?, loggerParadoEmMillis: Long?): FechamentoDoCiclo = when {
            encerradaEmMillis == null -> EM_ANDAMENTO
            loggerParadoEmMillis == null -> FINAL_SEM_STOP
            else -> FECHADO
        }
    }
}
