package com.thermotrace.app.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import com.thermotrace.app.domain.StatusRemessa
import com.thermotrace.app.domain.TipoLeitura
import kotlinx.coroutines.flow.Flow

/** Remessa com tudo que a tela de detalhe precisa, numa consulta só. */
data class RemessaCompleta(
    @Embedded val remessa: RemessaEntity,
    @Relation(parentColumn = "id", entityColumn = "remessaId")
    val documentos: List<DocumentoEntity>,
    @Relation(parentColumn = "id", entityColumn = "remessaId")
    val volumes: List<VolumeEntity>,
    @Relation(parentColumn = "id", entityColumn = "remessaId")
    val custodia: List<CustodiaEntity>,
    @Relation(parentColumn = "id", entityColumn = "remessaId")
    val sessoes: List<SessaoEntity>,
)

data class SessaoComLeituras(
    @Embedded val sessao: SessaoEntity,
    @Relation(parentColumn = "id", entityColumn = "sessaoId")
    val leituras: List<LeituraEntity>,
)

@Dao
interface RemessaDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun inserir(remessa: RemessaEntity)

    @Update suspend fun atualizar(remessa: RemessaEntity)

    @Query("SELECT * FROM remessa ORDER BY criadaEmMillis DESC")
    fun observarTodas(): Flow<List<RemessaEntity>>

    @Query("SELECT * FROM remessa WHERE status IN (:status) ORDER BY criadaEmMillis DESC")
    fun observarPorStatus(status: List<StatusRemessa>): Flow<List<RemessaEntity>>

    @Transaction
    @Query("SELECT * FROM remessa WHERE id = :id")
    fun observarCompleta(id: String): Flow<RemessaCompleta?>

    @Transaction
    @Query("SELECT * FROM remessa WHERE id = :id")
    suspend fun buscarCompleta(id: String): RemessaCompleta?

    @Query("SELECT * FROM remessa WHERE id = :id")
    suspend fun buscar(id: String): RemessaEntity?

    /** Deduplicação pela chave do documento fiscal. */
    @Query("SELECT * FROM remessa WHERE identidadeDocumento = :identidade LIMIT 1")
    suspend fun porIdentidadeDocumento(identidade: String): RemessaEntity?

    /**
     * Apaga a remessa e tudo que depende dela (documento, volume, sessao,
     * leitura, custodia, ocorrencia) por CASCADE.
     *
     * Existe para limpar teste de bancada. Em producao com backend isto vira
     * arquivamento, nao exclusao: evidencia de auditoria nao se apaga, se
     * encerra. Enquanto o dado so vive no aparelho e e teste, apagar e o que
     * mantem a tela legivel.
     */
    @Query("DELETE FROM remessa WHERE id = :id")
    suspend fun apagar(id: String)

    @Query("DELETE FROM remessa")
    suspend fun apagarTodas()

    @Query("UPDATE remessa SET status = :status WHERE id = :id")
    suspend fun definirStatus(id: String, status: StatusRemessa)

    @Query("SELECT COUNT(*) FROM remessa WHERE codigo LIKE :prefixo || '%'")
    suspend fun contarComPrefixo(prefixo: String): Int
}

@Dao
interface DocumentoDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun inserir(documento: DocumentoEntity)

    @Query("SELECT * FROM documento WHERE remessaId = :remessaId")
    suspend fun porRemessa(remessaId: String): List<DocumentoEntity>
}

@Dao
interface VolumeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun inserirTodos(volumes: List<VolumeEntity>)

    @Update suspend fun atualizar(volume: VolumeEntity)

    @Query("SELECT * FROM volume WHERE remessaId = :remessaId ORDER BY sequencia")
    fun observarPorRemessa(remessaId: String): Flow<List<VolumeEntity>>

    @Query("SELECT * FROM volume WHERE remessaId = :remessaId ORDER BY sequencia")
    suspend fun porRemessa(remessaId: String): List<VolumeEntity>

    @Query("SELECT * FROM volume WHERE id = :id")
    suspend fun buscar(id: String): VolumeEntity?

    @Query("SELECT * FROM volume WHERE etiquetaId = :etiquetaId")
    suspend fun porEtiqueta(etiquetaId: String): List<VolumeEntity>

    @Query("SELECT COUNT(*) FROM volume WHERE remessaId = :remessaId AND monitorado = 1")
    suspend fun contarMonitorados(remessaId: String): Int
}

@Dao
interface EtiquetaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun inserir(etiqueta: EtiquetaEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun inserirTodas(etiquetas: List<EtiquetaEntity>)

    @Update suspend fun atualizar(etiqueta: EtiquetaEntity)

    @Query("SELECT * FROM etiqueta ORDER BY serial")
    fun observarTodas(): Flow<List<EtiquetaEntity>>

    @Query("SELECT * FROM etiqueta WHERE id = :id")
    suspend fun buscar(id: String): EtiquetaEntity?

    /** Busca pelo QR lido. É por aqui que o vínculo começa. */
    @Query("SELECT * FROM etiqueta WHERE qrPayload = :qr OR serial = :qr LIMIT 1")
    suspend fun porQr(qr: String): EtiquetaEntity?

    @Query("SELECT * FROM etiqueta WHERE uidNfc = :uid LIMIT 1")
    suspend fun porUid(uid: String): EtiquetaEntity?

    /**
     * Uma etiqueta não pode estar em duas remessas ativas ao mesmo tempo.
     * Esta é a consulta que impede o erro operacional mais caro do sistema.
     */
    @Query("""
        SELECT COUNT(*) FROM volume v
        JOIN remessa r ON r.id = v.remessaId
        WHERE v.etiquetaId = :etiquetaId
          AND r.status NOT IN ('CONCLUIDA','CANCELADA')
    """)
    suspend fun contarVinculosAtivos(etiquetaId: String): Int
}

