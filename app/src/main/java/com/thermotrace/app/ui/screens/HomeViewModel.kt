package com.thermotrace.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thermotrace.app.data.db.EtiquetaEntity
import com.thermotrace.app.data.db.RemessaEntity
import com.thermotrace.app.data.prefs.Preferencias
import com.thermotrace.app.data.repo.FluxoRapido
import com.thermotrace.app.data.repo.Repositorio
import com.thermotrace.app.data.repo.RepositorioAlertas
import com.thermotrace.app.domain.DocumentoFiscal
import com.thermotrace.app.domain.PerfilTermico
import com.thermotrace.app.domain.ResultadoTermico
import com.thermotrace.app.domain.StatusRemessa
import com.thermotrace.app.domain.TipoLeitura
import com.thermotrace.app.nfc.ActivationPlan
import com.thermotrace.app.nfc.NfcOperator
import com.thermotrace.app.nfc.TagIdentity
import com.thermotrace.app.ui.components.AcaoEtiqueta
import com.thermotrace.app.ui.components.EtiquetaBipada
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Recortes da lista de remessas.
 *
 * Um cliente com trinta remessas abertas rolava a lista inteira para achar a
 * que estava esperando leitura final — que é justamente a que segura o
 * faturamento. O recorte responde "o que exige ação minha agora?".
 */
enum class FiltroRemessa(val rotulo: String) {
    TODAS("Todas"),
    EM_ANDAMENTO("Em andamento"),
    AGUARDANDO_LEITURA("Aguardando leitura"),
    CONCLUIDAS("Concluídas");

    fun aceita(r: RemessaEntity): Boolean = when (this) {
        TODAS -> true
        EM_ANDAMENTO -> r.status !in setOf(
            StatusRemessa.CONCLUIDA,
            StatusRemessa.CANCELADA,
            StatusRemessa.ENTREGUE_AGUARDANDO_LEITURA,
        )
        AGUARDANDO_LEITURA -> r.status == StatusRemessa.ENTREGUE_AGUARDANDO_LEITURA
        CONCLUIDAS -> r.status == StatusRemessa.CONCLUIDA
    }
}

/** Quantas remessas caem em cada recorte. Vira contador no próprio chip. */
data class ContagemRemessas(
    val total: Int = 0,
    val emAndamento: Int = 0,
    val aguardandoLeitura: Int = 0,
    val concluidas: Int = 0,
) {
    fun de(filtro: FiltroRemessa): Int = when (filtro) {
        FiltroRemessa.TODAS -> total
        FiltroRemessa.EM_ANDAMENTO -> emAndamento
        FiltroRemessa.AGUARDANDO_LEITURA -> aguardandoLeitura
        FiltroRemessa.CONCLUIDAS -> concluidas
    }
}

/**
 * O passo 2 do fluxo: a remessa está ligada e ainda não tem nota fiscal.
 *
 * Fica na tela inicial, não numa tela de formulário, porque é o que o
 * operador faz logo depois de fechar a caixa — com o celular na mão e a
 * DANFE na outra.
 */
data class RemessaEmAberto(
    val id: String,
    val codigo: String,
    val documento: String?,   // null = ainda falta bipar a nota
    val etiquetaSerial: String?,
)

    /** O que apareceu quando o operador baixou o histórico e encerrou a entrega. */
data class ResultadoEntrega(
    val remessaId: String,
    val codigoRemessa: String,
    val veredito: ResultadoTermico,
    val quantidadeMedicoes: Int,
    val minimaC: Double?,
    val maximaC: Double?,
    val mktC: Double?,
    val tempoForaFaixaSegundos: Long,
    val ocorrenciasNovas: Int,
    /**
     * A etiqueta foi desligada junto com o encerramento.
     *
     * `false` importa e vai para a tela: a remessa fechou, mas a etiqueta
     * continua gravando na prateleira e vai recusar o próximo START.
     * `null` é o caso em que o STOP nem foi tentado.
     */
    val etiquetaLiberada: Boolean?,
)

