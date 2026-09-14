package com.thermotrace.app.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcA
import android.nfc.tech.NfcV
import android.os.Bundle
import android.util.Log
import com.fmsh.nfcinstruct.GeneralNFC
import com.fmsh.nfcinstruct.callback.OnResultCallback
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

/**
 * Camada de operação NFC do ThermoTrace.
 *
 * Substitui `NfcTagService` da v0.1 e corrige os defeitos D1, D5, D11, D12 e D13.
 *
 * Três decisões que valem mais que o código em si:
 *
 * 1. **NfcA e NfcV.** `enableReaderMode` habilita as duas pilhas, como faz o
 *    app do fabricante em `BaseActivity.enableReaderMode`. A etiqueta física
 *    responde como NFC Forum Type 2 (ISO 14443-A); habilitar só NfcV fazia o
 *    reader mode nunca capturá-la, e o Android caía no leitor de tag do
 *    sistema. `NFCUtils.parseTag` escolhe a primeira tech da lista, então na
 *    prática o caminho usado é o NfcA.
 *
 *    Consequência para o iPhone: o Core NFC público só envia comandos
 *    proprietários por `NFCISO15693Tag.customCommand`. Se esta etiqueta for
 *    NfcA-only, a paridade com iOS depende de confirmar com o fornecedor se
 *    o mesmo modelo também responde a ISO 15693. Está nas perguntas em aberto.
 *
 * 2. **Uma única thread para todo o SDK.** `NFCUtils` guarda `chipType`,
 *    `standard`, `offset`, `detA`, `detB` em campos `static`, e
 *    `InstructMap.parameterArr` também. Duas operações concorrentes aplicam a
 *    calibração de uma etiqueta na outra. Todo acesso passa por `worker`.
 *
 * 3. **Nada é executado numa etiqueta que não foi verificada.** Ver `expectedUid`.
 */
