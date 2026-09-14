package com.thermotrace.app.data.repo

import com.thermotrace.app.data.alerta.AlertaEmail
import com.thermotrace.app.data.db.AcaoCorretivaDao
import com.thermotrace.app.data.db.AcaoCorretivaEntity
import com.thermotrace.app.data.db.CustodiaDao
import com.thermotrace.app.data.db.DestinatarioAlertaDao
import com.thermotrace.app.data.db.DestinatarioAlertaEntity
import com.thermotrace.app.data.db.DocumentoDao
import com.thermotrace.app.data.db.EtiquetaDao
import com.thermotrace.app.data.db.LeituraDao
import com.thermotrace.app.data.db.OcorrenciaComAcoes
import com.thermotrace.app.data.db.OcorrenciaDao
import com.thermotrace.app.data.db.OcorrenciaEntity
import com.thermotrace.app.data.db.OutboxDao
import com.thermotrace.app.data.db.OutboxEntity
import com.thermotrace.app.data.db.RemessaDao
import com.thermotrace.app.data.db.SessaoComLeituras
import com.thermotrace.app.data.db.SessaoDao
import com.thermotrace.app.data.db.VolumeDao
import com.thermotrace.app.domain.AlertaTermico
import com.thermotrace.app.domain.DestinatarioAlerta
import com.thermotrace.app.domain.EstadoEtiqueta
import com.thermotrace.app.domain.Excursao
import com.thermotrace.app.domain.GravidadeExcursao
import com.thermotrace.app.domain.PerfilTermico
import com.thermotrace.app.domain.RegrasTermicas
import com.thermotrace.app.domain.StatusOcorrencia
import com.thermotrace.app.domain.TipoAcaoCorretiva
import com.thermotrace.app.domain.TipoOcorrencia
import kotlinx.coroutines.flow.Flow
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Parte do repositório dedicada a ocorrências, alertas e estado da etiqueta.
 *
 * Separado de `Repositorio` por assunto, não por camada: aqui mora tudo que
 * responde "algo saiu do esperado, quem precisa saber e o que foi feito".
 */
