package com.thermotrace.app.data.repo

import com.thermotrace.app.data.db.CustodiaDao
import com.thermotrace.app.data.db.CustodiaEntity
import com.thermotrace.app.data.db.DocumentoDao
import com.thermotrace.app.data.db.DocumentoEntity
import com.thermotrace.app.data.db.EtiquetaDao
import com.thermotrace.app.data.db.EtiquetaEntity
import com.thermotrace.app.data.db.LeituraDao
import com.thermotrace.app.data.db.LeituraEntity
import com.thermotrace.app.data.db.OutboxDao
import com.thermotrace.app.data.db.OutboxEntity
import com.thermotrace.app.data.db.RemessaDao
import com.thermotrace.app.data.db.RemessaEntity
import com.thermotrace.app.data.db.SessaoComLeituras
import com.thermotrace.app.data.db.SessaoDao
import com.thermotrace.app.data.db.SessaoEntity
import com.thermotrace.app.data.db.VolumeDao
import com.thermotrace.app.data.db.VolumeEntity
import com.thermotrace.app.domain.Medicao
import com.thermotrace.app.domain.PerfilTermico
import com.thermotrace.app.domain.RegrasTermicas
import com.thermotrace.app.domain.ResumoTermico
import com.thermotrace.app.domain.StatusRemessa
import com.thermotrace.app.domain.StatusVolume
import com.thermotrace.app.domain.DocumentoFiscal
import com.thermotrace.app.domain.IdentidadeVolume
import com.thermotrace.app.domain.TipoDocumentoFiscal
import com.thermotrace.app.domain.TipoDocumento
import com.thermotrace.app.domain.TipoLeitura
import com.thermotrace.app.nfc.DecodedSession
import com.thermotrace.app.nfc.OutboxPayload
import com.thermotrace.app.nfc.TagIdentity
import com.thermotrace.app.nfc.TimeBase
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.Year
import java.util.UUID

/**
 * Ponto único de escrita. Toda regra que protege a integridade do dado mora
 * aqui, não nas telas — uma tela nova não pode conseguir burlar a checagem
 * de identidade nem gravar uma leitura fora de ordem.
 */