class NfcOperator(
    private val activity: Activity,
    private val onEvent: (NfcEvent) -> Unit,
) {

    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)
    @Volatile private var closed = false
    private val readerGeneration = AtomicLong()

    /**
     * UID que DEVE estar na etiqueta encostada. Definido a partir do QR lido.
     * `null` só é aceito em modo de descoberta (vinculação inicial ou diagnóstico).
     *
     * Este campo é a correção do defeito D1: na v0.1 qualquer etiqueta que
     * entrasse no campo era ativada.
     */
    @Volatile var expectedUid: String? = null

    private data class PendingOperation(val operation: Operation, val uid: String?)
    private val pending = AtomicReference<PendingOperation?>(null)

    /** Laco do termometro ao vivo. Escrito pela UI, lido pela thread `worker`. */
    @Volatile private var aoVivo: Boolean = false

    /**
     * A etiqueta da ultima aproximacao.
     *
     * Continua valida enquanto ela estiver no campo, e e o que permite
     * executar a operacao no mesmo encostar em que a folha foi aberta.
     */
    @Volatile private var ultimaTag: Tag? = null

    val isAvailable: Boolean get() = adapter != null
    val isEnabled: Boolean get() = adapter?.isEnabled == true

    sealed interface Operation {
        object Idle : Operation
        /** Lê somente o estado RTC. UID e tecnologia vêm do próprio Android. */
        object Identify : Operation
        data class Activate(val plan: ActivationPlan) : Operation
        data class Download(val purpose: String) : Operation

        /**
         * Encerramento da entrega: baixa o historico e desliga a etiqueta,
         * nessa ordem, numa aproximacao so.
         *
         * A ordem nao e detalhe. Parar antes de baixar deixaria a etiqueta
         * livre e o laudo vazio. Aqui, se o download falhar, o STOP nem
         * chega a ser enviado.
         *
         * Fecha o ciclo do jeito que o app do fabricante faz: START na
         * origem, STOP na entrega, etiqueta livre para o proximo uso. Sem o
         * STOP ela segue gravando na prateleira ate a memoria encher, e
         * recusa o proximo START com "IN RTC Flow Status".
         */
        data class Encerrar(val senha: String = SENHA_STOP_PADRAO) : Operation

        /**
         * Verificação rápida: lê só o bit de status e a tensão.
         *
         * Existe separada do Download porque é barata — uma aproximação
         * curta, sem baixar milhares de pontos. É o que permite perguntar
         * "a etiqueta ainda está gravando?" numa doca, com a caixa fechada,
         * sem gastar a sessão inteira nem a bateria da etiqueta.
         */
        data object VerifyStatus : Operation

        /**
         * Para o registro autônomo (STOP do RTC).
         *
         * Libera uma etiqueta presa em ciclo anterior. A senha padrão de
         * fábrica é 8 zeros — ou seja, de fábrica **qualquer pessoa com um
         * app FMSH genérico pode parar a etiqueta em trânsito**. Está na
         * lista de perguntas ao fornecedor.
         */
        data class StopLogging(val senha: String = SENHA_STOP_PADRAO) : Operation

        /**
         * Levantamento completo do que a etiqueta e, sem escrever nada.
         *
         * Responde em campo tres perguntas que hoje so o logcat responde:
         * qual interface a etiqueta expoe (e portanto se o iPhone consegue
         * falar com ela), se o chip esta acordado, e o que ele mede agora.
         */
        data object Diagnosticar : Operation

        /**
         * Le o cabecalho da sessao gravada NA etiqueta — nao o que o app
         * acha que gravou.
         *
         * E a unica forma de conferir a configuracao real: faixa, intervalo,
         * delay, quantidade programada e quantas amostras ja existem. Quando
         * app e etiqueta divergem, e aqui que aparece.
         */
        data object LerConfiguracao : Operation

        /**
         * Termometro continuo enquanto a etiqueta ficar no campo.
         *
         * Serve para a conferencia que todo cliente pede antes de confiar no
         * sistema: por a etiqueta ao lado de um termometro aferido e ver os
         * dois numeros juntos. So le; o registro autonomo nao e tocado.
         */
        data object TemperaturaAoVivo : Operation
    }

    sealed interface NfcEvent {
        data class TagSeen(val uid: String) : NfcEvent
        /** UID diferente do esperado — a operação foi RECUSADA, nada foi escrito. */
        data class WrongTag(val expected: String, val found: String) : NfcEvent
        data class Identified(val snapshot: TagSnapshot) : NfcEvent
        data class Activated(val result: ActivationResult) : NfcEvent
        data class StatusVerificado(
            val uid: String,
            val registrando: Boolean,
            val voltageV: Double?,
            val temperatureC: Double?,
        ) : NfcEvent
        data class Downloaded(val read: RawRead) : NfcEvent
        data class LoggingParado(val uid: String, val ok: Boolean) : NfcEvent
        data class Failed(val stage: String, val message: String) : NfcEvent

        data class Diagnosticado(val laudo: DiagnosticoEtiqueta) : NfcEvent
        data class ConfiguracaoLida(val cabecalho: SessionHeader, val bruto: List<String>) : NfcEvent
        data class MedidaAoVivo(
            val amostra: Int,
            val temperaturaC: Double?,
            val voltageV: Double?,
            val campo: String?,
        ) : NfcEvent
        data object AoVivoEncerrado : NfcEvent
    }

    /**
     * Retrato tecnico da etiqueta encostada.
     *
     * `suportaIso15693` e o campo que mais importa comercialmente: o Core NFC
     * publico do iPhone so envia comando proprietario por ISO 15693. Se as
     * etiquetas compradas forem 14443-A puro, o app de iPhone nao existe — e
     * e melhor descobrir isso na bancada do que depois de vender.
     */
    data class DiagnosticoEtiqueta(
        val uid: String,
        val tecnologias: List<String>,
        val rotuloInterface: String?,
        val suportaIso15693: Boolean,
        val suportaIso14443a: Boolean,
        val versaoSdk: String,
        val acordada: Boolean?,
        val registrando: Boolean,
        val campo: String?,
        val temperaturaC: Double?,
        val voltageV: Double?,
    )

    data class TagSnapshot(
        val uid: String,
        val temperatureC: Double?,
        val voltageV: Double?,
        val fieldStrength: String?,
        /** Bit de fluxo RTC lido AGORA. E o que separa "ativa" de "presumida". */
        val registrando: Boolean,
        /**
         * Interface fisica da etiqueta, como o app do fabricante exibe:
         * "Type-A  14443" ou "Type-V  15693" (`IMFragment`, linhas 177-186).
         *
         * Nao e curiosidade tecnica: o Core NFC publico do iPhone so envia
         * comando proprietario por ISO 15693. Se estas etiquetas forem
         * 14443-A puro, a v2 iOS precisa ser repensada — e a tela responde
         * isso sem ninguem precisar de logcat.
         */
        val tecnologia: String?,
    )

    data class ActivationResult(
        val uid: String,
        val tagStartEpoch: Long,
        val deviceStartAtMillis: Long,
        val plan: ActivationPlan,
        val verified: Boolean,
        val voltageAtStartV: Double?,
        val respostaStart: List<String> = emptyList(),
    )

    /** Resposta crua do SDK. Nunca descartar: é a evidência (princípio P2 do banco). */
    data class RawRead(
        val uid: String,
        val purpose: String,
        val response: List<String>,
        val deviceReadAtMillis: Long,
        val voltageV: Double?,
        val instantTempC: Double?,
        /**
         * Resultado do STOP, quando a operacao tambem desligou a etiqueta.
         *
         * `null` significa "nem tentou" — checkpoint nao para nada.
         *
         * Vem no mesmo evento do download de proposito. Como dois eventos
         * separados, a tela teria que casar os dois na ordem certa, e ordem
         * de chegada de eventos assincronos e o tipo de coisa que funciona
         * na bancada e falha no galpao.
         */
        val loggerParado: Boolean? = null,
    )

    fun start() {
        if (closed) return
        val a = adapter ?: return
        activeReader?.aoVivo = false
        activeReader = this
        val generation = readerGeneration.incrementAndGet()
        Log.i(TAG_LOG, "reader mode habilitado")
        val opcoes = Bundle().apply {
            // O app do fabricante usa 1000 ms. Sem isto o Android redispara a
            // mesma etiqueta várias vezes numa única aproximação, e a
            // sequência de gravação é interrompida no meio dela.
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 1000)
        }
        a.enableReaderMode(
            activity,
            { tag ->
                val command = pending.get()
                worker.execute { if (!closed && activeReader === this && readerGeneration.get() == generation) dispatch(tag, command) }
            },
            // Exatamente os mesmos flags do app do fabricante
            // (`BaseActivity.enableReaderMode`): NfcA | NfcV, nada mais.
            //
            // Eu tinha acrescentado SKIP_NDEF_CHECK e NO_PLATFORM_SOUNDS. Sao
            // melhorias defensaveis, mas o codigo do fabricante e a fonte de
            // verdade e ele nao usa nenhum dos dois — e funciona com estas
            // etiquetas. Onde havia divergencia sem motivo forte, voltei ao
            // original.
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_V,
            opcoes,
        )
    }

    fun stop() {
        // Sem isto, o laco do termometro continuaria rodando com a tela
        // apagada, mantendo o chip acordado e comendo a bateria da etiqueta.
        aoVivo = false
        // Com a tela pausada nao ha etiqueta encostada em que confiar: guardar
        // o Tag antigo faria a proxima operacao tentar gravar num handle morto.
        ultimaTag = null
        readerGeneration.incrementAndGet()
        if (activeReader === this) {
            activeReader = null
            adapter?.disableReaderMode(activity)
        }
    }

    fun shutdown() {
        closed = true
        pending.set(null)
        stop()
        // O SDK é singleton: o executor é compartilhado por todas as telas.
        // Fechar uma tela não encerra o executor das demais.
    }

    /**
     * Agenda uma operacao — e a executa na hora, se a etiqueta ainda estiver
     * encostada.
     *
     * **Por que isto existe.** O reader mode do Android entrega a etiqueta
     * uma vez por aproximacao. Como a tela so sabe o que oferecer depois de
     * ler a etiqueta, o operador encostava para abrir a folha, tocava em
     * Ativar, e precisava AFASTAR e ENCOSTAR de novo para a gravacao sair.
     * Dois toques fisicos por operacao, num fluxo que se vende como "um
     * bipe" — e ninguem entendia por que "nao funcionou da primeira vez".
     *
     * O `Tag` da ultima aproximacao continua valido enquanto a etiqueta
     * estiver no campo. Entao tentamos por ele imediatamente; se a etiqueta
     * ja saiu, a operacao fica pendente e sai na proxima aproximacao, que e
     * o comportamento de antes. Nao ha caso pior: no maximo, o que ja era.
     */
    fun request(op: Operation) {
        if (closed) return
        val command = PendingOperation(op, expectedUid)
        pending.set(command)
        val tag = ultimaTag ?: return
        val generation = readerGeneration.get()
        worker.execute {
            // `pending` pode ter sido consumido por uma aproximacao real que
            // chegou primeiro. Sem esta guarda, a mesma gravacao sairia duas
            // vezes — e ativar duas vezes cria duas sessoes para a mesma
            // etiqueta.
            if (!closed && activeReader === this && readerGeneration.get() == generation && pending.get() === command && presente(tag)) dispatch(tag, command)
        }
    }

    /**
     * Arma uma operação para a próxima aproximação física.
     *
     * Diferente de [request], não reutiliza o `Tag` que ainda pode estar no
     * campo. A Home usa isto para voltar ao estado "encoste uma etiqueta"
     * depois de fechar uma folha, ativar ou finalizar. Sem limpar o handle
     * anterior, a mesma etiqueta era identificada outra vez imediatamente e
     * a folha reabria antes de o operador conseguir bipar a nota fiscal.
     */
    fun awaitNextTag(op: Operation) {
        ultimaTag = null
        if (!closed) pending.set(PendingOperation(op, expectedUid))
    }

    /**
     * Esquece a operacao agendada.
     *
     * **Sem isto existe um caminho para gravar numa etiqueta errada.** O
     * operador toca em Ativar, muda de ideia e fecha a folha sem encostar a
     * etiqueta. A operacao fica pendente. A barreira de identidade
     * (o campo expectedUid) e desligada junto com a folha, porque nao ha
     * etiqueta escolhida. A proxima etiqueta que entrar no campo — outra
     * qualquer, da bancada — recebe o START.
     *
     * Fechar a folha cancela o que estava agendado.
     */
    fun cancelarPendente() {
        pending.set(null)
    }

    /**
     * A etiqueta ainda responde?
     *
     * Um connect/close e a forma barata de perguntar. Custa alguns
     * milissegundos e evita mandar uma sequencia de gravacao para o vazio.
     */
    private fun presente(tag: Tag): Boolean = runCatching {
        NfcA.get(tag)?.let { nfc ->
            if (!nfc.isConnected) nfc.connect()
            nfc.close()
            return true
        }
        NfcV.get(tag)?.let { nfc ->
            if (!nfc.isConnected) nfc.connect()
            nfc.close()
            return true
        }
        false
    }.getOrDefault(false)

    // -----------------------------------------------------------------
    // Execução — sempre na thread `worker`
    // -----------------------------------------------------------------

    private fun dispatch(tag: Tag, command: PendingOperation?) {
        if (closed) return
        ultimaTag = tag
        // Um callback antigo nunca consome uma operação armada depois dele.
        if (command == null || !pending.compareAndSet(command, null)) return
        val op = command.operation
        try {
            val sdk = GeneralNFC.getInstance()
            sdk.setTag(tag)
            val uid = sdk.uid?.uppercase().orEmpty()
            require(uid.isNotBlank()) { "A etiqueta não devolveu uma identificação válida." }
            Log.i(TAG_LOG, "ação=${op.javaClass.simpleName} techs=${tag.techList.joinToString()}")
            emit(NfcEvent.TagSeen(uid))
            val expected = command.uid
            if (expected != null && !TagIdentity.matches(expected, uid)) {
                emit(NfcEvent.WrongTag(expected, uid))
                return
            }
            when (op) {
                is Operation.Idle -> Unit
                is Operation.Identify -> identify(sdk, uid, tag)
                is Operation.Activate -> activate(sdk, uid, op.plan)
                is Operation.Download -> download(sdk, uid, op.purpose)
                is Operation.Encerrar -> encerrar(sdk, uid, op.senha)
                is Operation.VerifyStatus -> verificarStatus(sdk, uid)
                is Operation.StopLogging -> pararLogger(sdk, uid, op.senha)
                is Operation.Diagnosticar -> diagnosticar(sdk, uid, tag)
                is Operation.LerConfiguracao -> lerConfiguracao(sdk)
                is Operation.TemperaturaAoVivo -> temperaturaAoVivo(sdk)
            }
        } catch (t: Exception) {
            // Descoberta é somente leitura. Repetir requer nova aproximação;
            // START/STOP nunca são repetidos automaticamente após resultado incerto.
            if (op == Operation.Identify && !closed) pending.compareAndSet(null, command)
            emit(NfcEvent.Failed("execucao", t.message ?: t::class.java.simpleName))
        } finally {
            // O SDK conecta e nunca fecha. Sem isto a próxima aproximação falha
            // intermitentemente até o timeout do driver.
            // Fecha a interface que estiver aberta. Antes so fechava NfcV, e
            // como a etiqueta e NfcA a conexao ficava presa ate o timeout
            // do driver — o que fazia a segunda aproximacao falhar.
            runCatching { NfcA.get(tag)?.takeIf { it.isConnected }?.close() }
            runCatching { NfcV.get(tag)?.takeIf { it.isConnected }?.close() }
        }
    }

    /** Mesmo rotulo do app do fabricante. */
    private fun rotuloTecnologia(tag: Tag): String? = when {
        tag.techList.any { it.endsWith("NfcA") } -> "Type-A  14443"
        tag.techList.any { it.endsWith("NfcV") } -> "Type-V  15693"
        else -> tag.techList.lastOrNull()?.substringAfterLast(".")
    }

    private fun identify(sdk: GeneralNFC, uid: String, tag: Tag) {
        // Assim como no app original, uma aproximacao executa uma unica API do
        // SDK. UID e tecnologia ja foram entregues pelo Android; aqui so falta
        // consultar se o RTC esta gravando. Ler bateria e piscar LED antes
        // tornava este simples reconhecimento uma sequencia de quatro comandos.
        val registrando = sdk.statusBlocking().confirmedLogging()

        emit(
            NfcEvent.Identified(
                TagSnapshot(
                    uid = uid,
                    temperatureC = null,
                    voltageV = null,
                    fieldStrength = null,
                    registrando = registrando,
                    tecnologia = rotuloTecnologia(tag),
                )
            )
        )
    }

    /** START isolado, exatamente como a ação equivalente do app original. */
    private fun activate(sdk: GeneralNFC, uid: String, plan: ActivationPlan) {
        // Instante em que o SDK grava o relógio DO CELULAR dentro da etiqueta
        // (InstructMap case 22). É a origem de toda a base de tempo — precisa
        // ser capturado aqui e enviado ao servidor junto com o skew (D2).
        val deviceStartAtMillis = System.currentTimeMillis()

        // START, como no app do fabricante.
        //
        // Eu encadeava `initUHF()` antes, herdado de uma nota da v0.1. O app
        // do fabricante NAO faz isso: cada aproximacao envia um comando so
        // (`IMFragment.sendInstruct`), e "Init Regfile" e um item de menu a
        // parte, de manutencao. O proprio `startLogging` do SDK ja acorda o
        // chip e confere o estado antes de gravar.
        //
        // Inicializar registradores antes de todo START e escrever na
        // etiqueta mais do que o proprio fabricante escreve.
        val started = sdk.startLoggingBlocking(plan)
        if (!started.ok) {
            emit(NfcEvent.Failed("start", started.explain(plan)))
            return
        }

        emit(
            NfcEvent.Activated(
                ActivationResult(
                    uid = uid,
                    tagStartEpoch = deviceStartAtMillis / 1000L,
                    deviceStartAtMillis = deviceStartAtMillis,
                    plan = plan,
                    // startLogging do SDK original ja faz a verificacao interna
                    // dos registradores antes de devolver sucesso.
                    verified = true,
                    voltageAtStartV = null,
                    respostaStart = started.raw,
                )
            )
        )
    }

    /** Feature "etiqueta ativa": consulta o bit de fluxo RTC do chip. */
    private fun verificarStatus(sdk: GeneralNFC, uid: String) {
        val registrando = sdk.statusBlocking().confirmedLogging()
        emit(
            NfcEvent.StatusVerificado(
                uid = uid,
                registrando = registrando,
                voltageV = null,
                temperatureC = null,
            )
        )
    }

    /**
     * Baixa o historico e, so depois de garanti-lo, desliga a etiqueta.
     *
     * O `stopLogging` e o mesmo comando do app do fabricante
     * (`IMFragment.mStopRTC`), com a senha de fabrica de oito zeros.
     */
    private fun encerrar(sdk: GeneralNFC, uid: String, senha: String) {
        val response = sdk.loggingResultBlocking()
        if (response == null) {
            emit(
                NfcEvent.Failed(
                    "historico",
                    "Não foi possível recuperar o histórico. A etiqueta NÃO foi " +
                        "desligada — encoste de novo para tentar.",
                )
            )
            return
        }

        // Só agora. Se o STOP viesse antes, uma falha de leitura deixaria a
        // etiqueta livre e a viagem sem laudo.
        val parou = sdk.stopLoggingBlocking(senha)

        emit(
            NfcEvent.Downloaded(
                RawRead(
                    uid = uid,
                    purpose = "destino",
                    response = response,
                    deviceReadAtMillis = System.currentTimeMillis(),
                    voltageV = null,
                    instantTempC = null,
                    loggerParado = parou,
                )
            )
        )
        dormir(sdk)
    }

    private fun download(sdk: GeneralNFC, uid: String, purpose: String) {
        val response = sdk.loggingResultBlocking()
            ?: run { emit(NfcEvent.Failed("historico", "Não foi possível recuperar o histórico.")); return }

        emit(
            NfcEvent.Downloaded(
                RawRead(
                    uid = uid,
                    purpose = purpose,
                    response = response,
                    deviceReadAtMillis = System.currentTimeMillis(),
                    voltageV = null,
                    instantTempC = null,
                )
            )
        )
    }

    /**
     * Devolve o chip ao modo de baixo consumo depois de uma leitura.
     *
     * A bateria e de 5 mAh nao recarregavel e precisa durar 30 dias. O app
     * do fabricante expoe `doSleep` justamente por isso. O RTC continua
     * gravando em PD — dormir nao interrompe o monitoramento.
     */
    private fun dormir(sdk: GeneralNFC) {
        runCatching { sdk.sleepBlocking() }
    }

    // -----------------------------------------------------------------
    // Diagnostico, configuracao gravada e termometro ao vivo
    // -----------------------------------------------------------------

    /**
     * So le. Nenhum comando de escrita e enviado — este caminho tem que ser
     * seguro de rodar numa etiqueta que esta monitorando uma carga real.
     */
    private fun diagnosticar(sdk: GeneralNFC, uid: String, tag: Tag) {
        val techs = tag.techList.map { it.substringAfterLast('.') }
        val acordada = sdk.acordadaBlocking()
        val basic = sdk.basicDataBlocking()
        val registrando = sdk.statusBlocking().confirmedLogging()

        emit(
            NfcEvent.Diagnosticado(
                DiagnosticoEtiqueta(
                    uid = uid,
                    tecnologias = techs,
                    rotuloInterface = rotuloTecnologia(tag),
                    suportaIso15693 = techs.any { it == "NfcV" },
                    suportaIso14443a = techs.any { it == "NfcA" },
                    versaoSdk = versaoSdk(),
                    acordada = acordada,
                    registrando = registrando,
                    campo = basic?.getOrNull(0),
                    temperaturaC = basic?.getOrNull(1)?.toDoubleOrNull(),
                    voltageV = basic?.getOrNull(2)?.toDoubleOrNull(),
                )
            )
        )
    }

    /**
     * Baixa a sessao so para ler o cabecalho.
     *
     * Sim, isso traz a serie inteira junto — o SDK nao expoe o cabecalho
     * separado. E caro em bateria, e por isso e uma acao explicita do
     * operador, nunca automatica.
     */
    private fun lerConfiguracao(sdk: GeneralNFC) {
        val resposta = sdk.loggingResultBlocking()
        if (resposta == null || resposta.size < 12) {
            emit(
                NfcEvent.Failed(
                    "configuracao",
                    "A etiqueta não devolveu a configuração. Ela pode nunca ter sido ligada.",
                )
            )
            return
        }
        val cabecalho = SessionHeader(
            statusCode = resposta[0],
            startEpoch = resposta[1].toLongOrNull() ?: 0L,
            plannedCount = resposta[2].toIntOrNull() ?: 0,
            measuredCount = resposta[3].toIntOrNull() ?: 0,
            delayMinutes = resposta[4].toIntOrNull() ?: 0,
            intervalSeconds = resposta[5].toIntOrNull() ?: 0,
            recordedMinC = resposta[6].toDoubleOrNull() ?: Double.NaN,
            recordedMaxC = resposta[7].toDoubleOrNull() ?: Double.NaN,
            limitMinC = resposta[8].toDoubleOrNull() ?: Double.NaN,
            limitMaxC = resposta[9].toDoubleOrNull() ?: Double.NaN,
            belowCount = resposta[10].toIntOrNull() ?: 0,
            aboveCount = resposta[11].toIntOrNull() ?: 0,
        )
        emit(NfcEvent.ConfiguracaoLida(cabecalho, resposta))
        dormir(sdk)
    }

    /**
     * Le temperatura e tensao em laco enquanto a etiqueta estiver no campo.
     *
     * Termina de tres formas: o operador para, a etiqueta sai do campo (as
     * leituras passam a falhar) ou o teto de amostras e atingido. O teto
     * existe para o laco nao consumir a bateria da etiqueta se alguem deixar
     * o celular em cima dela e for almocar.
     */
    private fun temperaturaAoVivo(sdk: GeneralNFC) {
        aoVivo = true
        var falhasSeguidas = 0
        var amostra = 0
        while (aoVivo && falhasSeguidas < FALHAS_ATE_DESISTIR && amostra < MAX_AMOSTRAS_AO_VIVO) {
            val basic = sdk.basicDataBlocking()
            if (basic == null) {
                falhasSeguidas++
                Thread.sleep(250)
                continue
            }
            falhasSeguidas = 0
            emit(
                NfcEvent.MedidaAoVivo(
                    amostra = amostra++,
                    temperaturaC = basic.getOrNull(1)?.toDoubleOrNull(),
                    voltageV = basic.getOrNull(2)?.toDoubleOrNull(),
                    campo = basic.getOrNull(0),
                )
            )
            Thread.sleep(INTERVALO_AO_VIVO_MS)
        }
        aoVivo = false
        dormir(sdk)
        emit(NfcEvent.AoVivoEncerrado)
    }

    /** Encerra o termometro ao vivo. Pode ser chamado de qualquer thread. */
    fun encerrarAoVivo() {
        aoVivo = false
    }

    /** Versao do SDK do fabricante, para o registro de auditoria. */
    fun versaoSdk(): String = versaoSdkFmsh()

    /** Para o registro autonomo. Libera a etiqueta para um novo ciclo. */
    private fun pararLogger(sdk: GeneralNFC, uid: String, senha: String) {
        val ok = sdk.stopLoggingBlocking(senha)
        emit(NfcEvent.LoggingParado(uid, ok))
    }

    private fun emit(e: NfcEvent) = activity.runOnUiThread { if (!closed) onEvent(e) }

    companion object {
        const val TAG_LOG = "ThermoTraceNFC"
        @Volatile private var activeReader: NfcOperator? = null
        private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "thermotrace-nfc") }

        /**
         * Senha de fabrica do STOP, conforme o app do fabricante
         * (`IMFragment.mStopRTC = "00000000"`).
         *
         * Oito zeros: de fabrica, parar o logger nao exige segredo nenhum.
         * Enquanto o fornecedor nao confirmar se `settingPassword` protege o
         * STOP, o laudo precisa registrar que a etiqueta e interrompivel por
         * terceiros.
         */
        const val SENHA_STOP_PADRAO = "00000000"

        /** Pausa entre leituras do termometro ao vivo. */
        private const val INTERVALO_AO_VIVO_MS = 600L

        /** Leituras falhas seguidas que significam "a etiqueta saiu do campo". */
        private const val FALHAS_ATE_DESISTIR = 3

        /** Teto de amostras: ~10 min de termometro. Depois disso e desperdicio. */
        private const val MAX_AMOSTRAS_AO_VIVO = 600
    }
}

