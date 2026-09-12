package com.thermotrace.app.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import com.thermotrace.app.domain.StatusOcorrencia
import kotlinx.coroutines.flow.Flow

data class OcorrenciaComAcoes(
    @Embedded val ocorrencia: OcorrenciaEntity,
    @Relation(parentColumn = "id", entityColumn = "ocorrenciaId")
    val acoes: List<AcaoCorretivaEntity>,
)

@Dao
interface OcorrenciaDao {

    /**
     * IGNORE por causa de `chaveNatural`: reler a mesma etiqueta encontra a
     * mesma excursão na mesma série. Sem isto, cada checkpoint duplicaria
     * todas as ocorrências anteriores — e o cliente receberia o mesmo alerta
     * várias vezes, que é o jeito mais rápido de fazer alguém desligar alerta.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun inserir(ocorrencia: OcorrenciaEntity): Long

    @Update suspend fun atualizar(ocorrencia: OcorrenciaEntity)

    @Query("SELECT * FROM ocorrencia WHERE id = :id")
    suspend fun buscar(id: String): OcorrenciaEntity?

    @Query("SELECT * FROM ocorrencia WHERE chaveNatural = :chave LIMIT 1")
    suspend fun porChaveNatural(chave: String): OcorrenciaEntity?

    @Transaction
    @Query("SELECT * FROM ocorrencia WHERE id = :id")
    fun observarComAcoes(id: String): Flow<OcorrenciaComAcoes?>

    @Transaction
    @Query("SELECT * FROM ocorrencia WHERE remessaId = :remessaId ORDER BY ocorridoEmMillis DESC")
    suspend fun porRemessa(remessaId: String): List<OcorrenciaComAcoes>

    @Transaction
    @Query("SELECT * FROM ocorrencia WHERE remessaId = :remessaId ORDER BY ocorridoEmMillis DESC")
    fun observarPorRemessa(remessaId: String): Flow<List<OcorrenciaComAcoes>>

    @Transaction
    @Query("SELECT * FROM ocorrencia WHERE status IN (:status) ORDER BY detectadoEmMillis DESC")
    fun observarPorStatus(status: List<StatusOcorrencia>): Flow<List<OcorrenciaComAcoes>>

    @Query("SELECT COUNT(*) FROM ocorrencia WHERE status IN ('ABERTA','ALERTA_ENVIADO','EM_TRATAMENTO')")
    fun contarAbertas(): Flow<Int>

    @Query("SELECT * FROM ocorrencia WHERE alertaEnviadoEmMillis IS NULL")
    suspend fun semAlertaEnviado(): List<OcorrenciaEntity>
}

@Dao
interface AcaoCorretivaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun inserir(acao: AcaoCorretivaEntity)

    @Query("SELECT * FROM acao_corretiva WHERE ocorrenciaId = :ocorrenciaId ORDER BY ocorridoEmMillis")
    suspend fun porOcorrencia(ocorrenciaId: String): List<AcaoCorretivaEntity>

    @Query("SELECT * FROM acao_corretiva WHERE sincronizada = 0")
    suspend fun naoSincronizadas(): List<AcaoCorretivaEntity>
}

@Dao
interface DestinatarioAlertaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun inserir(destinatario: DestinatarioAlertaEntity)

    @Query("SELECT * FROM destinatario_alerta ORDER BY papel, nome")
    fun observarTodos(): Flow<List<DestinatarioAlertaEntity>>

    @Query("SELECT * FROM destinatario_alerta WHERE ativo = 1")
    suspend fun ativos(): List<DestinatarioAlertaEntity>

    @Query("DELETE FROM destinatario_alerta WHERE id = :id")
    suspend fun remover(id: String)
}