class Repositorio(
    private val remessaDao: RemessaDao,
    private val documentoDao: DocumentoDao,
    private val volumeDao: VolumeDao,
    private val etiquetaDao: EtiquetaDao,
    private val sessaoDao: SessaoDao,
    private val leituraDao: LeituraDao,
    private val custodiaDao: CustodiaDao,
    private val outboxDao: OutboxDao,
    private val installId: String,
) {

    // -----------------------------------------------------------------
    // Consultas
    // -----------------------------------------------------------------

    fun observarRemessas() = remessaDao.observarTodas()
    fun observarRemessa(id: String) = remessaDao.observarCompleta(id)
    fun observarVolumes(remessaId: String) = volumeDao.observarPorRemessa(remessaId)
    fun observarSessoes(remessaId: String) = sessaoDao.observarComLeiturasPorRemessa(remessaId)
    fun observarEtiquetas() = etiquetaDao.observarTodas()
    fun observarPendentesDeEnvio() = outboxDao.observarPendentes()

    suspend fun buscarRemessa(id: String) = remessaDao.buscarCompleta(id)
    suspend fun buscarVolume(id: String) = volumeDao.buscar(id)
    suspend fun buscarEtiqueta(id: String) = etiquetaDao.buscar(id)
    suspend fun cadastrarEtiqueta(etiqueta: EtiquetaEntity) = etiquetaDao.inserir(etiqueta)
    suspend fun etiquetaPorQr(qr: String) = etiquetaDao.porQr(qr.trim())
    suspend fun etiquetaPorUid(uid: String) = etiquetaDao.porUid(TagIdentity.canonical(uid))
    suspend fun sessaoDoVolume(volumeId: String) = sessaoDao.ultimaComLeituras(volumeId)

    // -----------------------------------------------------------------
    // Criação de remessa
    // -----------------------------------------------------------------

    suspend fun proximoCodigo(): String {
        val prefixo = "REM-${Year.now().value}-"
        val n = remessaDao.contarComPrefixo(prefixo) + 184
        return prefixo + n.toString().padStart(5, '0')
    }

    suspend fun criarRemessa(
        codigo: String,
        remetente: String,
        transportadora: String,
        destinatario: String,
        destinoEndereco: String,
        contatoRecebimento: String,
        perfil: PerfilTermico,
        descricaoCarga: String,
        intervaloSegundos: Int,
        previsaoColeta: Instant?,
        previsaoEntrega: Instant?,
        totalVolumes: Int,
        volumesMonitorados: Int,
        documento: DocumentoFiscal?,
    ): String {
        // A identidade da remessa vem do documento, não da etiqueta.
        // A etiqueta é insumo reutilizável; o documento é o que existe no
        // mundo para o cliente, para a transportadora e para a fiscalização.
        documento?.identidade?.let { identidade ->
            remessaDao.porIdentidadeDocumento(identidade)?.let { existente ->
                // Mesma NF-e não vira duas remessas. Devolvemos a que já existe
                // em vez de criar uma duplicata silenciosa.
                return existente.id
            }
        }

        val id = UUID.randomUUID().toString()
        remessaDao.inserir(
            RemessaEntity(
                id = id,
                codigo = codigo,
                remetente = remetente,
                transportadora = transportadora,
                destinatario = destinatario,
                destinoEndereco = destinoEndereco,
                contatoRecebimento = contatoRecebimento,
                perfilTermicoCodigo = perfil.codigo,
                descricaoCarga = descricaoCarga,
                intervaloSegundos = intervaloSegundos,
                previsaoColetaMillis = previsaoColeta?.toEpochMilli(),
                previsaoEntregaMillis = previsaoEntrega?.toEpochMilli(),
                identidadeDocumento = documento?.identidade,
                status = StatusRemessa.EM_PREPARACAO,
                criadaEmMillis = System.currentTimeMillis(),
            )
        )

        documento?.let { doc ->
            documentoDao.inserir(
                DocumentoEntity(
                    id = UUID.randomUUID().toString(),
                    remessaId = id,
                    tipo = mapearTipo(doc.tipo),
                    numero = doc.numero,
                    chaveAcesso = doc.chaveAcesso,
                    cnpjEmitente = doc.cnpjEmitente,
                    serie = doc.serie,
                    ufEmitente = doc.ufEmitente,
                    competencia = doc.competencia?.toString(),
                    validado = doc.validado,
                    observacaoValidacao = doc.observacaoValidacao,
                    conteudoBruto = doc.conteudoBruto,
                    simbologia = doc.simbologia,
                    digitadoManualmente = doc.simbologia == "MANUAL",
                )
            )
        }

        volumeDao.inserirTodos(
            (1..totalVolumes).map { seq ->
                VolumeEntity(
                    id = UUID.randomUUID().toString(),
                    remessaId = id,
                    sequencia = seq,
                    codigoExterno = null,
                    identidade = documento?.identidade?.let {
                        IdentidadeVolume.gerar(it, seq)
                    },
                    monitorado = seq <= volumesMonitorados,
                    status = StatusVolume.SEM_ETIQUETA,
                    etiquetaId = null,
                )
            }
        )
        return id
    }

    /**
     * Cria remessa, volume, vínculo e sessão de uma vez, logo depois de a
     * etiqueta ser ligada fisicamente.
     *
     * Existe porque o fluxo agora começa na etiqueta e não no formulário: o
     * operador bipa, liga, e só então diz de que remessa se trata.
     *
     * A gravação é **imediata e completa**. Se o app morrer entre ligar a
     * etiqueta e preencher os dados, a etiqueta continua gravando de verdade —
     * e sem este registro não haveria nada no sistema apontando para ela. Uma
     * remessa incompleta em "Em preparação" é recuperável; uma etiqueta
     * gravando sem dono, não.
     */
    suspend fun abrirRemessaComEtiqueta(
        etiqueta: EtiquetaEntity,
        perfil: PerfilTermico,
        horasPrevistas: Int,
        epochInicioEtiqueta: Long,
        inicioDispositivoMillis: Long,
        baseDeTempo: String,
        intervaloSegundos: Int,
        quantidadePlanejada: Int,
        modoArmazenamento: Int,
        confirmada: Boolean,
        tensaoV: Double?,
    ): String {
        val remessaId = criarRemessa(
            codigo = proximoCodigo(),
            remetente = "",
            transportadora = "",
            destinatario = "",
            destinoEndereco = "",
            contatoRecebimento = "",
            perfil = perfil,
            descricaoCarga = "",
            intervaloSegundos = intervaloSegundos,
            previsaoColeta = Instant.now(),
            previsaoEntrega = Instant.now().plusSeconds(horasPrevistas * 3600L),
            totalVolumes = 1,
            volumesMonitorados = 1,
            documento = null,
        )

        val volume = volumeDao.porRemessa(remessaId).first()
        volumeDao.atualizar(
            volume.copy(etiquetaId = etiqueta.id, status = StatusVolume.MONITORANDO)
        )

        registrarAtivacao(
            remessaId = remessaId,
            volumeId = volume.id,
            etiquetaId = etiqueta.id,
            perfil = perfil,
            epochInicioEtiqueta = epochInicioEtiqueta,
            inicioDispositivoMillis = inicioDispositivoMillis,
            desvioRelogioMs = null,
            baseDeTempo = baseDeTempo,
            delayMinutos = 0,
            intervaloSegundos = intervaloSegundos,
            quantidadePlanejada = quantidadePlanejada,
            modoArmazenamento = modoArmazenamento,
            confirmada = confirmada,
            tensaoV = tensaoV,
        )

        registrarCustodia(
            remessaId = remessaId,
            de = null, para = "embarcador",
            ocorridoEm = Instant.ofEpochMilli(inicioDispositivoMillis),
            origem = "automatico_nfc",
            volumesConfirmados = null, recebedor = null,
            observacao = "Etiqueta ${etiqueta.serial} ligada",
            local = null,
        )

        // A etiqueta está gravando: a remessa não está mais "em preparação".
        // Sem esta linha ela ficava parada nesse estado até alguém abrir a
        // tela de detalhe, e o recorte "Em andamento" acertava por acidente.
        remessaDao.definirStatus(remessaId, StatusRemessa.AGUARDANDO_COLETA)
        return remessaId
    }

    // -----------------------------------------------------------------
    // Documento fiscal anexado depois
    // -----------------------------------------------------------------

    sealed interface ResultadoDocumento {
        data class Ok(val documento: DocumentoFiscal) : ResultadoDocumento
        /** A mesma chave já identifica outra remessa. */
        data class JaUsada(val codigoRemessa: String) : ResultadoDocumento
        data class RemessaInexistente(val motivo: String) : ResultadoDocumento
    }

    /**
     * Amarra a nota fiscal a uma remessa que já existe.
     *
     * O fluxo de campo virou: bipar para ligar, bipar a nota, bipar para
     * encerrar. Ligar vem primeiro porque é o que tem hora marcada — a caixa
     * está fechando. A nota entra depois, e é aqui.
     *
     * A chave do documento vira a identidade da remessa e desce para os
     * volumes (`chave#V001`), o que preserva o histórico se a etiqueta for
     * trocada no meio do caminho.
     */
    suspend fun anexarDocumento(remessaId: String, doc: DocumentoFiscal): ResultadoDocumento {
        val remessa = remessaDao.buscar(remessaId)
            ?: return ResultadoDocumento.RemessaInexistente("Remessa não encontrada.")

        // Duas remessas com a mesma NF-e é erro de operação, não de digitação:
        // ou o operador bipou a nota errada, ou está ligando uma segunda
        // etiqueta para uma carga que já tem remessa aberta. Nos dois casos o
        // certo é dizer qual é a remessa que já tem essa nota.
        remessaDao.porIdentidadeDocumento(doc.identidade)?.let { existente ->
            if (existente.id != remessaId) {
                return ResultadoDocumento.JaUsada(existente.codigo)
            }
        }

        documentoDao.inserir(
            DocumentoEntity(
                id = UUID.randomUUID().toString(),
                remessaId = remessaId,
                tipo = mapearTipo(doc.tipo),
                numero = doc.numero,
                chaveAcesso = doc.chaveAcesso,
                cnpjEmitente = doc.cnpjEmitente,
                serie = doc.serie,
                ufEmitente = doc.ufEmitente,
                competencia = doc.competencia?.toString(),
                validado = doc.validado,
                observacaoValidacao = doc.observacaoValidacao,
                conteudoBruto = doc.conteudoBruto,
                simbologia = doc.simbologia,
                digitadoManualmente = doc.simbologia == "MANUAL",
            )
        )
        remessaDao.atualizar(remessa.copy(identidadeDocumento = doc.identidade))

        volumeDao.porRemessa(remessaId).forEach { v ->
            if (v.identidade == null) {
                volumeDao.atualizar(
                    v.copy(identidade = IdentidadeVolume.gerar(doc.identidade, v.sequencia))
                )
            }
        }
        return ResultadoDocumento.Ok(doc)
    }

    suspend fun documentosDaRemessa(remessaId: String) = documentoDao.porRemessa(remessaId)

    /** Remessa em que a etiqueta está em uso agora, se houver. */
    suspend fun remessaAtivaDaEtiqueta(etiquetaId: String): RemessaEntity? {
        for (v in volumeDao.porEtiqueta(etiquetaId)) {
            val r = remessaDao.buscar(v.remessaId) ?: continue
            if (r.status != StatusRemessa.CONCLUIDA && r.status != StatusRemessa.CANCELADA) {
                return r
            }
        }
        return null
    }

    /**
     * Apaga a remessa e, por CASCADE, documento, volume, sessao, leitura,
     * custodia e ocorrencia.
     *
     * NAO para o registro na etiqueta: isso e fisico e exige aproximacao.
     * Apagar aqui so limpa a tela; a etiqueta continua gravando. Para
     * liberar a etiqueta de verdade, use "Parar registro" na folha dela.
     *
     * Existe para limpar teste de bancada. Quando houver backend, isto vira
     * arquivamento: evidencia de auditoria nao se apaga, se encerra.
     */
    suspend fun apagarRemessa(remessaId: String) = remessaDao.apagar(remessaId)

    suspend fun apagarTodasAsRemessas() = remessaDao.apagarTodas()

    suspend fun definirStatus(remessaId: String, status: StatusRemessa) =
        remessaDao.definirStatus(remessaId, status)

    suspend fun atualizarVolume(volume: VolumeEntity) = volumeDao.atualizar(volume)

    // -----------------------------------------------------------------
    // Vínculo etiqueta ↔ volume
    // -----------------------------------------------------------------

    sealed interface ResultadoVinculo {
        data class Ok(val etiqueta: EtiquetaEntity) : ResultadoVinculo
        data object QrDesconhecido : ResultadoVinculo
        data class JaVinculada(val etiqueta: EtiquetaEntity) : ResultadoVinculo
        data class Aposentada(val motivo: String) : ResultadoVinculo
    }

    /**
     * Vincula pelo QR — e só pelo QR. O UID só entra depois, como CONFIRMAÇÃO
     * de que a etiqueta encostada é a que foi escaneada.
     *
     * Essa ordem não é detalhe. É o que impede o erro que a análise apontou:
     * trinta etiquetas na bancada, o operador escaneia uma e encosta o celular
     * em outra.
     */
    suspend fun vincularPorQr(volumeId: String, qr: String): ResultadoVinculo {
        val etiqueta = etiquetaDao.porQr(qr.trim()) ?: return ResultadoVinculo.QrDesconhecido

        etiqueta.aposentadaEmMillis?.let {
            return ResultadoVinculo.Aposentada("Etiqueta aposentada.")
        }
        etiqueta.calibracaoValidaAteMillis?.let { validade ->
            if (validade < System.currentTimeMillis()) {
                return ResultadoVinculo.Aposentada(
                    "Calibração vencida. Um lote sem calibração válida não gera laudo defensável."
                )
            }
        }
        if (etiquetaDao.contarVinculosAtivos(etiqueta.id) > 0) {
            val volume = volumeDao.buscar(volumeId)
            if (volume?.etiquetaId != etiqueta.id) return ResultadoVinculo.JaVinculada(etiqueta)
        }

        val volume = volumeDao.buscar(volumeId) ?: return ResultadoVinculo.QrDesconhecido
        volumeDao.atualizar(
            volume.copy(etiquetaId = etiqueta.id, status = StatusVolume.VINCULADO)
        )
        return ResultadoVinculo.Ok(etiqueta)
    }

    // -----------------------------------------------------------------
    // Leitura 1 — ATIVAÇÃO (obrigatória)
    // -----------------------------------------------------------------

    suspend fun registrarAtivacao(
        remessaId: String,
        volumeId: String,
        etiquetaId: String,
        perfil: PerfilTermico,
        epochInicioEtiqueta: Long,
        inicioDispositivoMillis: Long,
        desvioRelogioMs: Long?,
        baseDeTempo: String,
        delayMinutos: Int,
        intervaloSegundos: Int,
        quantidadePlanejada: Int,
        modoArmazenamento: Int,
        confirmada: Boolean,
        tensaoV: Double?,
    ): String {
        // Chave natural (etiqueta + epoch): reativar por engano não cria
        // sessão duplicada.
        sessaoDao.porChaveNatural(etiquetaId, epochInicioEtiqueta)?.let { return it.id }

        val id = UUID.randomUUID().toString()
        sessaoDao.inserir(
            SessaoEntity(
                id = id,
                remessaId = remessaId,
                volumeId = volumeId,
                etiquetaId = etiquetaId,
                perfilTermicoCodigo = perfil.codigo,
                epochInicioEtiqueta = epochInicioEtiqueta,
                inicioDispositivoMillis = inicioDispositivoMillis,
                desvioRelogioMs = desvioRelogioMs,
                baseDeTempo = baseDeTempo,
                plataformaAtivacao = "android",
                delayMinutos = delayMinutos,
                intervaloSegundos = intervaloSegundos,
                quantidadePlanejada = quantidadePlanejada,
                minConfiguradoC = perfil.minC.toDouble(),
                maxConfiguradoC = perfil.maxC.toDouble(),
                modoArmazenamento = modoArmazenamento,
                ativacaoConfirmada = confirmada,
                tensaoNoStartV = tensaoV,
                encerradaEmMillis = null,
            )
        )

        volumeDao.buscar(volumeId)?.let {
            volumeDao.atualizar(it.copy(status = StatusVolume.MONITORANDO))
        }
        etiquetaDao.buscar(etiquetaId)?.let {
            etiquetaDao.atualizar(
                it.copy(ciclosAtivacao = it.ciclosAtivacao + 1, ultimaTensaoV = tensaoV)
            )
        }
        // Payload real da ativação. O servidor precisa de tudo isto para
        // reconstruir os horários da série: sem `base_de_tempo` e o epoch
        // gravado, o instante de cada ponto é adivinhação.
        val etiqueta = etiquetaDao.buscar(etiquetaId)
        outboxDao.enfileirar(
            OutboxEntity(
                chaveIdempotencia = "sessao:$id",
                endpoint = "sessoes",
                corpoJson = JSONObject().apply {
                    put("sessao_id", id)
                    put("remessa_id", remessaId)
                    put("volume_id", volumeId)
                    put("etiqueta_serial", etiqueta?.serial ?: JSONObject.NULL)
                    put("nfc_uid", etiqueta?.uidNfc ?: JSONObject.NULL)
                    put("perfil_termico", perfil.codigo)
                    put("epoch_inicio_etiqueta", epochInicioEtiqueta)
                    put("inicio_dispositivo_em", isoUtc(inicioDispositivoMillis))
                    put("desvio_relogio_ms", desvioRelogioMs ?: JSONObject.NULL)
                    put("base_de_tempo", baseDeTempo)
                    put("plataforma_ativacao", "android")
                    put("delay_minutos", delayMinutos)
                    put("intervalo_segundos", intervaloSegundos)
                    put("quantidade_planejada", quantidadePlanejada)
                    put("min_configurado_c", perfil.minC)
                    put("max_configurado_c", perfil.maxC)
                    put("modo_armazenamento", modoArmazenamento)
                    put("ativacao_confirmada", confirmada)
                    put("tensao_no_start_v", tensaoV ?: JSONObject.NULL)
                    put("install_id", installId)
                }.toString(),
                criadoEmMillis = System.currentTimeMillis(),
            )
        )
        return id
    }

    // -----------------------------------------------------------------
    // Leituras 2 e 3 — CHECKPOINT (opcional) e FINAL (obrigatória)
    // -----------------------------------------------------------------

    /**
     * Grava uma leitura de etiqueta.
     *
     * Preserva a resposta crua do SDK e encadeia o hash com a leitura
     * anterior da mesma sessão. Se o decodificador melhorar depois, a série
     * pode ser recalculada a partir do bruto; se alguém alterar o banco, a
     * cadeia quebra e aparece.
     */
    suspend fun registrarLeitura(
        sessaoId: String,
        tipo: TipoLeitura,
        decodificada: DecodedSession,
        lidaEmMillis: Long,
        desvioRelogioMs: Long?,
        tensaoV: Double?,
        temperaturaInstantaneaC: Double?,
        versaoSdk: String,
        localizacao: com.thermotrace.app.data.local.FixLocal? = null,
    ): LeituraEntity {
        val sequencia = leituraDao.porSessao(sessaoId).size + 1
        val chave = "dev:$installId:sess:$sessaoId:read:$sequencia"

        val bruto = decodificada.raw.joinToString("")
        val hashPayload = sha256(bruto)
        val anterior = leituraDao.ultimoHash(sessaoId)
        val encadeado = sha256((anterior ?: "") + hashPayload)

        val leitura = LeituraEntity(
            id = UUID.randomUUID().toString(),
            sessaoId = sessaoId,
            tipo = tipo,
            chaveIdempotencia = chave,
            lidaEmMillis = lidaEmMillis,
            desvioRelogioMs = desvioRelogioMs,
            respostaBruta = decodificada.raw,
            versaoSdk = versaoSdk,
            versaoDecodificador = VERSAO_DECODIFICADOR,
            codigoEstado = decodificada.header.statusCode,
            quantidadeMedida = decodificada.samples.size,
            intervaloRelatado = decodificada.header.intervalSeconds,
            tensaoV = tensaoV,
            temperaturaInstantaneaC = temperaturaInstantaneaC,
            derivaRelogioSegundos = decodificada.rtcDriftSeconds,
            horariosCorrigidos = decodificada.timestampsCorrected,
            primeiroPontoMillis = (decodificada.samples.firstOrNull()?.epochSeconds ?: 0L) * 1000L,
            temperaturas = decodificada.samples.map { it.temperatureC },
            hashPayload = hashPayload,
            hashAnterior = anterior,
            hashEncadeado = encadeado,
            latitude = localizacao?.latitude,
            longitude = localizacao?.longitude,
            precisaoMetros = localizacao?.precisaoMetros,
            provedorLocal = localizacao?.provedor,
            localizadoEmMillis = localizacao?.obtidoEmMillis,
        )
        leituraDao.inserir(leitura)

        // Payload REAL, não o id da entidade.
        //
        // Isto era o defeito P1 do diagnóstico da Fase 0: `enfileirar` gravava
        // `corpoJson = entidadeId`, então o outbox postava uma string com um
        // UUID que o servidor não teria como resolver. A fila, o retry e a
        // idempotência estavam corretos; o conteúdo, não. Sincronização que
        // não sincroniza é pior que sincronização ausente, porque parece
        // funcionar.
        val sessao = sessaoDao.buscar(sessaoId)
        val etiqueta = sessao?.let { etiquetaDao.buscar(it.etiquetaId) }
        val baseDeTempo = runCatching {
            TimeBase.valueOf(sessao?.baseDeTempo ?: TimeBase.START_INSTANT.name)
        }.getOrDefault(TimeBase.START_INSTANT)

        outboxDao.enfileirar(
            OutboxEntity(
                chaveIdempotencia = chave,
                endpoint = "leituras",
                corpoJson = OutboxPayload.build(
                    ingestKey = chave,
                    sessionId = sessaoId,
                    nfcUid = etiqueta?.uidNfc.orEmpty(),
                    purpose = when (tipo) {
                        TipoLeitura.ATIVACAO -> "ativacao"
                        TipoLeitura.CHECKPOINT -> "checkpoint"
                        TipoLeitura.FINAL -> "destino"
                    },
                    decoded = decodificada,
                    timeBase = baseDeTempo,
                    deviceReadAtMillis = lidaEmMillis,
                    deviceClockSkewMillis = desvioRelogioMs,
                    voltageV = tensaoV,
                    instantTempC = temperaturaInstantaneaC,
                    sdkVersion = versaoSdk,
                ).toString(),
                criadoEmMillis = System.currentTimeMillis(),
            )
        )

        if (tipo == TipoLeitura.FINAL) {
            sessaoDao.buscar(sessaoId)?.let { sessao ->
                sessaoDao.atualizar(sessao.copy(encerradaEmMillis = lidaEmMillis))
                volumeDao.buscar(sessao.volumeId)?.let {
                    volumeDao.atualizar(it.copy(status = StatusVolume.ENCERRADO))
                }
                concluirSePossivel(sessao.remessaId)
            }
        }
        return leitura
    }

    /**
     * A remessa só conclui quando TODO volume monitorado tem leitura final.
     * É a contrapartida da ativação obrigatória: sem fechar o ciclo nos dois
     * extremos, não existe cadeia fria documentada.
     */
    suspend fun concluirSePossivel(remessaId: String) {
        val volumes = volumeDao.porRemessa(remessaId).filter { it.monitorado }
        if (volumes.isEmpty()) return
        val todosEncerrados = volumes.all { it.status == StatusVolume.ENCERRADO }
        remessaDao.definirStatus(
            remessaId,
            if (todosEncerrados) StatusRemessa.CONCLUIDA
            else StatusRemessa.ENTREGUE_AGUARDANDO_LEITURA
        )
    }

    // -----------------------------------------------------------------
    // Custódia
    // -----------------------------------------------------------------

    suspend fun registrarCustodia(
        remessaId: String,
        de: String?,
        para: String,
        ocorridoEm: Instant,
        origem: String,
        volumesConfirmados: Int?,
        recebedor: String?,
        observacao: String?,
        local: String?,
    ) {
        custodiaDao.inserir(
            CustodiaEntity(
                id = UUID.randomUUID().toString(),
                remessaId = remessaId,
                de = de,
                para = para,
                ocorridoEmMillis = ocorridoEm.toEpochMilli(),
                // Nunca igual a ocorridoEm por construção: são fatos diferentes.
                registradoEmMillis = System.currentTimeMillis(),
                origem = origem,
                volumesConfirmados = volumesConfirmados,
                recebedor = recebedor,
                observacao = observacao,
                local = local,
            )
        )
        custodiaDao.porRemessa(remessaId) // toca a tabela para invalidar observadores
    }

    suspend fun eventosCustodia(remessaId: String) = custodiaDao.porRemessa(remessaId)

    // -----------------------------------------------------------------
    // Análise
    // -----------------------------------------------------------------

    fun medicoesDe(leitura: LeituraEntity): List<Medicao> {
        val inicio = Instant.ofEpochMilli(leitura.primeiroPontoMillis)
        val intervalo = leitura.intervaloRelatado.coerceAtLeast(1)
        return leitura.temperaturas.mapIndexed { i, t ->
            Medicao(i, RegrasTermicas.instanteDaMedicao(inicio, i, intervalo), t)
        }
    }

    fun resumir(sessao: SessaoComLeituras): ResumoTermico {
        val ultima = sessao.leituras.maxByOrNull { it.lidaEmMillis } ?: return ResumoTermico.VAZIO
        if (ultima.temperaturas.isEmpty()) return ResumoTermico.VAZIO
        return RegrasTermicas.resumir(
            medicoes = medicoesDe(ultima),
            perfil = PerfilTermico.porCodigo(sessao.sessao.perfilTermicoCodigo),
            intervaloSegundos = ultima.intervaloRelatado.coerceAtLeast(1),
        )
    }

    // -----------------------------------------------------------------

    /** ISO-8601 em UTC. O servidor nunca recebe hora local sem fuso. */
    private fun isoUtc(millis: Long): String =
        java.time.format.DateTimeFormatter.ISO_INSTANT
            .format(Instant.ofEpochMilli(millis))

    /** O enum de UI é mais simples que o fiscal; o fiscal é a fonte da verdade. */
    private fun mapearTipo(t: TipoDocumentoFiscal): TipoDocumento = when (t) {
        TipoDocumentoFiscal.NFE, TipoDocumentoFiscal.NFCE -> TipoDocumento.NFE
        TipoDocumentoFiscal.CTE, TipoDocumentoFiscal.CTE_OS -> TipoDocumento.CTE
        TipoDocumentoFiscal.AWB -> TipoDocumento.AWB
        TipoDocumentoFiscal.PEDIDO -> TipoDocumento.PEDIDO
        TipoDocumentoFiscal.SEM_DOCUMENTO -> TipoDocumento.SEM_DOCUMENTO
        else -> TipoDocumento.OUTRO
    }

    private fun sha256(texto: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(texto.toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val VERSAO_DECODIFICADOR = com.thermotrace.app.nfc.SessionDecoder.VERSION
    }
}