// ---------------------------------------------------------------------
// Adaptação do SDK (callbacks) para chamadas bloqueantes.
//
// `NFCUtils.parseTag` já é síncrono — invoca o callback na thread chamadora.
// Mas a API é de callback, o que espalha aninhamento por todo lado. Estas
// extensões achatam isso. Como já estamos na thread `worker`, bloquear é
// correto e desejado: a operação inteira tem que acontecer numa aproximação só.
// ---------------------------------------------------------------------


internal data class StartOutcome(val ok: Boolean, val detail: String?, val raw: List<String> = emptyList()) {
    /**
     * O SDK devolve `onResult(false, "0")` quando a contagem excede a capacidade
     * do modo de armazenamento. A v0.1 mostrava só "a etiqueta recusou o início",
     * o que é indepurável em campo.
     */
    fun explain(plan: ActivationPlan): String = when {
        detail == "0" ->
            "A etiqueta não comporta ${plan.loggingCount} registros no modo atual " +
                "dela. Reduza a duração prevista ou aumente o intervalo."
        detail == "IN RTC Flow Status" ->
            "Esta etiqueta já está monitorando. Encerre a sessão anterior antes de reativar."
        detail == "Wakeup fail" ->
            "A etiqueta não acordou. Reaproxime o telefone e mantenha parado."
        else -> detail ?: "A etiqueta recusou o início do logger."
    }
}

