package com.thermotrace.app.data.repo

import androidx.room.RoomDatabase
import androidx.room.withTransaction

import com.thermotrace.app.data.db.RemessaEntity
import com.thermotrace.app.data.db.SessaoComLeituras
import com.thermotrace.app.data.db.VolumeEntity
import com.thermotrace.app.domain.Medicao
import com.thermotrace.app.domain.PerfilTermico
import com.thermotrace.app.domain.RegrasTermicas
import com.thermotrace.app.domain.ResultadoTermico
import com.thermotrace.app.domain.ResumoTermico
import com.thermotrace.app.domain.TipoLeitura
import com.thermotrace.app.nfc.DecodedSession
import com.thermotrace.app.nfc.NfcOperator
import com.thermotrace.app.nfc.SessionDecoder
import com.thermotrace.app.nfc.TimeBase
import com.thermotrace.app.nfc.versaoSdkFmsh
import java.time.Instant

/**
 * O que acontece entre "a etiqueta respondeu" e "está gravado e alertado".
 *
 * **Por que existe:** essa sequência — decodificar, resolver os horários,
 * resumir, persistir a evidência, abrir ocorrência, enfileirar alerta,
 * registrar custódia, encerrar a remessa — estava escrita inteira dentro da
 * `LeituraViewModel`. Quando a tela inicial passou a finalizar a entrega com
 * um toque só, ou eu copiava tudo para lá, ou extraía.
 *
 * Copiar seria o erro caro: são duas telas gravando **evidência de auditoria**
 * por caminhos diferentes, e a primeira correção aplicada só em uma delas
 * produziria dois laudos divergentes para a mesma leitura.
 *
 * Separado em dois passos de propósito:
 *
 *  - [analisar] não escreve nada. A tela de Leitura mostra o resultado antes
 *    de o operador confirmar, e mostrar exige calcular.
 *  - [persistir] escreve. É o passo que o bipe rápido dispara direto.
 */
class FluxoRapido(
    private val repo: Repositorio,
    private val alertas: RepositorioAlertas,
    private val db: RoomDatabase,
) {

    /** Leitura já interpretada, ainda não gravada. */
    data class Previa(
        val sessao: SessaoComLeituras,
        val remessa: RemessaEntity,
        val volume: VolumeEntity,
        val perfil: PerfilTermico,
        val decodificada: DecodedSession,
        val medicoes: List<Medicao>,
        val resumo: ResumoTermico,
        val leitura: NfcOperator.RawRead,
    ) {
        val rotuloVolume: String
            get() = "Volume ${volume.sequencia}" +
                (volume.codigoExterno?.let { " · $it" } ?: "")
    }

    data class Resultado(
        val previa: Previa,
        val ocorrenciasNovas: Int,
        val encerrada: Boolean,
    ) {
        val veredito: ResultadoTermico get() = previa.resumo.resultado
    }

    /**
     * Interpreta a resposta da etiqueta contra a sessão que existe no banco.
     *
     * Falha — e é importante que falhe — quando o volume não tem ativação
     * registrada: sem o instante do START não existe base de tempo, e uma
     * série sem horário confiável não é evidência, é um gráfico bonito.
     */
    suspend fun analisar(
        volumeId: String,
        leitura: NfcOperator.RawRead,
    ): Result<Previa> {
        val volume = repo.buscarVolume(volumeId)
            ?: return Result.failure(IllegalStateException("Volume não encontrado."))
        val remessa = repo.buscarRemessa(volume.remessaId)?.remessa
            ?: return Result.failure(IllegalStateException("Remessa não encontrada."))
        val sessao = repo.sessaoDoVolume(volumeId)
            ?: return Result.failure(
                IllegalStateException(
                    "Este volume não tem ativação registrada. Sem a leitura de origem " +
                        "não há base de tempo para o histórico."
                )
            )

        val baseDeTempo = runCatching { TimeBase.valueOf(sessao.sessao.baseDeTempo) }
            .getOrDefault(TimeBase.START_INSTANT)

        val decodificada = SessionDecoder.decode(
            response = leitura.response,
            deviceReadAtMillis = leitura.deviceReadAtMillis,
            timeBase = baseDeTempo,
        ).getOrElse { return Result.failure(it) }

        val perfil = PerfilTermico.porCodigo(sessao.sessao.perfilTermicoCodigo)
        val intervalo = decodificada.header.intervalSeconds.coerceAtLeast(1)
        val medicoes = decodificada.samples.map {
            Medicao(it.index, Instant.ofEpochSecond(it.epochSeconds), it.temperatureC)
        }

        return Result.success(
            Previa(
                sessao = sessao,
                remessa = remessa,
                volume = volume,
                perfil = perfil,
                decodificada = decodificada,
                medicoes = medicoes,
                resumo = RegrasTermicas.resumir(medicoes, perfil, intervalo),
                leitura = leitura,
            )
        )
    }

    /**
     * Grava a leitura, abre as ocorrências novas e, na leitura final, fecha
     * a custódia e o monitoramento.
     *
     * Só as excursões NOVAS viram alerta. Reler a etiqueta encontra as mesmas
     * excursões do checkpoint anterior, e reenviar o mesmo e-mail é o jeito
     * mais rápido de alguém criar uma regra de caixa de entrada para o
     * remetente — e aí o alerta que importa também não é lido.
     */
    suspend fun persistir(previa: Previa, tipo: TipoLeitura): Resultado = db.withTransaction {
        val sessaoId = previa.sessao.sessao.id

        repo.registrarLeitura(
            sessaoId = sessaoId,
            tipo = tipo,
            decodificada = previa.decodificada,
            lidaEmMillis = previa.leitura.deviceReadAtMillis,
            desvioRelogioMs = null,   // preenchido pelo servidor na sincronização
            tensaoV = previa.leitura.voltageV,
            temperaturaInstantaneaC = previa.leitura.instantTempC,
            versaoSdk = versaoSdkFmsh(),
        )

        var novas = 0
        val excursoes = previa.resumo.excursoes
        if (excursoes.isNotEmpty()) {
            val abertas = alertas.registrarExcursoes(
                remessaId = previa.remessa.id,
                volumeId = previa.volume.id,
                sessaoId = sessaoId,
                excursoes = excursoes,
                detectadoEm = Instant.ofEpochMilli(previa.leitura.deviceReadAtMillis),
            )
            abertas.forEach { oc ->
                alertas.montarAlerta(oc.id)?.let { alertas.enfileirarAlerta(it) }
            }
            novas = abertas.size
        }

        if (tipo == TipoLeitura.FINAL) {
            repo.registrarCustodia(
                remessaId = previa.remessa.id,
                de = "transportadora",
                para = "destinatario",
                ocorridoEm = Instant.ofEpochMilli(previa.leitura.deviceReadAtMillis),
                origem = "automatico_nfc",
                volumesConfirmados = 1,
                recebedor = null,
                observacao = "Leitura final do ${previa.rotuloVolume}",
                local = null,
            )
        }

        Resultado(
            previa = previa,
            ocorrenciasNovas = novas,
            encerrada = tipo == TipoLeitura.FINAL,
        )
    }

    /** Analisar e gravar de uma vez. É o que o bipe de encerramento faz. */
    suspend fun registrar(
        volumeId: String,
        tipo: TipoLeitura,
        leitura: NfcOperator.RawRead,
    ): Result<Resultado> =
        analisar(volumeId, leitura).mapCatching { persistir(it, tipo) }
}
