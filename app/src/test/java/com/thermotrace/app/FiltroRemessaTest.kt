package com.thermotrace.app

import com.thermotrace.app.data.db.RemessaEntity
import com.thermotrace.app.domain.PerfilTermico
import com.thermotrace.app.domain.StatusRemessa
import com.thermotrace.app.ui.screens.FiltroRemessa
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Os recortes da lista de remessas.
 *
 * O caso que importa é a fronteira entre "em andamento" e "aguardando
 * leitura": a remessa entregue mas sem leitura final é a que segura o
 * faturamento, e se ela vazar para "em andamento" some no meio das outras —
 * que é exatamente o problema que o recorte existe para resolver.
 */
class FiltroRemessaTest {

    private fun remessa(status: StatusRemessa) = RemessaEntity(
        id = "id-${status.name}",
        codigo = "REM-2026-00001",
        remetente = "", transportadora = "", destinatario = "",
        destinoEndereco = "", contatoRecebimento = "",
        perfilTermicoCodigo = PerfilTermico.REFRIGERADO_2_8.codigo,
        descricaoCarga = "", intervaloSegundos = 600,
        previsaoColetaMillis = null, previsaoEntregaMillis = null,
        status = status,
        criadaEmMillis = 0L,
    )

    @Test
    fun `todas aceita qualquer status`() {
        StatusRemessa.entries.forEach {
            assertTrue(it.name, FiltroRemessa.TODAS.aceita(remessa(it)))
        }
    }

    @Test
    fun `entregue aguardando leitura nao conta como em andamento`() {
        val alvo = remessa(StatusRemessa.ENTREGUE_AGUARDANDO_LEITURA)
        assertFalse(FiltroRemessa.EM_ANDAMENTO.aceita(alvo))
        assertTrue(FiltroRemessa.AGUARDANDO_LEITURA.aceita(alvo))
    }

    @Test
    fun `concluida e cancelada saem de em andamento`() {
        assertFalse(FiltroRemessa.EM_ANDAMENTO.aceita(remessa(StatusRemessa.CONCLUIDA)))
        assertFalse(FiltroRemessa.EM_ANDAMENTO.aceita(remessa(StatusRemessa.CANCELADA)))
    }

    @Test
    fun `cancelada nao aparece em concluidas`() {
        // Cancelar não é concluir: uma some do fluxo, a outra fechou o ciclo
        // com leitura final. Misturar as duas inflaria o número que o cliente
        // usa para dizer quantas remessas foram monitoradas até o fim.
        assertFalse(FiltroRemessa.CONCLUIDAS.aceita(remessa(StatusRemessa.CANCELADA)))
        assertTrue(FiltroRemessa.CONCLUIDAS.aceita(remessa(StatusRemessa.CONCLUIDA)))
    }

    @Test
    fun `em transporte e em preparacao sao em andamento`() {
        assertTrue(FiltroRemessa.EM_ANDAMENTO.aceita(remessa(StatusRemessa.EM_TRANSPORTE)))
        assertTrue(FiltroRemessa.EM_ANDAMENTO.aceita(remessa(StatusRemessa.EM_PREPARACAO)))
    }

    @Test
    fun `os recortes nao deixam status orfao entre andamento, leitura e concluida`() {
        // Toda remessa que não foi cancelada tem que cair em exatamente um
        // dos três recortes. Um status novo que escape aparece aqui, e não
        // como "sumiu uma remessa da tela" no cliente.
        StatusRemessa.entries
            .filter { it != StatusRemessa.CANCELADA }
            .forEach { status ->
                val r = remessa(status)
                val quantos = listOf(
                    FiltroRemessa.EM_ANDAMENTO,
                    FiltroRemessa.AGUARDANDO_LEITURA,
                    FiltroRemessa.CONCLUIDAS,
                ).count { it.aceita(r) }
                assertEquals(status.name, 1, quantos)
            }
    }
}
