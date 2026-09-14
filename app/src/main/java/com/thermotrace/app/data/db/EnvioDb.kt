package com.thermotrace.app.data.db

import androidx.room.*

/** Associação explícita, fixa antes do primeiro envio; não altera a remessa de campo. */
@Entity(tableName = "vinculo_envio", foreignKeys = [ForeignKey(entity = SessaoEntity::class,
    parentColumns = ["id"], childColumns = ["sessaoId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["volumeOnline"], unique = true)])
data class VinculoEnvio(
    @PrimaryKey val sessaoId: String, val escopo: String, val empresaId: String, val usuarioId: String,
    val remessaOnline: String, val volumeOnline: String, val codigoCarga: String, val sequenciaVolume: Int,
    val etiquetaOnline: String, val uid: String, val dispositivoId: String, val vinculadoEm: Long,
)

/** Corpo exato e recibo sobrevivem ao processo. Recebidos são preservados para consulta. */
@Entity(tableName = "pedido_envio", foreignKeys = [ForeignKey(entity = VinculoEnvio::class,
    parentColumns = ["sessaoId"], childColumns = ["sessaoId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("sessaoId")])
data class PedidoEnvio(
    @PrimaryKey val eventoId: String, val sessaoId: String, val chaveLocal: String, val tipo: String,
    val caminho: String, val corpo: String, val sha256: String, val chave: String, val ordem: Long,
    val recibo: String? = null, val tentativas: Int = 0, val erro: String? = null,
)

/** Registro do aparelho também precisa poder repetir bytes idênticos após perda da resposta. */
@Entity(tableName = "aparelho_envio")
data class AparelhoEnvio(@PrimaryKey val id: Int = 1, val installId: String, val corpo: String,
    val chave: String, val dispositivoId: String? = null)

@Dao
interface EnvioDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun vincular(v: VinculoEnvio)
    @Query("SELECT * FROM vinculo_envio WHERE sessaoId = :id") suspend fun vinculo(id: String): VinculoEnvio?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun preparar(p: PedidoEnvio)
    @Query("SELECT * FROM pedido_envio WHERE sessaoId = :id ORDER BY ordem, eventoId")
    suspend fun pedidos(id: String): List<PedidoEnvio>
    @Query("UPDATE pedido_envio SET recibo = :recibo, erro = NULL WHERE eventoId = :id AND recibo IS NULL")
    suspend fun confirmar(id: String, recibo: String): Int
    @Query("UPDATE pedido_envio SET tentativas = tentativas + 1, erro = :erro WHERE eventoId = :id AND recibo IS NULL")
    suspend fun falha(id: String, erro: String)
    @Query("SELECT * FROM aparelho_envio WHERE id = 1") suspend fun aparelho(): AparelhoEnvio?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun prepararAparelho(a: AparelhoEnvio)
    @Query("UPDATE aparelho_envio SET dispositivoId = :id WHERE id = 1 AND dispositivoId IS NULL")
    suspend fun confirmarAparelho(id: String)
}
