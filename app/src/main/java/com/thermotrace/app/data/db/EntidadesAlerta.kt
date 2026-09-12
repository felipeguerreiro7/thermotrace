package com.thermotrace.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.thermotrace.app.domain.GravidadeExcursao
import com.thermotrace.app.domain.PapelDestinatario
import com.thermotrace.app.domain.StatusOcorrencia
import com.thermotrace.app.domain.TipoAcaoCorretiva
import com.thermotrace.app.domain.TipoOcorrencia

/**
 * Ocorrências, ações corretivas e destinatários de alerta.
 *
 * Espelham `occurrence`, `corrective_action` e a lógica de notificação do
 * schema de auditoria em `db/01_schema.sql`.
 */

@Entity(
    tableName = "ocorrencia",
    foreignKeys = [ForeignKey(
        entity = RemessaEntity::class, parentColumns = ["id"],
        childColumns = ["remessaId"], onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("remessaId"), Index("sessaoId"), Index("chaveNatural", unique = true)]
)
data class OcorrenciaEntity(
    @PrimaryKey val id: String,
    val remessaId: String,
    val volumeId: String?,
    val sessaoId: String?,

    /**
     * Impede que a mesma excursão vire duas ocorrências quando o operador lê a
     * etiqueta de novo: a série é a mesma, o segmento é o mesmo.
     * Formato: sessaoId + índice da primeira amostra do segmento.
     */
    val chaveNatural: String,

    val tipo: TipoOcorrencia,
    val gravidade: GravidadeExcursao?,
    val status: StatusOcorrencia,

    val titulo: String,
    val detalhe: String?,

    // Os três tempos. Nunca colapsar.
    val ocorridoEmMillis: Long,
    val detectadoEmMillis: Long,
    val registradoEmMillis: Long,

    // Dados térmicos congelados no momento da detecção. Guardados aqui de
    // propósito: o laudo tem que mostrar o que se sabia quando o alerta saiu,
    // mesmo que uma leitura posterior mude os números.
    val picoC: Double?,
    val limiteC: Double?,
    val duracaoSegundos: Long?,
    val versaoRegra: String,

    val alertaEnviadoEmMillis: Long?,
    val alertaDestinatarios: String?,
    val alertaCanal: String?,       // "servidor" | "app_email" | null

    val fechadaEmMillis: Long?,
)

@Entity(
    tableName = "acao_corretiva",
    foreignKeys = [ForeignKey(
        entity = OcorrenciaEntity::class, parentColumns = ["id"],
        childColumns = ["ocorrenciaId"], onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("ocorrenciaId")]
)
data class AcaoCorretivaEntity(
    @PrimaryKey val id: String,
    val ocorrenciaId: String,
    val tipo: TipoAcaoCorretiva,
    val descricao: String?,
    /** Quando a ação foi executada. Pode ser retroativo. */
    val ocorridoEmMillis: Long,
    /** Quando foi lançada no sistema. Nunca editável. */
    val registradoEmMillis: Long,
    val origem: String,             // confirmado_tempo_real | informado_posteriormente
    val executadaPor: String?,
    val empresa: String?,
    val evidenciaUri: String?,
    val sincronizada: Boolean = false,
)

@Entity(tableName = "destinatario_alerta", indices = [Index("email", unique = true)])
data class DestinatarioAlertaEntity(
    @PrimaryKey val id: String,
    val nome: String,
    val email: String,
    val papel: PapelDestinatario,
    /** Só recebe desta gravidade para cima. Existe para evitar fadiga de alerta. */
    val gravidadeMinima: GravidadeExcursao,
    val ativo: Boolean,
)

class ConversoresAlerta {
    @TypeConverter fun tipoOcorrenciaParaTexto(v: TipoOcorrencia): String = v.name
    @TypeConverter fun textoParaTipoOcorrencia(v: String): TipoOcorrencia = TipoOcorrencia.valueOf(v)

    @TypeConverter fun statusOcorrenciaParaTexto(v: StatusOcorrencia): String = v.name
    @TypeConverter fun textoParaStatusOcorrencia(v: String): StatusOcorrencia = StatusOcorrencia.valueOf(v)

    @TypeConverter fun acaoParaTexto(v: TipoAcaoCorretiva): String = v.name
    @TypeConverter fun textoParaAcao(v: String): TipoAcaoCorretiva = TipoAcaoCorretiva.valueOf(v)

    @TypeConverter fun papelParaTexto(v: PapelDestinatario): String = v.name
    @TypeConverter fun textoParaPapel(v: String): PapelDestinatario = PapelDestinatario.valueOf(v)

    @TypeConverter fun gravidadeParaTexto(v: GravidadeExcursao?): String? = v?.name
    @TypeConverter fun textoParaGravidade(v: String?): GravidadeExcursao? =
        v?.let { GravidadeExcursao.valueOf(it) }
}