@Dao
interface SessaoDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun inserir(sessao: SessaoEntity)

    @Update suspend fun atualizar(sessao: SessaoEntity)

    @Query("SELECT * FROM sessao WHERE id = :id")
    suspend fun buscar(id: String): SessaoEntity?

    @Query("SELECT * FROM sessao WHERE volumeId = :volumeId ORDER BY inicioDispositivoMillis DESC LIMIT 1")
    suspend fun ultimaDoVolume(volumeId: String): SessaoEntity?

    @Query("SELECT * FROM sessao WHERE remessaId = :remessaId")
    suspend fun porRemessa(remessaId: String): List<SessaoEntity>

    @Transaction
    @Query("SELECT * FROM sessao WHERE id = :id")
    fun observarComLeituras(id: String): Flow<SessaoComLeituras?>

    @Transaction
    @Query("SELECT * FROM sessao WHERE remessaId = :remessaId")
    fun observarComLeiturasPorRemessa(remessaId: String): Flow<List<SessaoComLeituras>>

    @Transaction
    @Query("SELECT * FROM sessao WHERE volumeId = :volumeId ORDER BY inicioDispositivoMillis DESC LIMIT 1")
    suspend fun ultimaComLeituras(volumeId: String): SessaoComLeituras?

    @Query("SELECT * FROM sessao WHERE etiquetaId = :etiquetaId AND epochInicioEtiqueta = :epoch LIMIT 1")
    suspend fun porChaveNatural(etiquetaId: String, epoch: Long): SessaoEntity?
}

@Dao
interface LeituraDao {
    /**
     * IGNORE, não REPLACE. `chaveIdempotencia` é única: se a mesma leitura
     * chegar duas vezes (retry do outbox, toque duplo no NFC), a segunda é
     * descartada em silêncio. Evidência nunca é sobrescrita.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun inserir(leitura: LeituraEntity): Long

    @Query("SELECT * FROM leitura WHERE sessaoId = :sessaoId ORDER BY lidaEmMillis")
    suspend fun porSessao(sessaoId: String): List<LeituraEntity>

    @Query("SELECT * FROM leitura WHERE sessaoId = :sessaoId ORDER BY lidaEmMillis DESC LIMIT 1")
    suspend fun ultimaDaSessao(sessaoId: String): LeituraEntity?

    @Query("SELECT * FROM leitura WHERE sessaoId = :sessaoId AND tipo = :tipo ORDER BY lidaEmMillis DESC LIMIT 1")
    suspend fun ultimaDoTipo(sessaoId: String, tipo: TipoLeitura): LeituraEntity?

    @Query("SELECT hashEncadeado FROM leitura WHERE sessaoId = :sessaoId ORDER BY lidaEmMillis DESC LIMIT 1")
    suspend fun ultimoHash(sessaoId: String): String?

    @Query("SELECT COUNT(*) FROM leitura WHERE sessaoId = :sessaoId AND tipo = :tipo")
    suspend fun contarDoTipo(sessaoId: String, tipo: TipoLeitura): Int

    @Query("SELECT * FROM leitura WHERE sincronizada = 0")
    suspend fun naoSincronizadas(): List<LeituraEntity>

    /** Quantas leituras nunca sairam do aparelho num laudo. Ver [LeituraEntity.exportadaEmMillis]. */
    @Query("SELECT COUNT(*) FROM leitura WHERE exportadaEmMillis IS NULL")
    fun contarNaoExportadas(): kotlinx.coroutines.flow.Flow<Int>

    @Query("UPDATE leitura SET exportadaEmMillis = :emMillis WHERE id IN (:ids) AND exportadaEmMillis IS NULL")
    suspend fun marcarExportadas(ids: List<String>, emMillis: Long)

    @Query("UPDATE leitura SET sincronizada = 1 WHERE id = :id")
    suspend fun marcarSincronizada(id: String)

    /** O outbox conhece a leitura pela chave de idempotencia, nao pelo id. */
    @Query("UPDATE leitura SET sincronizada = 1 WHERE chaveIdempotencia = :chave")
    suspend fun marcarSincronizadaPorChave(chave: String)
}

@Dao
interface CustodiaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun inserir(evento: CustodiaEntity)

    @Query("SELECT * FROM custodia WHERE remessaId = :remessaId ORDER BY ocorridoEmMillis")
    suspend fun porRemessa(remessaId: String): List<CustodiaEntity>
}

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enfileirar(item: OutboxEntity)

    @Query("SELECT * FROM outbox ORDER BY criadoEmMillis LIMIT :limite")
    suspend fun pendentes(limite: Int = 50): List<OutboxEntity>

    @Query("SELECT COUNT(*) FROM outbox")
    fun observarPendentes(): Flow<Int>

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun remover(id: Long)

    @Query("UPDATE outbox SET tentativas = tentativas + 1, ultimoErro = :erro WHERE id = :id")
    suspend fun registrarFalha(id: Long, erro: String)
}
