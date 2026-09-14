package com.thermotrace.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thermotrace.app.data.db.EtiquetaEntity
import com.thermotrace.app.data.prefs.Preferencias
import com.thermotrace.app.data.repo.FluxoRapido
import com.thermotrace.app.data.repo.Repositorio
import com.thermotrace.app.data.repo.RepositorioAlertas
import com.thermotrace.app.domain.EstadoEtiqueta
import com.thermotrace.app.domain.Medicao
import com.thermotrace.app.domain.PerfilTermico
import com.thermotrace.app.domain.ResumoTermico
import com.thermotrace.app.domain.StatusRemessa
import com.thermotrace.app.domain.TipoLeitura
import com.thermotrace.app.nfc.ActivationPlan
import com.thermotrace.app.nfc.NfcOperator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.time.Duration
import java.time.Instant

data class LeituraUiState(
    val tipo: TipoLeitura = TipoLeitura.ATIVACAO,
    val volumeId: String = "",
    val remessaId: String = "",
    val codigoRemessa: String = "—",
    val rotuloVolume: String = "—",
    val perfil: PerfilTermico? = null,
    val etiquetaSerial: String? = null,
    val etiquetaId: String? = null,
    val uidEsperado: String? = null,

    val qrDigitado: String = "",
    val erroVinculo: String? = null,
    val erroNfc: String? = null,
    val mensagem: String? = null,

    val aguardandoTag: Boolean = false,
    val processando: Boolean = false,
    val operacaoPendente: NfcOperator.Operation? = null,
    /**
     * Confronto entre o cabeçalho da etiqueta e a série decodificada.
     * Nulo até haver coleta. Ver [com.thermotrace.app.nfc.Reconciliacao].
     */
    val reconciliacao: com.thermotrace.app.nfc.Reconciliacao? = null,

    val leituraInstantanea: NfcOperator.TagSnapshot? = null,
    val plano: ActivationPlan? = null,
    val podeAtivar: Boolean = false,

    val sessaoId: String? = null,
    val ativacaoFeita: Boolean = false,
    val quantidadeCheckpoints: Int = 0,
    val finalFeita: Boolean = false,

    val resumo: ResumoTermico? = null,
    val medicoes: List<Medicao> = emptyList(),
    val derivaSegundos: Long? = null,

    val estadoEtiqueta: EstadoEtiqueta = EstadoEtiqueta.NAO_ATIVADA,
    /** Piso de tensão vigente. Vem dos Ajustes; ver [Preferencias]. */
    val tensaoMinimaV: Double = 1.40,
    val verificando: Boolean = false,
    val ocorrenciasNovas: Int = 0,
    val podeFinalizar: Boolean = false,
    val concluido: Boolean = false,
    /**
     * A leitura já foi persistida, mas a tela continua aberta.
     *
     * Existe por causa do registro automático do checkpoint: a evidência entra
     * no banco na hora do bipe, e o operador vê o resultado depois, sem que a
     * tela feche debaixo dele. [concluido] continua significando "pode sair".
     */
    val registrada: Boolean = false,
    val salvando: Boolean = false,
    /**
     * Resultado do STOP físico na etiqueta.
     *
     * `null` = não foi tentado (checkpoint nunca para nada). `true` = parou e
     * está livre para o próximo ciclo. `false` = o histórico está salvo mas a
     * etiqueta CONTINUA gravando. Os três casos precisam ser distinguíveis na
     * tela: fechamento lógico não é prova de STOP físico.
     */
    val etiquetaLiberada: Boolean? = null,
    /** O fix que foi de fato gravado com a leitura. Nulo = sem localização. */
    val localizacao: com.thermotrace.app.data.local.FixLocal? = null,
)