class RepositorioAlertas(
    private val ocorrenciaDao: OcorrenciaDao,
    private val acaoDao: AcaoCorretivaDao,
    private val destinatarioDao: DestinatarioAlertaDao,
    private val outboxDao: OutboxDao,
    private val sessaoDao: SessaoDao,
    private val volumeDao: VolumeDao,
    private val remessaDao: RemessaDao,
    private val documentoDao: DocumentoDao,
    private val etiquetaDao: EtiquetaDao,
    private val leituraDao: LeituraDao,
) {

    // -----------------------------------------------------------------
    // 1. Ocorrências e alerta
    // -----------------------------------------------------------------

    fun observarOcorrencias(remessaId: String): Flow<List<OcorrenciaComAcoes>> =
        ocorrenciaDao.observarPorRemessa(remessaId)

    fun observarAbertas(): Flow<List<OcorrenciaComAcoes>> =
        ocorrenciaDao.observarPorStatus(
            listOf(
                StatusOcorrencia.ABERTA,
                StatusOcorrencia.ALERTA_ENVIADO,
                StatusOcorrencia.EM_TRATAMENTO,
            )
        )

    fun contarAbertas(): Flow<Int> = ocorrenciaDao.contarAbertas()

    fun observarOcorrencia(id: String) = ocorrenciaDao.observarComAcoes(id)

    suspend fun ocorrenciasDaRemessa(remessaId: String) = ocorrenciaDao.porRemessa(remessaId)

    /**
     * Converte as excursões detectadas numa leitura em ocorrências.
     *
     * A chave natural (sessão + índice da primeira amostra do segmento) é o que
     * impede a duplicação: reler a etiqueta no destino encontra as MESMAS
     * excursões que o checkpoint já tinha encontrado. Sem isso, cada leitura
     * reenviaria todos os alertas anteriores — e o destinatário desligaria a
     * notificação na segunda semana.
     *
     * @return apenas as ocorrências NOVAS, que são as que merecem alerta.
     */
    suspend fun registrarExcursoes(
        remessaId: String,
        volumeId: String,
        sessaoId: String,
        excursoes: List<Excursao>,
        detectadoEm: Instant,
    ): List<OcorrenciaEntity> {
        val novas = mutableListOf<OcorrenciaEntity>()
        val agora = System.currentTimeMillis()

        for (ex in excursoes) {
            val chave = "$sessaoId#${ex.inicio.epochSecond}#${ex.tipo.name}"
            if (ocorrenciaDao.porChaveNatural(chave) != null) continue

            val ocorrencia = OcorrenciaEntity(
                id = UUID.randomUUID().toString(),
                remessaId = remessaId,
                volumeId = volumeId,
                sessaoId = sessaoId,
                chaveNatural = chave,
                tipo = TipoOcorrencia.TERMICA,
                gravidade = ex.gravidade,
                status = StatusOcorrencia.ABERTA,
                titulo = "${ex.tipo.rotulo} · pico ${"%.1f".format(ex.picoC)} °C",
                detalhe = "Desvio de ${"%.1f".format(ex.desvioC)} °C por " +
                    RegrasTermicas.formatarDuracao(ex.duracao.seconds) +
                    ", contra o limite de ${"%.1f".format(ex.limiteC)} °C.",
                ocorridoEmMillis = ex.inicio.toEpochMilli(),
                detectadoEmMillis = detectadoEm.toEpochMilli(),
                registradoEmMillis = agora,
                picoC = ex.picoC,
                limiteC = ex.limiteC,
                duracaoSegundos = ex.duracao.seconds,
                versaoRegra = RegrasTermicas.VERSAO,
                alertaEnviadoEmMillis = null,
                alertaDestinatarios = null,
                alertaCanal = null,
                fechadaEmMillis = null,
            )
            if (ocorrenciaDao.inserir(ocorrencia) > 0) novas += ocorrencia
        }
        return novas
    }

    /** Ocorrência sem excursão: bateria, etiqueta parada, avaria, atraso. */
    suspend fun abrirOcorrencia(
        remessaId: String,
        volumeId: String?,
        sessaoId: String?,
        tipo: TipoOcorrencia,
        gravidade: GravidadeExcursao?,
        titulo: String,
        detalhe: String?,
        ocorridoEm: Instant,
    ): OcorrenciaEntity {
        val agora = System.currentTimeMillis()
        val ocorrencia = OcorrenciaEntity(
            id = UUID.randomUUID().toString(),
            remessaId = remessaId,
            volumeId = volumeId,
            sessaoId = sessaoId,
            chaveNatural = "${sessaoId ?: remessaId}#${tipo.name}#${ocorridoEm.epochSecond}",
            tipo = tipo,
            gravidade = gravidade,
            status = StatusOcorrencia.ABERTA,
            titulo = titulo,
            detalhe = detalhe,
            ocorridoEmMillis = ocorridoEm.toEpochMilli(),
            detectadoEmMillis = agora,
            registradoEmMillis = agora,
            picoC = null, limiteC = null, duracaoSegundos = null,
            versaoRegra = RegrasTermicas.VERSAO,
            alertaEnviadoEmMillis = null, alertaDestinatarios = null, alertaCanal = null,
            fechadaEmMillis = null,
        )
        ocorrenciaDao.inserir(ocorrencia)
        return ocorrencia
    }

    /** Reúne tudo que o e-mail precisa dizer. */
    suspend fun montarAlerta(ocorrenciaId: String): AlertaTermico? {
        val oc = ocorrenciaDao.buscar(ocorrenciaId) ?: return null
        val remessa = remessaDao.buscar(oc.remessaId) ?: return null
        val volume = oc.volumeId?.let { volumeDao.buscar(it) }
        val sessao = oc.sessaoId?.let { sessaoDao.buscar(it) }
        val etiqueta = sessao?.etiquetaId?.let { etiquetaDao.buscar(it) }
        val documentos = documentoDao.porRemessa(oc.remessaId)

        val leituras = oc.sessaoId?.let { leituraDao.porSessao(it) }.orEmpty()
        val ultima = leituras.maxByOrNull { it.lidaEmMillis }
        val perfil = PerfilTermico.porCodigo(remessa.perfilTermicoCodigo)

        val resumo = if (ultima != null && ultima.temperaturas.isNotEmpty() && sessao != null) {
            val inicio = Instant.ofEpochMilli(ultima.primeiroPontoMillis)
            val intervalo = ultima.intervaloRelatado.coerceAtLeast(1)
            RegrasTermicas.resumir(
                ultima.temperaturas.mapIndexed { i, t ->
                    com.thermotrace.app.domain.Medicao(
                        i, RegrasTermicas.instanteDaMedicao(inicio, i, intervalo), t
                    )
                },
                perfil, intervalo,
            )
        } else null

        return AlertaTermico(
            ocorrenciaId = oc.id,
            codigoRemessa = remessa.codigo,
            documento = documentos.firstOrNull()
                ?.let { d -> "${d.tipo.rotulo} ${d.numero}" } ?: "sem documento",
            volume = volume?.let { "Volume ${it.sequencia}" +
                (it.codigoExterno?.let { c -> " · $c" } ?: "") } ?: "—",
            etiquetaSerial = etiqueta?.serial ?: "—",
            perfil = perfil,
            gravidade = oc.gravidade ?: GravidadeExcursao.ALERTA,
            tipo = if ((oc.picoC ?: 0.0) > (oc.limiteC ?: 0.0))
                com.thermotrace.app.domain.TipoExcursao.ACIMA
            else com.thermotrace.app.domain.TipoExcursao.ABAIXO,
            picoC = oc.picoC ?: 0.0,
            limiteC = oc.limiteC ?: 0.0,
            duracao = Duration.ofSeconds(oc.duracaoSegundos ?: 0),
            ocorridoEm = Instant.ofEpochMilli(oc.ocorridoEmMillis),
            detectadoEm = Instant.ofEpochMilli(oc.detectadoEmMillis),
            tempoForaFaixaTotal = Duration.ofSeconds(resumo?.tempoForaFaixaSegundos ?: 0),
            mktC = resumo?.mktC,
            quantidadeExcursoes = resumo?.excursoes?.size ?: 1,
            transportadora = remessa.transportadora.ifBlank { "—" },
            destinatario = remessa.destinatario.ifBlank { "—" },
            destinatarios = destinatariosAtivos(),
        )
    }

    /**
     * Enfileira o alerta. O envio é do servidor.
     *
     * A ocorrência só muda para ALERTA_ENVIADO quando o canal confirma —
     * enfileirar não é enviar, e a tela precisa dizer a verdade sobre isso.
     */
    suspend fun enfileirarAlerta(alerta: AlertaTermico) {
        outboxDao.enfileirar(
            OutboxEntity(
                chaveIdempotencia = "alerta:${alerta.ocorrenciaId}",
                endpoint = "alertas",
                corpoJson = AlertaEmail.payload(alerta).toString(),
                criadoEmMillis = System.currentTimeMillis(),
            )
        )
    }

    suspend fun marcarAlertaEnviado(ocorrenciaId: String, canal: String, destinatarios: List<String>) {
        val oc = ocorrenciaDao.buscar(ocorrenciaId) ?: return
        ocorrenciaDao.atualizar(
            oc.copy(
                status = if (oc.status == StatusOcorrencia.ABERTA)
                    StatusOcorrencia.ALERTA_ENVIADO else oc.status,
                alertaEnviadoEmMillis = System.currentTimeMillis(),
                alertaCanal = canal,
                alertaDestinatarios = destinatarios.joinToString(", "),
            )
        )
    }

    // -----------------------------------------------------------------
    // Ação corretiva
    // -----------------------------------------------------------------

    /**
     * `ocorridoEm` é informado pelo usuário e pode ser retroativo — a ação
     * acontece na estrada, o lançamento acontece quando dá. `registradoEm` é
     * do sistema e não se edita. A distinção é o que sustenta o laudo.
     */
    suspend fun registrarAcaoCorretiva(
        ocorrenciaId: String,
        tipo: TipoAcaoCorretiva,
        descricao: String?,
        ocorridoEm: Instant,
        executadaPor: String?,
        empresa: String?,
        evidenciaUri: String?,
    ) {
        val agora = System.currentTimeMillis()
        val emTempoReal = Duration.between(ocorridoEm, Instant.now()).toMinutes() < 15

        acaoDao.inserir(
            AcaoCorretivaEntity(
                id = UUID.randomUUID().toString(),
                ocorrenciaId = ocorrenciaId,
                tipo = tipo,
                descricao = descricao,
                ocorridoEmMillis = ocorridoEm.toEpochMilli(),
                registradoEmMillis = agora,
                origem = if (emTempoReal) "confirmado_tempo_real" else "informado_posteriormente",
                executadaPor = executadaPor,
                empresa = empresa,
                evidenciaUri = evidenciaUri,
            )
        )

        ocorrenciaDao.buscar(ocorrenciaId)?.let { oc ->
            ocorrenciaDao.atualizar(
                oc.copy(
                    status = if (tipo == TipoAcaoCorretiva.NENHUMA)
                        StatusOcorrencia.SEM_ACAO else StatusOcorrencia.EM_TRATAMENTO
                )
            )
        }
        // Payload real. O servidor precisa dos DOIS instantes: quando a acao
        // ocorreu e quando foi lancada. Enviar so o id da ocorrencia, como
        // estava antes, nao permitia reconstruir nada disso.
        outboxDao.enfileirar(
            OutboxEntity(
                chaveIdempotencia = "acao:$ocorrenciaId:$agora",
                endpoint = "acoes",
                corpoJson = org.json.JSONObject().apply {
                    put("ocorrencia_id", ocorrenciaId)
                    put("tipo", tipo.name)
                    put("descricao", descricao ?: org.json.JSONObject.NULL)
                    put("ocorrido_em", java.time.format.DateTimeFormatter.ISO_INSTANT.format(ocorridoEm))
                    put("registrado_em", java.time.format.DateTimeFormatter.ISO_INSTANT.format(java.time.Instant.ofEpochMilli(agora)))
                    put("origem", if (emTempoReal) "confirmado_tempo_real" else "informado_posteriormente")
                    put("executada_por", executadaPor ?: org.json.JSONObject.NULL)
                    put("empresa", empresa ?: org.json.JSONObject.NULL)
                    put("evidencia_uri", evidenciaUri ?: org.json.JSONObject.NULL)
                }.toString(),
                criadoEmMillis = agora,
            )
        )
    }

    suspend fun encerrarOcorrencia(ocorrenciaId: String) {
        ocorrenciaDao.buscar(ocorrenciaId)?.let {
            ocorrenciaDao.atualizar(
                it.copy(status = StatusOcorrencia.RESOLVIDA,
                    fechadaEmMillis = System.currentTimeMillis())
            )
        }
    }

    // -----------------------------------------------------------------
    // Destinatários
    // -----------------------------------------------------------------

    fun observarDestinatarios() = destinatarioDao.observarTodos()

    suspend fun destinatariosAtivos(): List<DestinatarioAlerta> =
        destinatarioDao.ativos().map {
            DestinatarioAlerta(it.id, it.nome, it.email, it.papel, it.gravidadeMinima, it.ativo)
        }

    suspend fun salvarDestinatario(d: DestinatarioAlertaEntity) = destinatarioDao.inserir(d)
    suspend fun removerDestinatario(id: String) = destinatarioDao.remover(id)

    // -----------------------------------------------------------------
    // 2. Estado da etiqueta
    // -----------------------------------------------------------------

    /** Grava o resultado de uma verificação real do bit de status. */
    suspend fun registrarVerificacao(sessaoId: String, registrando: Boolean, tensaoV: Double?) {
        val sessao = sessaoDao.buscar(sessaoId) ?: return
        sessaoDao.atualizar(
            sessao.copy(
                verificadoEmMillis = System.currentTimeMillis(),
                registrandoNaVerificacao = registrando,
            )
        )
        tensaoV?.let { v ->
            etiquetaDao.buscar(sessao.etiquetaId)?.let {
                etiquetaDao.atualizar(it.copy(ultimaTensaoV = v))
            }
        }
    }

    /**
     * Estado derivado.
     *
     * `registrandoAgora` só é preenchido quando acabamos de encostar o
     * telefone. Fora disso é `null`, e o estado sai como PRESUMIDO — a tela
     * precisa mostrar essa diferença, porque afirmar "ativa" sem ter
     * verificado é o tipo de promessa que só se descobre falsa no destino.
     */
    suspend fun estadoDaEtiqueta(
        sessao: SessaoComLeituras?,
        registrandoAgora: Boolean? = null,
    ): EstadoEtiqueta {
        if (sessao == null) return EstadoEtiqueta.NAO_ATIVADA
        val ultima = sessao.leituras.maxByOrNull { it.lidaEmMillis }
        val etiqueta = etiquetaDao.buscar(sessao.sessao.etiquetaId)

        return EstadoEtiqueta.derivar(
            ativadaEm = Instant.ofEpochMilli(sessao.sessao.inicioDispositivoMillis),
            ativacaoConfirmada = sessao.sessao.ativacaoConfirmada,
            encerrada = sessao.sessao.encerradaEmMillis != null,
            intervaloSegundos = sessao.sessao.intervaloSegundos,
            capacidadeProgramada = sessao.sessao.quantidadePlanejada,
            registrosBaixados = ultima?.quantidadeMedida,
            tensaoV = ultima?.tensaoV ?: etiqueta?.ultimaTensaoV ?: sessao.sessao.tensaoNoStartV,
            ultimaTemperaturaC = ultima?.temperaturaInstantaneaC,
            registrandoAgora = registrandoAgora,
            registrandoNaUltimaVerificacao = sessao.sessao.registrandoNaVerificacao,
            verificadoEm = sessao.sessao.verificadoEmMillis?.let { Instant.ofEpochMilli(it) }
                ?: Instant.ofEpochMilli(sessao.sessao.inicioDispositivoMillis)
                    .takeIf { sessao.sessao.ativacaoConfirmada },
        )
    }

    // -----------------------------------------------------------------
    // Outbox
    // -----------------------------------------------------------------

    suspend fun pendentesDeEnvio(): List<OutboxEntity> = outboxDao.pendentes()
    suspend fun registrarFalhaEnvio(item: OutboxEntity, erro: String) =
        outboxDao.registrarFalha(item.id, erro)
}