private fun <T> await(block: (OnResultCallback) -> Unit, map: (Boolean, Array<out String>) -> T?): T? {
    val box = ArrayBlockingQueue<Optional<T>>(1)
    block(object : OnResultCallback {
        override fun onResult(status: Boolean, vararg response: String) {
            box.offer(Optional(map(status, response)))
        }
        override fun onFailed(errorMsg: String?) {
            box.offer(Optional(null))
        }
    })
    // O SDK é síncrono; o timeout é só uma rede de segurança contra
    // um transceive travado no driver NFC.
    return box.poll(8, TimeUnit.SECONDS)?.value
}

private class Optional<T>(val value: T?)

internal fun GeneralNFC.basicDataBlocking(): List<String>? =
    await({ cb -> getBasicData(cb) }, { ok, r -> if (ok) r.toList() else null })

internal fun GeneralNFC.initBlocking(): Boolean =
    await({ cb -> initUHF(cb) }, { ok, _ -> ok }) == true

internal fun GeneralNFC.startLoggingBlocking(plan: ActivationPlan): StartOutcome =
    await(
        { cb ->
            startLogging(
                plan.delayMinutes,
                plan.intervalSeconds,
                plan.loggingCount,
                plan.minC,
                plan.maxC,
                plan.storageMode.sdkMode,
                cb,
            )
        },
        { ok, r -> StartOutcome(ok, r.firstOrNull(), r.toList()) },
    ) ?: StartOutcome(false, null)

