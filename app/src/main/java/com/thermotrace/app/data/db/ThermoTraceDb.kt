package com.thermotrace.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        RemessaEntity::class,
        DocumentoEntity::class,
        VolumeEntity::class,
        EtiquetaEntity::class,
        SessaoEntity::class,
        LeituraEntity::class,
        CustodiaEntity::class,
        OutboxEntity::class,
        OcorrenciaEntity::class,
        AcaoCorretivaEntity::class,
        DestinatarioAlertaEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
@TypeConverters(Conversores::class, ConversoresAlerta::class)
abstract class ThermoTraceDb : RoomDatabase() {

    abstract fun remessaDao(): RemessaDao
    abstract fun documentoDao(): DocumentoDao
    abstract fun volumeDao(): VolumeDao
    abstract fun etiquetaDao(): EtiquetaDao
    abstract fun sessaoDao(): SessaoDao
    abstract fun leituraDao(): LeituraDao
    abstract fun custodiaDao(): CustodiaDao
    abstract fun outboxDao(): OutboxDao
    abstract fun ocorrenciaDao(): OcorrenciaDao
    abstract fun acaoCorretivaDao(): AcaoCorretivaDao
    abstract fun destinatarioAlertaDao(): DestinatarioAlertaDao

    companion object {

        /**
         * v1 → v2: identidade pelo documento fiscal, verificação de etiqueta
         * ativa, ocorrências e alertas.
         *
         * Migração escrita à mão de propósito. `fallbackToDestructiveMigration`
         * apagaria evidência de auditoria numa atualização de app — o que num
         * sistema de cadeia fria é o mesmo que destruir a prova.
         */
        /**
         * Localização da coleta (TT-047).
         *
         * Colunas anuláveis e nenhum valor retroativo: leitura antiga não tem
         * localização, e preencher com zero ou com a posição de hoje seria
         * inventar evidência. Ausência explícita é informação; chute não é.
         */
        /** Marca de exportacao por leitura (TT-054b). Sem valor retroativo: leitura
         *  antiga nao tem como saber se ja saiu do aparelho, e supor que saiu seria
         *  exatamente o engano que este campo existe para evitar. */
        /** Confirmacao do STOP fisico (TT-055). Sem valor retroativo: sessao
         *  antiga nao tem como saber se a etiqueta chegou a parar, e supor que
         *  parou seria exatamente o engano que esta coluna existe para evitar. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Não confiar retroativamente no marcador antigo, que era apenas cache.
                db.execSQL("ALTER TABLE leitura ADD COLUMN copiaConfirmadaEmMillis INTEGER")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessao ADD COLUMN loggerParadoEmMillis INTEGER")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE leitura ADD COLUMN exportadaEmMillis INTEGER")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE leitura ADD COLUMN latitude REAL")
                db.execSQL("ALTER TABLE leitura ADD COLUMN longitude REAL")
                db.execSQL("ALTER TABLE leitura ADD COLUMN precisaoMetros REAL")
                db.execSQL("ALTER TABLE leitura ADD COLUMN provedorLocal TEXT")
                db.execSQL("ALTER TABLE leitura ADD COLUMN localizadoEmMillis INTEGER")
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ---- identidade pelo documento fiscal ----------------------
                db.execSQL("ALTER TABLE remessa ADD COLUMN identidadeDocumento TEXT")
                db.execSQL("ALTER TABLE volume ADD COLUMN identidade TEXT")
                db.execSQL("ALTER TABLE documento ADD COLUMN chaveAcesso TEXT")
                db.execSQL("ALTER TABLE documento ADD COLUMN cnpjEmitente TEXT")
                db.execSQL("ALTER TABLE documento ADD COLUMN serie TEXT")
                db.execSQL("ALTER TABLE documento ADD COLUMN ufEmitente TEXT")
                db.execSQL("ALTER TABLE documento ADD COLUMN competencia TEXT")
                db.execSQL("ALTER TABLE documento ADD COLUMN validado INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE documento ADD COLUMN observacaoValidacao TEXT")

                // ---- etiqueta ativa ---------------------------------------
                db.execSQL("ALTER TABLE sessao ADD COLUMN verificadoEmMillis INTEGER")
                db.execSQL("ALTER TABLE sessao ADD COLUMN registrandoNaVerificacao INTEGER")

                // ---- ocorrências e alertas --------------------------------
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ocorrencia (
                        id TEXT NOT NULL PRIMARY KEY,
                        remessaId TEXT NOT NULL,
                        volumeId TEXT,
                        sessaoId TEXT,
                        chaveNatural TEXT NOT NULL,
                        tipo TEXT NOT NULL,
                        gravidade TEXT,
                        status TEXT NOT NULL,
                        titulo TEXT NOT NULL,
                        detalhe TEXT,
                        ocorridoEmMillis INTEGER NOT NULL,
                        detectadoEmMillis INTEGER NOT NULL,
                        registradoEmMillis INTEGER NOT NULL,
                        picoC REAL,
                        limiteC REAL,
                        duracaoSegundos INTEGER,
                        versaoRegra TEXT NOT NULL,
                        alertaEnviadoEmMillis INTEGER,
                        alertaDestinatarios TEXT,
                        alertaCanal TEXT,
                        fechadaEmMillis INTEGER,
                        FOREIGN KEY(remessaId) REFERENCES remessa(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ocorrencia_remessaId ON ocorrencia(remessaId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ocorrencia_sessaoId ON ocorrencia(sessaoId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_ocorrencia_chaveNatural ON ocorrencia(chaveNatural)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS acao_corretiva (
                        id TEXT NOT NULL PRIMARY KEY,
                        ocorrenciaId TEXT NOT NULL,
                        tipo TEXT NOT NULL,
                        descricao TEXT,
                        ocorridoEmMillis INTEGER NOT NULL,
                        registradoEmMillis INTEGER NOT NULL,
                        origem TEXT NOT NULL,
                        executadaPor TEXT,
                        empresa TEXT,
                        evidenciaUri TEXT,
                        sincronizada INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(ocorrenciaId) REFERENCES ocorrencia(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_acao_corretiva_ocorrenciaId ON acao_corretiva(ocorrenciaId)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS destinatario_alerta (
                        id TEXT NOT NULL PRIMARY KEY,
                        nome TEXT NOT NULL,
                        email TEXT NOT NULL,
                        papel TEXT NOT NULL,
                        gravidadeMinima TEXT NOT NULL,
                        ativo INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_destinatario_alerta_email ON destinatario_alerta(email)")
            }
        }

        fun abrir(context: Context, escopo: com.thermotrace.app.data.conta.EscopoLocal): ThermoTraceDb =
                Room.databaseBuilder(
                    context.applicationContext,
                    ThermoTraceDb::class.java,
                    escopo.banco,
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .build()
    }
}
