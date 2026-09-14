package com.thermotrace.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.thermotrace.app.data.conta.EscopoLocal
import com.thermotrace.app.data.conta.SessaoConta
import com.thermotrace.app.data.db.ThermoTraceDb
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.util.UUID

object MigracoesInstrumentadas {
    fun executar(aplicativo: Context, testes: Context) = runBlocking {
        for (versao in 2..6) {
            val fixture=JSONObject(testes.assets.open("migracoes/v$versao.json").bufferedReader().use { it.readText() })
            val tabelas=fixture.getJSONArray("tables")
            val escopo=EscopoLocal.de(SessaoConta("https://migration.example.test","sintetico","sintetico",
                UUID.randomUUID().toString(),UUID.randomUUID().toString()))
            val arquivo=aplicativo.getDatabasePath(escopo.banco)
            arquivo.parentFile!!.mkdirs()
            val esperado=mutableMapOf<String,List<String?>>()
            try {
                SQLiteDatabase.openOrCreateDatabase(arquivo,null).use { antigo ->
                    for (i in 0 until tabelas.length()) {
                        val t=tabelas.getJSONObject(i)
                        antigo.execSQL(t.getString("sql"))
                        val indices=t.getJSONArray("indices")
                        for(j in 0 until indices.length()) antigo.execSQL(indices.getString(j))
                        val campos=t.getJSONArray("fields")
                        val nomes=(0 until campos.length()).map { campos.getJSONObject(it).getString("columnName") }
                        val valores=(0 until campos.length()).map {
                            val f=campos.getJSONObject(it)
                            when {
                                f.getString("columnName")=="id" && f.getString("affinity")=="INTEGER" -> 1
                                f.getString("affinity")=="TEXT" -> "sintetico"
                                else -> 777
                            }
                        }.toTypedArray<Any>()
                        antigo.execSQL("INSERT INTO `${t.getString("name")}` (${nomes.joinToString { "`$it`" }}) VALUES (${nomes.joinToString { "?" }})",valores)
                        antigo.rawQuery("SELECT ${nomes.joinToString { "`$it`" }} FROM `${t.getString("name")}`",null).use { c ->
                            check(c.moveToFirst()); esperado[t.getString("name")]=nomes.indices.map { c.getString(it) }
                        }
                    }
                    antigo.version=versao
                }
                val atualizado=ThermoTraceDb.abrir(aplicativo,escopo)
                try {
                    // A abertura aciona a sequência de migrações e a validação estrutural do Room.
                    val banco=atualizado.openHelper.writableDatabase
                    check(banco.version==7)
                    for(i in 0 until tabelas.length()) {
                        val t=tabelas.getJSONObject(i);val campos=t.getJSONArray("fields")
                        val nomes=(0 until campos.length()).map { campos.getJSONObject(it).getString("columnName") }
                        banco.query("SELECT ${nomes.joinToString { "`$it`" }} FROM `${t.getString("name")}`").use { c ->
                            check(c.moveToFirst());check(nomes.indices.map { c.getString(it) }==esperado[t.getString("name")]) { "Dados alterados na migração $versao" }
                        }
                    }
                    if(versao<6) banco.query("SELECT copiaConfirmadaEmMillis FROM leitura").use { c -> check(c.moveToFirst() && c.isNull(0)) }
                    check(atualizado.outboxDao().pendentes().size==1)
                    if(versao<5) {
                        check(atualizado.sessaoDao().confirmarPrimeiroStop("sintetico",1000)==1)
                        check(atualizado.sessaoDao().confirmarPrimeiroStop("sintetico",2000)==0)
                        banco.query("SELECT loggerParadoEmMillis FROM sessao").use { c -> check(c.moveToFirst() && c.getLong(0)==1000L) }
                    }
                } finally { atualizado.close() }
            } finally { aplicativo.deleteDatabase(escopo.banco) }
        }
    }
}