internal fun GeneralNFC.statusBlocking(): TagRtcStatus =
    await({ cb -> checkStatus(cb) }, { ok, _ ->
        if (ok) TagRtcStatus.LOGGING else TagRtcStatus.IDLE
    }) ?: TagRtcStatus.UNKNOWN

internal fun GeneralNFC.loggingResultBlocking(): List<String>? =
    await({ cb -> getLoggingResult(true, cb) }, { ok, r -> if (ok) r.toList() else null })

internal fun GeneralNFC.stopLoggingBlocking(senha: String): Boolean =
    await({ cb -> stopLogging(senha, cb) }, { ok, _ -> ok }) == true

/**
 * `checkWakeUp` devolve `true` quando o chip esta acordado e `false` quando
 * dorme — os dois sao respostas validas. `null` e a terceira: a etiqueta nao
 * respondeu, e isso nao e a mesma coisa que "esta dormindo".
 */
internal fun GeneralNFC.acordadaBlocking(): Boolean? =
    await({ cb -> checkWakeUp(cb) }, { ok, _ -> ok })

/**
 * Versao do SDK do fabricante, lida do proprio SDK.
 *
 * Estava escrita a mao em `LeituraViewModel` como "1.0.0". Numa troca de
 * biblioteca, a evidencia de auditoria passaria a mentir sobre qual codigo
 * produziu a leitura — que e justamente o que a coluna existe para provar.
 */
fun versaoSdkFmsh(): String =
    "fmsh-nfcinstruct-" +
        (runCatching { GeneralNFC.getInstance().libVersion }.getOrNull() ?: "desconhecida")

internal fun GeneralNFC.sleepBlocking(): Boolean =
    await({ cb -> doSleep(cb) }, { ok, _ -> ok }) == true
