package com.thermotrace.app

import com.thermotrace.app.data.db.*
import com.thermotrace.app.data.prefs.*
import com.thermotrace.app.data.repo.*
import com.thermotrace.app.domain.*
import com.thermotrace.app.nfc.*
import com.thermotrace.app.ui.screens.LeituraViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.mockito.Mockito.*

@OptIn(ExperimentalCoroutinesApi::class)
class LeituraViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mock(Repositorio::class.java)
    private val alertas = mock(RepositorioAlertas::class.java)
    private val prefs = mock(Preferencias::class.java)
    private val fluxo = mock(FluxoRapido::class.java)
    private lateinit var vm: LeituraViewModel
    private lateinit var previa: FluxoRapido.Previa
    private val raw = NfcOperator.RawRead("0102", "checkpoint",
        listOf("1", "1700000000", "648", "2", "0", "600", "4", "5", "2", "8", "0", "0", "4", "5"),
        1700000650000L, null, 5.0, null)

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    private suspend fun preparar(tipo: TipoLeitura) {
        val volume = mock(VolumeEntity::class.java)
        val remessa = mock(RemessaEntity::class.java)
        val completa = mock(RemessaCompleta::class.java)
        val sessao = mock(SessaoEntity::class.java)
        val leituras = mock(SessaoComLeituras::class.java)
        val etiqueta = mock(EtiquetaEntity::class.java)
        `when`(volume.remessaId).thenReturn("r")
        `when`(volume.etiquetaId).thenReturn("e")
        `when`(completa.remessa).thenReturn(remessa)
        `when`(remessa.codigo).thenReturn("TESTE")
        `when`(remessa.perfilTermicoCodigo).thenReturn(PerfilTermico.REFRIGERADO_2_8.codigo)
        `when`(leituras.sessao).thenReturn(sessao)
        `when`(leituras.leituras).thenReturn(emptyList())
        `when`(sessao.id).thenReturn("s")
        `when`(etiqueta.id).thenReturn("e")
        `when`(etiqueta.uidNfc).thenReturn("0102")
        `when`(repo.buscarVolume("v")).thenReturn(volume)
        `when`(repo.buscarRemessa("r")).thenReturn(completa)
        `when`(repo.sessaoDoVolume("v")).thenReturn(leituras)
        `when`(repo.buscarEtiqueta("e")).thenReturn(etiqueta)
        `when`(prefs.atual).thenReturn(Ajustes())
        `when`(alertas.estadoDaEtiqueta(leituras, null)).thenReturn(EstadoEtiqueta.NAO_ATIVADA)
        val decoded = SessionDecoder.decode(raw.response, raw.deviceReadAtMillis, TimeBase.START_INSTANT).getOrThrow()
        previa = FluxoRapido.Previa(leituras, remessa, volume, PerfilTermico.REFRIGERADO_2_8,
            decoded, emptyList(), mock(ResumoTermico::class.java), raw)
        `when`(fluxo.analisar("v", raw)).thenReturn(Result.success(previa))
        `when`(fluxo.persistir(previa, tipo)).thenReturn(FluxoRapido.Resultado(previa, 0, tipo == TipoLeitura.FINAL))
        vm = LeituraViewModel(repo, alertas, prefs, fluxo)
        vm.carregar("v", tipo).join()
    }

    @Test fun `dois checkpoints registram e limpam sucesso anterior`() = runTest(dispatcher) {
        preparar(TipoLeitura.CHECKPOINT)
        assertEquals(NfcOperator.Operation.Download("checkpoint"), vm.estado.value.operacaoPendente)
        vm.aoEventoNfc(NfcOperator.NfcEvent.Downloaded(raw)); advanceUntilIdle()
        assertTrue(vm.estado.value.registrada)
        assertFalse(vm.estado.value.concluido)
        vm.baixar()
        assertFalse(vm.estado.value.registrada)
        vm.aoEventoNfc(NfcOperator.NfcEvent.Downloaded(raw)); advanceUntilIdle()
        assertTrue(vm.estado.value.registrada)
        assertEquals(2, vm.estado.value.quantidadeCheckpoints)
        verify(fluxo, times(2)).persistir(previa, TipoLeitura.CHECKPOINT)
    }

    @Test fun `falha ao salvar nao vira sucesso e permite repetir registro`() = runTest(dispatcher) {
        preparar(TipoLeitura.CHECKPOINT)
        `when`(fluxo.persistir(previa, TipoLeitura.CHECKPOINT))
            .thenThrow(IllegalStateException("disco indisponível"))
            .thenReturn(FluxoRapido.Resultado(previa, 0, false))
        vm.aoEventoNfc(NfcOperator.NfcEvent.Downloaded(raw)); advanceUntilIdle()
        assertFalse(vm.estado.value.registrada)
        assertTrue(vm.estado.value.podeFinalizar)
        assertNotNull(vm.estado.value.erroNfc)
        vm.confirmar().join()
        assertTrue(vm.estado.value.registrada)
    }

    @Test fun `final so envia STOP depois de confirmar e persistir`() = runTest(dispatcher) {
        preparar(TipoLeitura.FINAL)
        assertEquals(NfcOperator.Operation.Download("destino"), vm.estado.value.operacaoPendente)
        vm.aoEventoNfc(NfcOperator.NfcEvent.Downloaded(raw)); advanceUntilIdle()
        verify(fluxo, never()).persistir(previa, TipoLeitura.FINAL)
        vm.pararRegistro()
        assertFalse(vm.estado.value.operacaoPendente is NfcOperator.Operation.StopLogging)
        vm.confirmar().join()
        verify(fluxo).persistir(previa, TipoLeitura.FINAL)
        assertTrue(vm.estado.value.registrada)
        assertFalse(vm.estado.value.concluido)
        assertTrue(vm.estado.value.operacaoPendente is NfcOperator.Operation.StopLogging)
        vm.confirmar().join()
        verify(fluxo, times(1)).persistir(previa, TipoLeitura.FINAL)
    }

    @Test fun `final com falha de persistencia nunca para etiqueta`() = runTest(dispatcher) {
        preparar(TipoLeitura.FINAL)
        `when`(fluxo.persistir(previa, TipoLeitura.FINAL)).thenThrow(IllegalStateException("falha"))
        vm.aoEventoNfc(NfcOperator.NfcEvent.Downloaded(raw)); advanceUntilIdle()
        vm.confirmar().join()
        assertFalse(vm.estado.value.registrada)
        assertFalse(vm.estado.value.operacaoPendente is NfcOperator.Operation.StopLogging)
        assertTrue(vm.estado.value.podeFinalizar)
    }

    @Test fun `STOP falho preserva coleta e permite repetir so a parada`() = runTest(dispatcher) {
        preparar(TipoLeitura.FINAL)
        vm.aoEventoNfc(NfcOperator.NfcEvent.Downloaded(raw)); advanceUntilIdle()
        vm.confirmar().join()
        vm.aoEventoNfc(NfcOperator.NfcEvent.LoggingParado("0102", false))
        assertEquals(false, vm.estado.value.etiquetaLiberada)
        assertTrue(vm.estado.value.registrada)
        vm.pararRegistro()
        assertTrue(vm.estado.value.operacaoPendente is NfcOperator.Operation.StopLogging)
        verify(fluxo, times(1)).persistir(previa, TipoLeitura.FINAL)
    }
}