class LeituraViewModel(
    private val repo: Repositorio,
    private val alertas: RepositorioAlertas,
    private val prefs: Preferencias,
    private val fluxo: FluxoRapido,
    /**
     * Nulo em teste e em aparelho sem serviços Google. A ausência é um caminho
     * previsto, não um defeito: a coleta acontece igual e a leitura fica sem
     * localização, de forma explícita.
     */
    private val localizador: com.thermotrace.app.data.local.Localizador? = null,
) : ViewModel() {

    private val _estado = MutableStateFlow(LeituraUiState())
    val estado: StateFlow<LeituraUiState> = _estado.asStateFlow()

    private var etiqueta: EtiquetaEntity? = null
    private var ativacaoPendente: NfcOperator.ActivationResult? = null

    /**
     * A leitura já interpretada, esperando o operador confirmar.
     *
     * Interpretar e gravar são passos separados nesta tela de propósito: o
     * operador vê o veredito antes de assinar embaixo dele. Quem calcula é o
     * mesmo [FluxoRapido] que o bipe rápido da tela inicial usa — dois
     * caminhos gravando evidência de auditoria não podem divergir.
     */
    private var previaPendente: FluxoRapido.Previa? = null

    /**
     * Fix da coleta em andamento.
     *
     * Pedido quando a tela arma e consumido quando a leitura é persistida. O
     * bipe NUNCA espera por ele: se não tiver chegado, a leitura é gravada sem
     * localização. Trocar evidência térmica por um metadado seria inverter a
     * prioridade do produto.
     */
    private var pedidoLocalizacao: kotlinx.coroutines.Job? = null
    @Volatile private var fixDaColeta: com.thermotrace.app.data.local.FixLocal? = null

    // -----------------------------------------------------------------

    fun carregar(volumeId: String, tipo: TipoLeitura) = viewModelScope.launch {
        if (_estado.value.volumeId == volumeId && _estado.value.tipo == tipo) return@launch
        previaPendente = null
        ativacaoPendente = null
        _estado.value = LeituraUiState(tipo = tipo)
        val volume = repo.buscarVolume(volumeId) ?: return@launch
        val remessa = repo.buscarRemessa(volume.remessaId) ?: return@launch
        val perfil = PerfilTermico.porCodigo(remessa.remessa.perfilTermicoCodigo)
        val sessao = repo.sessaoDoVolume(volumeId)
        etiqueta = volume.etiquetaId?.let { repo.buscarEtiqueta(it) }
        // Sem verificação agora: o estado sai como PRESUMIDO, e a tela diz isso.
        val estadoInicial = alertas.estadoDaEtiqueta(sessao, registrandoAgora = null)

        _estado.update {
            it.copy(
                tipo = tipo,
                volumeId = volumeId,
                remessaId = volume.remessaId,
                codigoRemessa = remessa.remessa.codigo,
                rotuloVolume = "Volume ${volume.sequencia}" +
                    (volume.codigoExterno?.let { c -> " · $c" } ?: ""),
                perfil = perfil,
                etiquetaSerial = etiqueta?.serial,
                etiquetaId = etiqueta?.id,
                // Na ativação a identidade vem do QR; nas outras, já está na sessão.
                uidEsperado = if (tipo == TipoLeitura.ATIVACAO) etiqueta?.uidNfc?.takeIf { sessao != null }
                else etiqueta?.uidNfc,
                sessaoId = sessao?.sessao?.id,
                ativacaoFeita = sessao != null,
                quantidadeCheckpoints = sessao?.leituras?.count { l ->
                    l.tipo == TipoLeitura.CHECKPOINT
                } ?: 0,
                finalFeita = sessao?.leituras?.any { l -> l.tipo == TipoLeitura.FINAL } == true,
                plano = if (tipo == TipoLeitura.ATIVACAO)
                    montarPlano(perfil, remessa.remessa.intervaloSegundos,
                        remessa.remessa.previsaoEntregaMillis) else null,
                estadoEtiqueta = estadoInicial,
                tensaoMinimaV = prefs.atual.tensaoMinimaV,
            )
        }
        // Checkpoint e leitura final entram armados: o gesto e sempre o mesmo
        // (encostar a etiqueta), e exigir um toque no botao antes disso fazia
        // a etiqueta ser lida pelo visualizador de tag do sistema.
        if (tipo != TipoLeitura.ATIVACAO && prontoParaBaixar()) baixar()
    }

    private fun montarPlano(perfil: PerfilTermico, intervalo: Int, previsaoEntregaMillis: Long?): ActivationPlan? {
        val duracao = previsaoEntregaMillis
            ?.let { Duration.ofMillis(it - System.currentTimeMillis()) }
            ?.takeIf { !it.isNegative && it.toHours() > 0 }
            ?: Duration.ofHours(72)   // padrão conservador quando não há previsão

        return ActivationPlan.forShipment(
            plannedDurationHours = duracao.toMinutes() / 60.0,
            intervalSeconds = intervalo,
            minC = perfil.minC,
            maxC = perfil.maxC,
            safetyFactor = prefs.atual.fatorSeguranca,
        ).getOrNull()
    }

    // -----------------------------------------------------------------
    // Vínculo pelo QR — sempre antes de qualquer escrita
    // -----------------------------------------------------------------

    fun alterarQr(valor: String) = _estado.update { it.copy(qrDigitado = valor, erroVinculo = null) }

    fun vincular() = viewModelScope.launch {
        val qr = _estado.value.qrDigitado.trim()
        when (val r = repo.vincularPorQr(_estado.value.volumeId, qr)) {
            is Repositorio.ResultadoVinculo.Ok -> {
                etiqueta = r.etiqueta
                _estado.update {
                    it.copy(
                        etiquetaSerial = r.etiqueta.serial,
                        etiquetaId = r.etiqueta.id,
                        uidEsperado = r.etiqueta.uidNfc,
                        erroVinculo = null,
                        mensagem = "Etiqueta ${r.etiqueta.serial} vinculada. " +
                            "Agora encoste-a no celular.",
                    )
                }
            }
            Repositorio.ResultadoVinculo.QrDesconhecido -> _estado.update {
                it.copy(erroVinculo = "Código não cadastrado. Cadastre a etiqueta em Etiquetas.")
            }
            is Repositorio.ResultadoVinculo.JaVinculada -> _estado.update {
                it.copy(erroVinculo = "Esta etiqueta já está em outra remessa ativa.")
            }
            is Repositorio.ResultadoVinculo.Aposentada -> _estado.update {
                it.copy(erroVinculo = r.motivo)
            }
        }
    }

    // -----------------------------------------------------------------
    // Operações NFC
    // -----------------------------------------------------------------

    /**
     * Verificação rápida: lê só o bit de status e a tensão.
     * É o que transforma "presumida ativa" em "confirmada ativa".
     */
    fun verificarEtiqueta() = _estado.update {
        it.copy(
            verificando = true, aguardandoTag = true, erroNfc = null,
            operacaoPendente = NfcOperator.Operation.VerifyStatus,
        )
    }

    fun identificar() = _estado.update {
        it.copy(
            aguardandoTag = true, erroNfc = null, mensagem = null,
            operacaoPendente = NfcOperator.Operation.Identify,
        )
    }

    fun ativar() {
        val plano = _estado.value.plano ?: run {
            _estado.update {
                it.copy(erroNfc = "Não foi possível montar o plano de gravação. " +
                    "Revise a previsão de entrega e o intervalo.")
            }
            return
        }
        _estado.update {
            it.copy(
                aguardandoTag = true, processando = true, erroNfc = null,
                operacaoPendente = NfcOperator.Operation.Activate(plano),
            )
        }
    }

    /**
     * A coleta so pode ser armada quando ha sessao ativa, etiqueta vinculada e
     * o monitoramento ainda nao foi encerrado.
     *
     * Existe separado de [baixar] porque o checkpoint e a leitura final se
     * armam sozinhos ao abrir a tela: sem esta checagem, abrir a tela de um
     * volume ja encerrado mostrava um erro que o operador nao provocou.
     */
    private fun prontoParaBaixar(): Boolean {
        val e = _estado.value
        return !e.uidEsperado.isNullOrBlank() && e.sessaoId != null && !e.finalFeita
    }

    fun baixar() {
        if (_estado.value.processando || _estado.value.salvando || _estado.value.podeFinalizar) return
        if (!prontoParaBaixar()) {
            _estado.update { it.copy(erroNfc = "A coleta precisa de uma sessão ativa e uma etiqueta vinculada.") }
            return
        }
        previaPendente = null
        // Em paralelo, sem bloquear nada. Entre armar a tela e o operador
        // encostar a etiqueta há segundos de sobra para o fix chegar; se não
        // chegar, segue sem.
        pedidoLocalizacao?.cancel()
        fixDaColeta = null
        pedidoLocalizacao = localizador?.let { l -> viewModelScope.launch { fixDaColeta = l.obter() } }
        _estado.update {
            it.copy(
                aguardandoTag = true, processando = false, erroNfc = null,
                podeFinalizar = false, resumo = null, medicoes = emptyList(),
                registrada = false, reconciliacao = null, etiquetaLiberada = null,
                mensagem = "Aproxime a etiqueta e mantenha o celular parado até terminar.",
                // Abrir a tela só lê. STOP exige confirmação e evidência já persistida (D08).
                operacaoPendente = NfcOperator.Operation.Download(
                    if (it.tipo == TipoLeitura.FINAL) "destino" else "checkpoint"),
            )
        }
    }

    /**
     * Repete o STOP na etiqueta, sem baixar de novo.
     *
     * Existe porque STOP falho é resultado possível e não pode virar beco sem
     * saída: o histórico já está salvo, e o que falta é liberar o chip.
     */
    fun pararRegistro() {
        val e = _estado.value
        if (e.tipo != TipoLeitura.FINAL || !e.registrada || e.processando || e.salvando) return
        _estado.update { it.copy(
            aguardandoTag = true, erroNfc = null,
            mensagem = "Aproxime a etiqueta para parar o registro…",
            operacaoPendente = NfcOperator.Operation.StopLogging(),
        ) }
    }

    fun aoEventoNfc(evento: NfcOperator.NfcEvent) {
        when (evento) {
            is NfcOperator.NfcEvent.TagSeen ->
                _estado.update { it.copy(operacaoPendente = null, aguardandoTag = false, processando = true) }

            is NfcOperator.NfcEvent.WrongTag -> _estado.update {
                it.copy(
                    aguardandoTag = false, processando = false, operacaoPendente = null,
                    erroNfc = "Etiqueta errada. Esperada ${evento.expected}, " +
                        "encontrada ${evento.found}. Nada foi gravado.",
                )
            }

            is NfcOperator.NfcEvent.Identified -> _estado.update {
                val tensao = evento.snapshot.voltageV
                val piso = it.tensaoMinimaV
                it.copy(
                    aguardandoTag = false, processando = false,
                    leituraInstantanea = evento.snapshot,
                    podeAtivar = tensao == null || tensao >= piso,
                    mensagem = if (tensao != null && tensao < piso)
                        "Bateria em %.2f V, abaixo do mínimo de %.2f V. Troque a etiqueta."
                            .format(tensao, piso)
                    else "Etiqueta conferida. Pode ativar.",
                )
            }

            is NfcOperator.NfcEvent.Activated -> {
                ativacaoPendente = evento.result
                _estado.update {
                    it.copy(
                        aguardandoTag = false, processando = false,
                        podeFinalizar = true,
                        mensagem = if (evento.result.verified)
                            "Registro iniciado e confirmado pela etiqueta."
                        else
                            "START aceito, mas a etiqueta não confirmou o estado de registro. " +
                                "Encoste novamente para conferir antes de despachar.",
                    )
                }
            }

            is NfcOperator.NfcEvent.StatusVerificado -> {
                registrarVerificacao(evento)
            }

            is NfcOperator.NfcEvent.Downloaded -> processarDownload(evento.read)

            is NfcOperator.NfcEvent.LoggingParado -> viewModelScope.launch {
                val id = _estado.value.sessaoId
                _estado.update { it.copy(salvando = true) }
                try {
                    if (evento.ok) repo.marcarLoggerParado(checkNotNull(id))
                    _estado.update { it.copy(
                        aguardandoTag = false, processando = false, operacaoPendente = null,
                        etiquetaLiberada = evento.ok,
                        mensagem = if (evento.ok) "STOP aceito e confirmação registrada. Confira o estado antes do próximo ciclo."
                        else "A etiqueta recusou o STOP. O histórico está salvo; encoste novamente para tentar parar.",
                    ) }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    _estado.update { it.copy(aguardandoTag = false, processando = false,
                        etiquetaLiberada = null, erroNfc = "A etiqueta respondeu, mas não foi possível gravar a confirmação do STOP. Confira novamente.") }
                } finally { _estado.update { it.copy(salvando = false) } }
            }

            is NfcOperator.NfcEvent.Failed -> _estado.update {
                it.copy(
                    aguardandoTag = false, processando = false, operacaoPendente = null,
                    erroNfc = "${evento.stage}: ${evento.message}",
                )
            }

            // Diagnostico e termometro ao vivo so existem na tela de
            // Diagnostico. Ignorar aqui de forma explicita e melhor que um
            // `else`: se um evento novo aparecer, o compilador cobra uma
            // decisao em vez de deixar a leitura engolir em silencio.
            is NfcOperator.NfcEvent.Diagnosticado,
            is NfcOperator.NfcEvent.ConfiguracaoLida,
            is NfcOperator.NfcEvent.MedidaAoVivo,
            NfcOperator.NfcEvent.AoVivoEncerrado -> Unit
        }
    }

    private fun registrarVerificacao(evento: NfcOperator.NfcEvent.StatusVerificado) =
        viewModelScope.launch {
            val sessaoId = _estado.value.sessaoId
            if (sessaoId != null) {
                alertas.registrarVerificacao(sessaoId, evento.registrando, evento.voltageV)
            }
            val sessao = repo.sessaoDoVolume(_estado.value.volumeId)
            val estadoEtiqueta = alertas.estadoDaEtiqueta(sessao, evento.registrando)
            _estado.update {
                it.copy(
                    verificando = false, aguardandoTag = false, processando = false,
                    estadoEtiqueta = estadoEtiqueta,
                    mensagem = estadoEtiqueta.resumo,
                )
            }
        }

    private fun processarDownload(leitura: NfcOperator.RawRead) = viewModelScope.launch {
        pedidoLocalizacao?.cancel() // O fix já disponível é o único elegível para este bipe.
        fluxo.analisar(_estado.value.volumeId, leitura)
            .onSuccess { previa ->
                previaPendente = previa
                _estado.update {
                    it.copy(
                        aguardandoTag = false, processando = false,
                        sessaoId = previa.sessao.sessao.id,
                        medicoes = previa.medicoes,
                        resumo = previa.resumo,
                        derivaSegundos = previa.decodificada.rtcDriftSeconds,
                        reconciliacao = previa.decodificada.reconciliacao,
                        etiquetaLiberada = leitura.loggerParado,
                        podeFinalizar = _estado.value.tipo != TipoLeitura.CHECKPOINT,
                        mensagem = "Histórico recuperado: ${previa.medicoes.size} registros.",
                    )
                }
                // Checkpoint grava na hora do bipe. Ativação e leitura final
                // continuam esperando o toque do operador.
                if (_estado.value.tipo == TipoLeitura.CHECKPOINT) {
                    registrarCheckpointAutomatico()
                }
            }
            .onFailure { erro ->
                _estado.update {
                    it.copy(
                        aguardandoTag = false, processando = false,
                        erroNfc = erro.message
                            ?: "Não foi possível interpretar o histórico.",
                    )
                }
            }
    }

    // -----------------------------------------------------------------
    // Persistência
    // -----------------------------------------------------------------

    fun confirmar() = viewModelScope.launch {
        if (_estado.value.registrada || _estado.value.salvando) return@launch
        if (salvarComRetorno()) {
            val final = _estado.value.tipo == TipoLeitura.FINAL
            _estado.update { it.copy(registrada = true, podeFinalizar = false,
                finalFeita = final || it.finalFeita, concluido = !final) }
            // A confirmação autoriza o STOP, somente após o commit local do histórico.
            if (final) pararRegistro()
        }
    }

    /**
     * Registro automático do checkpoint, decidido pelo usuário em 12/09/2026.
     *
     * Vale só para o checkpoint, e a razão está na semântica que o próprio
     * [TipoLeitura] já declara: checkpoint não é obrigatório e não escreve na
     * etiqueta — é observação de leitura. Ativação escreve configuração no
     * chip e leitura final encerra o monitoramento e fecha o laudo; essas duas
     * seguem exigindo ato explícito do operador.
     *
     * Automatizar aqui não afrouxa a cadeia de custódia, reforça: o risco real
     * medido no ensaio de 12/09 foi evidência baixada e perdida na volta, não
     * evidência registrada sem conferência. O operador continua vendo o
     * veredito — só não precisa mais autorizar a gravação daquilo que a
     * etiqueta já havia registrado por conta própria.
     */
    private suspend fun registrarCheckpointAutomatico() {
        if (salvarComRetorno()) _estado.update {
            it.copy(
                registrada = true,
                podeFinalizar = false,
                quantidadeCheckpoints = it.quantidadeCheckpoints + 1,
                mensagem = "Checkpoint registrado: ${it.medicoes.size} registros. " +
                    "Pode afastar o celular.",
            )
        }
    }

    private suspend fun salvarComRetorno(): Boolean {
        if (_estado.value.salvando || _estado.value.registrada) return false
        _estado.update { it.copy(salvando = true, erroNfc = null) }
        return try {
            check(persistir()) { "A coleta ainda não está pronta para registrar." }
            previaPendente = null
            ativacaoPendente = null
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _estado.update { it.copy(erroNfc = "Não foi possível registrar a coleta. Tente registrar novamente.",
                podeFinalizar = true, registrada = false) }
            false
        } finally {
            _estado.update { it.copy(salvando = false) }
        }
    }

    /** Persiste a leitura. Devolve falso quando falta insumo para gravar. */
    private suspend fun persistir(): Boolean {
        val e = _estado.value
        when (e.tipo) {
            TipoLeitura.ATIVACAO -> {
                val resultado = ativacaoPendente ?: return false
                val plano = e.plano ?: return false
                val etiquetaId = e.etiquetaId ?: return false
                val perfil = e.perfil ?: return false

                repo.registrarAtivacao(
                    remessaId = e.remessaId,
                    volumeId = e.volumeId,
                    etiquetaId = etiquetaId,
                    perfil = perfil,
                    epochInicioEtiqueta = resultado.tagStartEpoch,
                    inicioDispositivoMillis = resultado.deviceStartAtMillis,
                    desvioRelogioMs = null,   // preenchido pelo servidor na sincronização
                    baseDeTempo = plano.timeBase.name,
                    delayMinutos = plano.delayMinutes,
                    intervaloSegundos = plano.intervalSeconds,
                    quantidadePlanejada = plano.loggingCount,
                    modoArmazenamento = plano.storageMode.sdkMode,
                    confirmada = resultado.verified,
                    tensaoV = resultado.voltageAtStartV,
                )
                repo.registrarCustodia(
                    remessaId = e.remessaId,
                    de = null, para = "embarcador",
                    ocorridoEm = Instant.ofEpochMilli(resultado.deviceStartAtMillis),
                    origem = "automatico_nfc",
                    volumesConfirmados = null, recebedor = null,
                    observacao = "Monitoramento iniciado no volume ${e.rotuloVolume}",
                    local = null,
                )
                repo.definirStatus(e.remessaId, StatusRemessa.AGUARDANDO_COLETA)
            }

            TipoLeitura.CHECKPOINT, TipoLeitura.FINAL -> {
                // Gravar a leitura, abrir ocorrência, enfileirar alerta e
                // fechar a custódia é o mesmo caminho do bipe rápido. Ver
                // FluxoRapido: duplicar isso produziria dois laudos possíveis
                // para a mesma leitura.
                val previa = previaPendente ?: return false
                val fix = fixDaColeta
                val resultado = fluxo.persistir(previa, e.tipo, fix)
                _estado.update { it.copy(localizacao = fix) }
                _estado.update { it.copy(ocorrenciasNovas = resultado.ocorrenciasNovas) }
            }
        }
        return true
    }
}