data class HomeUiState(
    val bipada: EtiquetaBipada? = null,      // null = folha fechada
    val serialDigitado: String = "",
    val perfil: PerfilTermico = PerfilTermico.REFRIGERADO_2_8,
    val horas: String = "72",
    val operacaoPendente: NfcOperator.Operation? = null,
    val aguardandoTag: Boolean = true,       // a Home fica sempre ouvindo
    val remessaParaAbrir: String? = null,
    val aviso: String? = null,
    /** Remessa que o operador segurou e quer apagar. null = sem dialogo. */
    val remessaParaApagar: RemessaEntity? = null,
    val busca: String = "",
    val filtro: FiltroRemessa = FiltroRemessa.TODAS,
    /** Ajustes vigentes, para a folha da etiqueta prever o que será gravado. */
    val intervaloSegundos: Int = 600,
    val fatorSeguranca: Double = 1.5,
    val tensaoMinimaV: Double = 1.40,
    val piscarLed: Boolean = true,
    /** Passo 2: a remessa recém-ligada, esperando a nota fiscal. */
    val emAberto: RemessaEmAberto? = null,
    /** Passo 3: o veredito, logo depois do bipe de encerramento. */
    val resultado: ResultadoEntrega? = null,
)

/**
 * A Home é a tela de bipar, e o app inteiro cabe nela.
 *
 * O fluxo de campo tem três gestos e nenhum formulário obrigatório:
 *
 *   1. **bipar → Ativar**   a etiqueta liga; o celular vibra duas vezes
 *   2. **bipar a nota**     a câmera lê a DANFE e amarra à remessa
 *   3. **bipar → Finalizar** baixa o histórico, encerra e mostra o veredito
 *
 * O reader mode fica ligado o tempo todo aqui, sem botão: a etiqueta está na
 * mão do operador, e pedir que ele toque em algo antes de encostar inverte a
 * ordem natural. Foi o app do fabricante que mostrou isso.
 */
