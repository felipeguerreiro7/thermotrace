package com.thermotrace.app

import com.thermotrace.app.domain.Medicao
import com.thermotrace.app.domain.PerfilTermico
import com.thermotrace.app.domain.RegrasTermicas
import com.thermotrace.app.domain.ResultadoTermico
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Os perfis térmicos e as faixas da cadeia do frio.
 *
 * O caso que motivou os perfis novos: quem transporta laticínio a 7–14 °C
 * estava escolhendo entre 2–8 °C, que acusa excursão o tempo todo, e
 * 15–25 °C, que não acusa nada. Os dois destroem a confiança no alerta — o
 * primeiro por excesso, o segundo por omissão.
 */
class PerfilTermicoTest {

    private val inicio: Instant = Instant.parse("2026-08-20T08:00:00Z")

    private fun serie(vararg temps: Double): List<Medicao> =
        temps.mapIndexed { i, t -> Medicao(i, inicio.plusSeconds(i * 600L), t) }

    @Test
    fun `codigo de cada perfil e unico`() {
        val codigos = PerfilTermico.entries.map { it.codigo }
        assertEquals(codigos.size, codigos.toSet().size)
    }

    @Test
    fun `o codigo gravado no banco continua resolvendo para o mesmo perfil`() {
        // Sessões antigas guardam o código, não o nome do enum. Se um código
        // mudar, o laudo de uma remessa passada passa a ser avaliado contra
        // outra faixa — que é reescrever o passado.
        assertEquals(PerfilTermico.REFRIGERADO_2_8, PerfilTermico.porCodigo("REFRIG_2_8"))
        assertEquals(PerfilTermico.AMBIENTE_15_25, PerfilTermico.porCodigo("AMBIENTE_15_25"))
        assertEquals(PerfilTermico.CONGELADO, PerfilTermico.porCodigo("CONGELADO_M25_M15"))
        assertEquals(PerfilTermico.ULTRACONGELADO, PerfilTermico.porCodigo("CONGELADO_M80"))
    }

    @Test
    fun `codigo desconhecido cai no refrigerado, nunca em nulo`() {
        // Uma sessão vinda de uma versão mais nova do app não pode derrubar a
        // tela do laudo. Cair no perfil mais restritivo é o erro seguro.
        assertEquals(PerfilTermico.REFRIGERADO_2_8, PerfilTermico.porCodigo("NAO_EXISTE"))
    }

    @Test
    fun `toda faixa tem minimo menor que maximo`() {
        PerfilTermico.entries.forEach {
            assertTrue(it.name, it.minC < it.maxC)
        }
    }

    @Test
    fun `laticinio a 10 graus e conforme no perfil resfriado e excursao no refrigerado`() {
        // Dez horas a 10 °C: dentro da faixa de frutas, vegetais e laticínios,
        // muito fora da faixa de medicamento.
        val viagem = serie(*DoubleArray(60) { 10.0 })

        val resfriado = RegrasTermicas.resumir(viagem, PerfilTermico.RESFRIADO_7_14, 600)
        assertEquals(ResultadoTermico.CONFORME, resfriado.resultado)
        assertEquals(0, resfriado.excursoes.size)

        val refrigerado = RegrasTermicas.resumir(viagem, PerfilTermico.REFRIGERADO_2_8, 600)
        assertEquals(ResultadoTermico.EXCURSAO, refrigerado.resultado)
        assertNotEquals(0, refrigerado.excursoes.size)
    }

    @Test
    fun `carne a menos 5 graus e conforme no congelado ate zero e nao no congelado estreito`() {
        val viagem = serie(*DoubleArray(40) { -5.0 })

        assertEquals(
            ResultadoTermico.CONFORME,
            RegrasTermicas.resumir(viagem, PerfilTermico.CONGELADO_M18_0, 600).resultado,
        )
        assertEquals(
            ResultadoTermico.EXCURSAO,
            RegrasTermicas.resumir(viagem, PerfilTermico.CONGELADO, 600).resultado,
        )
    }

    @Test
    fun `tolerancia do perfil descarta abertura curta de caixa`() {
        // Um ponto fora, dez minutos: é a caixa sendo aberta na doca, não uma
        // excursão. Sem a tolerância o cliente recebe alerta a cada manuseio e
        // para de ler os alertas — que é o pior desfecho possível.
        val comAberturaCurta = serie(5.0, 5.0, 9.0, 5.0, 5.0)
        val resumo = RegrasTermicas.resumir(comAberturaCurta, PerfilTermico.REFRIGERADO_2_8, 600)
        assertEquals(ResultadoTermico.CONFORME, resumo.resultado)
    }

    @Test
    fun `todo perfil diz o que anda nele`() {
        // O texto aparece na folha da etiqueta, embaixo dos chips. Um perfil
        // sem exemplo obriga o operador a deduzir a faixa pelo número.
        PerfilTermico.entries.forEach {
            assertTrue(it.name, it.exemplos.isNotBlank())
        }
    }
}