class HomeViewModel(
    private val repo: Repositorio,
    alertas: RepositorioAlertas,
    private val prefs: Preferencias,
    private val fluxo: FluxoRapido,
) : ViewModel() {

    val remessas: StateFlow<List<RemessaEntity>> =
        repo.observarRemessas()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pendentesEnvio: StateFlow<Int> =
        repo.observarPendentesDeEnvio()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Evidencia que existe so aqui dentro. Ver [Repositorio.observarNaoExportadas]. */
    val naoExportadas: StateFlow<Int> =
        repo.observarNaoExportadas()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val ocorrenciasAbertas: StateFlow<Int> =
        alertas.contarAbertas()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _estado = MutableStateFlow(
        // O formulário já abre com o padrão do cliente, não com o do código.
        HomeUiState(
            perfil = prefs.atual.perfilPadrao,
            horas = prefs.atual.horasPadrao.toString(),
            intervaloSegundos = prefs.atual.intervaloSegundos,
            fatorSeguranca = prefs.atual.fatorSeguranca,
            tensaoMinimaV = prefs.atual.tensaoMinimaV,
            piscarLed = prefs.atual.piscarLed,
        )
    )
    val estado = _estado.asStateFlow()

    /** O que a lista mostra: recorte e busca já aplicados. */
    val remessasVisiveis: StateFlow<List<RemessaEntity>> =
        combine(remessas, _estado) { lista, e ->
            val termo = e.busca.trim()
            lista.filter { e.filtro.aceita(it) }.filter { r ->
                termo.isBlank() ||
                    r.codigo.contains(termo, ignoreCase = true) ||
                    r.destinatario.contains(termo, ignoreCase = true) ||
                    r.transportadora.contains(termo, ignoreCase = true) ||
                    r.identidadeDocumento?.contains(termo, ignoreCase = true) == true
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val contagem: StateFlow<ContagemRemessas> =
        remessas.map { lista ->
            ContagemRemessas(
                total = lista.size,
                emAndamento = lista.count { FiltroRemessa.EM_ANDAMENTO.aceita(it) },
                aguardandoLeitura = lista.count { FiltroRemessa.AGUARDANDO_LEITURA.aceita(it) },
                concluidas = lista.count { FiltroRemessa.CONCLUIDAS.aceita(it) },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ContagemRemessas())

    private var etiquetaAtual: EtiquetaEntity? = null

    /** Volume da remessa que o bipe de encerramento vai fechar. */
    private var volumeParaFinalizar: String? = null

    // -----------------------------------------------------------------

    fun alterarSerial(v: String) = _estado.update { it.copy(serialDigitado = v) }
    fun escolherPerfil(p: PerfilTermico) = _estado.update { it.copy(perfil = p) }
    fun alterarHoras(v: String) = _estado.update { it.copy(horas = v) }
    fun consumirNavegacao() = _estado.update { it.copy(remessaParaAbrir = null) }
    fun operacaoConsumida() = _estado.update { it.copy(operacaoPendente = null) }
    fun buscar(v: String) = _estado.update { it.copy(busca = v) }
    fun filtrar(f: FiltroRemessa) = _estado.update { it.copy(filtro = f) }
    fun dispensarResultado() = _estado.update { it.copy(resultado = null) }
    fun dispensarEmAberto() = _estado.update { it.copy(emAberto = null) }

    fun aoEventoNfc(evento: NfcOperator.NfcEvent) {
        when (evento) {
            is NfcOperator.NfcEvent.Identified -> abrirFolha(evento.snapshot)

            is NfcOperator.NfcEvent.Activated -> registrarAtivacao(evento.result)

            is NfcOperator.NfcEvent.Downloaded -> encerrarComLeitura(evento.read)

            is NfcOperator.NfcEvent.LoggingParado -> _estado.update {
                it.copy(
                    bipada = it.bipada?.copy(
                        ocupada = false,
                        registrando = if (evento.ok) false else it.bipada.registrando,
                        mensagem = if (evento.ok)
                            "Registro parado. A etiqueta está livre para o próximo ciclo."
                        else
                            "A etiqueta recusou o STOP. Pode não estar registrando, ou " +
                                "ter senha diferente da padrão de fábrica.",
                    )
                )
            }

            is NfcOperator.NfcEvent.WrongTag -> _estado.update {
                it.copy(
                    bipada = it.bipada?.copy(
                        ocupada = false,
                        mensagem = "Etiqueta errada. Esperada ${evento.expected}, " +
                            "encontrada ${evento.found}. Nada foi gravado.",
                    )
                )
            }

            is NfcOperator.NfcEvent.Failed -> _estado.update {
                it.copy(
                    bipada = it.bipada?.copy(ocupada = false, mensagem = evento.message),
                    aviso = if (it.bipada == null) evento.message else it.aviso,
                )
            }

            else -> Unit
        }
    }

    private fun abrirFolha(snapshot: NfcOperator.TagSnapshot) = viewModelScope.launch {
        val etiqueta = repo.etiquetaPorUid(snapshot.uid)
        etiquetaAtual = etiqueta
        val remessa = etiqueta?.let { repo.remessaAtivaDaEtiqueta(it.id) }
        val documentoVinculado = !remessa?.identidadeDocumento.isNullOrBlank()
        // O volume fica guardado agora, na bipada, e nao na hora de finalizar:
        // depois do download a etiqueta ja saiu do campo, e voltar ao banco
        // atras do vinculo seria uma ida a mais no meio da operacao.
        volumeParaFinalizar = remessa?.let { r ->
            repo.buscarRemessa(r.id)?.volumes
                ?.firstOrNull { it.etiquetaId == etiqueta.id }?.id
        }
        // Relidos a cada bipada: o operador pode ter passado pelos Ajustes
        // entre uma etiqueta e a próxima, e a prévia tem que dizer o que
        // realmente vai ser gravado agora.
        val ajustes = prefs.atual

        _estado.update {
            val lembreteDocumento = when {
                remessa == null -> it.emAberto
                !documentoVinculado -> RemessaEmAberto(
                    id = remessa.id,
                    codigo = remessa.codigo,
                    documento = null,
                    etiquetaSerial = etiqueta.serial,
                )
                it.emAberto?.id == remessa.id -> null
                else -> it.emAberto
            }
            it.copy(
                intervaloSegundos = ajustes.intervaloSegundos,
                fatorSeguranca = ajustes.fatorSeguranca,
                tensaoMinimaV = ajustes.tensaoMinimaV,
                piscarLed = ajustes.piscarLed,
                bipada = EtiquetaBipada(
                    uid = TagIdentity.canonical(snapshot.uid),
                    serial = etiqueta?.serial,
                    registrando = snapshot.registrando,
                    temperaturaC = snapshot.temperatureC,
                    tensaoV = snapshot.voltageV,
                    remessaVinculada = remessa?.codigo,
                    documentoVinculado = documentoVinculado,
                    tecnologia = snapshot.tecnologia,
                ),
                emAberto = lembreteDocumento,
                serialDigitado = "",
                aviso = null,
                resultado = null,
            )
        }
    }

    // -----------------------------------------------------------------
    // Passo 1 — ativar
    // -----------------------------------------------------------------

    private fun registrarAtivacao(resultado: NfcOperator.ActivationResult) =
        viewModelScope.launch {
            val etiqueta = etiquetaAtual ?: return@launch
            val e = _estado.value
            val horas = e.horas.toIntOrNull() ?: prefs.atual.horasPadrao

            // Persistimos AGORA, não depois de um formulário. A etiqueta já
            // está gravando de verdade; se o app morrer antes de salvar, ela
            // ficaria rodando sem nada apontando para ela.
            val remessaId = repo.abrirRemessaComEtiqueta(
                etiqueta = etiqueta,
                perfil = e.perfil,
                horasPrevistas = horas,
                epochInicioEtiqueta = resultado.tagStartEpoch,
                inicioDispositivoMillis = resultado.deviceStartAtMillis,
                baseDeTempo = resultado.plan.timeBase.name,
                intervaloSegundos = resultado.plan.intervalSeconds,
                quantidadePlanejada = resultado.plan.loggingCount,
                modoArmazenamento = resultado.plan.storageMode.sdkMode,
                confirmada = resultado.verified,
                tensaoV = resultado.voltageAtStartV,
            )
            val remessa = repo.buscarRemessa(remessaId)?.remessa

            // Nao navegamos para lugar nenhum. O passo seguinte — bipar a nota
            // — acontece aqui mesmo, e mandar o operador para uma tela de
            // detalhe no meio disso e o que fazia ele largar a nota para
            // depois (e esquecer).
            _estado.update {
                it.copy(
                    bipada = null,
                    emAberto = RemessaEmAberto(
                        id = remessaId,
                        codigo = remessa?.codigo ?: "",
                        documento = null,
                        etiquetaSerial = etiqueta.serial,
                    ),
                    aviso = if (resultado.verified) null
                    else "A etiqueta aceitou o START mas não confirmou o estado. " +
                        "Bipe de novo para conferir antes de despachar.",
                )
            }
        }

    // -----------------------------------------------------------------
    // Passo 2 — a nota fiscal
    // -----------------------------------------------------------------

    fun anexarDocumento(doc: DocumentoFiscal, remessaId: String) = viewModelScope.launch {
        when (val r = repo.anexarDocumento(remessaId, doc)) {
            is Repositorio.ResultadoDocumento.Ok -> _estado.update {
                it.copy(
                    // A etapa fiscal terminou. Fechamos a folha e armamos o
                    // leitor para a próxima aproximação, que será a entrega.
                    bipada = if (it.emAberto?.id == remessaId) null else it.bipada,
                    emAberto = it.emAberto?.let { alvo ->
                        if (alvo.id == remessaId) alvo.copy(documento = r.documento.rotuloCurto) else alvo
                    },
                    aviso = "Nota vinculada à remessa.",
                )
            }
            is Repositorio.ResultadoDocumento.JaUsada -> _estado.update {
                it.copy(
                    aviso = "Esta nota já está na remessa ${r.codigoRemessa}. " +
                        "Confira se não é a etiqueta de outra carga.",
                )
            }
            is Repositorio.ResultadoDocumento.RemessaInexistente -> _estado.update {
                it.copy(aviso = r.motivo)
            }
        }
    }

    // -----------------------------------------------------------------
    // Passo 3 — encerrar
    // -----------------------------------------------------------------

    private fun encerrarComLeitura(leitura: NfcOperator.RawRead) = viewModelScope.launch {
        val volumeId = volumeParaFinalizar
        if (volumeId == null) {
            _estado.update {
                it.copy(
                    bipada = it.bipada?.copy(
                        ocupada = false,
                        mensagem = "Não encontrei o volume desta etiqueta. Abra a remessa " +
                            "e faça a leitura final por lá.",
                    )
                )
            }
            return@launch
        }

        fluxo.registrar(volumeId, TipoLeitura.FINAL, leitura)
            .onSuccess { r ->
                _estado.update {
                    it.copy(
                        bipada = null,
                        // Só limpa o lembrete da nota se for desta remessa: o
                        // operador pode ter ligado uma carga, deixado a nota
                        // para depois e encerrado outra entrega no meio.
                        emAberto = it.emAberto?.takeIf { aberta ->
                            aberta.id != r.previa.remessa.id
                        },
                        resultado = ResultadoEntrega(
                            remessaId = r.previa.remessa.id,
                            codigoRemessa = r.previa.remessa.codigo,
                            veredito = r.veredito,
                            quantidadeMedicoes = r.previa.resumo.quantidadeMedicoes,
                            minimaC = r.previa.resumo.minimaC,
                            maximaC = r.previa.resumo.maximaC,
                            mktC = r.previa.resumo.mktC,
                            tempoForaFaixaSegundos = r.previa.resumo.tempoForaFaixaSegundos,
                            ocorrenciasNovas = r.ocorrenciasNovas,
                            etiquetaLiberada = leitura.loggerParado,
                        ),
                    )
                }
            }
            .onFailure { erro ->
                _estado.update {
                    it.copy(
                        bipada = it.bipada?.copy(
                            ocupada = false,
                            mensagem = erro.message
                                ?: "Não foi possível interpretar o histórico.",
                        )
                    )
                }
            }
    }

    // -----------------------------------------------------------------

    fun agir(acao: AcaoEtiqueta) {
        when (acao) {
            AcaoEtiqueta.Fechar -> _estado.update { it.copy(bipada = null) }

            AcaoEtiqueta.Ativar -> ativar()

            // Baixa o histórico E desliga a etiqueta, nessa ordem, na mesma
            // aproximação — é o que fecha o ciclo START/STOP do fabricante e
            // libera a etiqueta para o próximo uso.
            //
            // Encadear duas chamadas do SDK numa aproximação já é o normal
            // aqui: identificar faz três (dados, LED, status) e ativar faz
            // três (dados, START, confirmação). O risco real seria parar
            // antes de baixar, e é justamente o que `Encerrar` não faz: se o
            // download falhar, o STOP nem chega a ser enviado. Se o STOP é
            // que falhar, o histórico já está salvo e o cartão de resultado
            // manda usar "Parar registro".
            AcaoEtiqueta.Finalizar -> _estado.update {
                if (it.bipada?.documentoVinculado != true) {
                    return@update it.copy(
                        bipada = it.bipada?.copy(
                            mensagem = "Bipe a nota fiscal antes de finalizar a entrega."
                        )
                    )
                }
                it.copy(
                    bipada = it.bipada.copy(
                        ocupada = true,
                        mensagem = "Baixando o histórico e desligando a etiqueta. " +
                            "Mantenha encostado…",
                    ),
                    operacaoPendente = NfcOperator.Operation.Encerrar(),
                )
            }

            AcaoEtiqueta.Parar -> _estado.update {
                it.copy(
                    bipada = it.bipada?.copy(
                        ocupada = true,
                        mensagem = "Aproxime a etiqueta para parar…",
                    ),
                    operacaoPendente = NfcOperator.Operation.StopLogging(),
                )
            }

            AcaoEtiqueta.AbrirRemessa -> viewModelScope.launch {
                val etiqueta = etiquetaAtual ?: return@launch
                val remessa = repo.remessaAtivaDaEtiqueta(etiqueta.id) ?: return@launch
                _estado.update { it.copy(bipada = null, remessaParaAbrir = remessa.id) }
            }

            // Nem todo caso e "ligar agora": as vezes o operador so quer abrir
            // a remessa e ligar a etiqueta na hora de fechar a caixa.
            AcaoEtiqueta.CriarRemessa -> viewModelScope.launch {
                val e = _estado.value
                val remessaId = repo.criarRemessa(
                    codigo = repo.proximoCodigo(),
                    remetente = "", transportadora = "", destinatario = "",
                    destinoEndereco = "", contatoRecebimento = "",
                    perfil = e.perfil, descricaoCarga = "",
                    intervaloSegundos = prefs.atual.intervaloSegundos,
                    previsaoColeta = java.time.Instant.now(),
                    previsaoEntrega = java.time.Instant.now()
                        .plusSeconds((e.horas.toIntOrNull() ?: 72) * 3600L),
                    totalVolumes = 1, volumesMonitorados = 1,
                    documento = null,
                )
                _estado.update { it.copy(bipada = null, remessaParaAbrir = remessaId) }
            }
        }
    }

    /**
     * Um toque: cadastra a etiqueta se for nova e manda o START.
     *
     * O cadastro deixou de ser um passo separado. Ele existia para amarrar o
     * código impresso ao UID, e continua fazendo isso — só que sozinho, com o
     * código saindo do UID quando o operador não informa nenhum. Exigir que
     * ele digitasse antes de ligar era pedir para largar a caixa aberta e
     * procurar a etiqueta certa no meio de trinta.
     */
    private fun ativar() = viewModelScope.launch {
        val e = _estado.value
        val bipada = e.bipada ?: return@launch
        val horas = e.horas.toIntOrNull()?.takeIf { it > 0 } ?: prefs.atual.horasPadrao

        val etiqueta = etiquetaAtual ?: cadastrarAutomaticamente(bipada) ?: return@launch
        etiquetaAtual = etiqueta

        val ajustes = prefs.atual
        val plano = ActivationPlan.forShipment(
            plannedDurationHours = horas.toDouble(),
            intervalSeconds = ajustes.intervaloSegundos,
            minC = e.perfil.minC,
            maxC = e.perfil.maxC,
            safetyFactor = ajustes.fatorSeguranca,
        ).getOrNull()

        if (plano == null) {
            _estado.update {
                it.copy(
                    bipada = it.bipada?.copy(
                        mensagem = "Duração longa demais para o intervalo de " +
                            "${ajustes.intervaloSegundos / 60} min. Aumente o intervalo " +
                            "em Ajustes, reduza as horas ou use duas etiquetas."
                    )
                )
            }
            return@launch
        }

        // A barreira de identidade: daqui em diante só a etiqueta que acabou
        // de ser lida pode receber gravação.
        _estado.update {
            it.copy(
                bipada = it.bipada?.copy(
                    serial = etiqueta.serial,
                    ocupada = true,
                    mensagem = "Mantenha a etiqueta encostada…",
                ),
                operacaoPendente = NfcOperator.Operation.Activate(plano),
            )
        }
    }

    /** Cadastro sem formulário. O código sai do UID quando não é informado. */
    private suspend fun cadastrarAutomaticamente(bipada: EtiquetaBipada): EtiquetaEntity? {
        val uid = TagIdentity.canonical(bipada.uid)
        val serial = _estado.value.serialDigitado.trim().ifBlank { "TT-" + uid.takeLast(8) }

        if (repo.etiquetaPorQr(serial) != null) {
            _estado.update {
                it.copy(
                    bipada = it.bipada?.copy(
                        mensagem = "Já existe uma etiqueta com o código $serial. " +
                            "Abra Alterar e informe outro."
                    )
                )
            }
            return null
        }

        val nova = EtiquetaEntity(
            id = UUID.randomUUID().toString(),
            serial = serial,
            qrPayload = serial,
            uidNfc = uid,
            uhfEpc = null, uhfTid = null, loteId = null,
            certificadoCalibracao = null, calibracaoValidaAteMillis = null,
            ciclosAtivacao = 0, ultimaTensaoV = bipada.tensaoV,
            aposentadaEmMillis = null,
        )
        repo.cadastrarEtiqueta(nova)
        return nova
    }

    // -----------------------------------------------------------------
    // Apagar remessa — limpeza de teste de bancada
    // -----------------------------------------------------------------

    fun pedirParaApagar(remessa: RemessaEntity) =
        _estado.update { it.copy(remessaParaApagar = remessa) }

    fun cancelarApagar() = _estado.update { it.copy(remessaParaApagar = null) }

    fun confirmarApagar() = viewModelScope.launch {
        val alvo = _estado.value.remessaParaApagar ?: return@launch
        repo.apagarRemessa(alvo.id)
        _estado.update {
            it.copy(
                remessaParaApagar = null,
                emAberto = it.emAberto?.takeIf { aberta -> aberta.id != alvo.id },
                // Apagar no app nao para a etiqueta: isso e fisico.
                aviso = "Remessa ${alvo.codigo} apagada. Se a etiqueta dela ainda " +
                    "estiver gravando, encoste e use Parar registro para liberar.",
            )
        }
    }

    fun limparAviso() = _estado.update { it.copy(aviso = null) }

    /** UID que a próxima operação deve encontrar. Nada é gravado fora dele. */
    fun uidEsperado(): String? = _estado.value.bipada?.uid
}
