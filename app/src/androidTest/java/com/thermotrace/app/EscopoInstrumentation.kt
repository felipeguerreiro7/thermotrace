package com.thermotrace.app

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.thermotrace.app.data.conta.EscopoLocal
import com.thermotrace.app.data.conta.SessaoConta
import com.thermotrace.app.data.db.OutboxEntity
import com.thermotrace.app.data.db.ThermoTraceDb
import com.thermotrace.app.data.prefs.Preferencias
import kotlinx.coroutines.runBlocking
import java.util.UUID

/** Ensaio real de Room/SharedPreferences com nomes aleatórios; não toca dados do usuário. */
class EscopoInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val resultado = Bundle()
        val escopos = mutableListOf<EscopoLocal>()
        val abertos = mutableListOf<ThermoTraceDb>()
        var codigo = Activity.RESULT_OK
        try {
            val primeira = SessaoConta("https://synthetic.example.test", "sintetico", "sintetico",
                UUID.randomUUID().toString(), UUID.randomUUID().toString())
            escopos += listOf(primeira, primeira.copy(empresaId=UUID.randomUUID().toString()),
                primeira.copy(usuarioId=UUID.randomUUID().toString()),
                primeira.copy(endereco="https://other-synthetic.example.test")).map(EscopoLocal::de)
            runBlocking {
                val original=ThermoTraceDb.abrir(targetContext,escopos[0]).also(abertos::add)
                original.outboxDao().enfileirar(OutboxEntity(chaveIdempotencia="teste-escopo",
                    endpoint="leituras",corpoJson="{\"sintetico\":true}",criadoEmMillis=1L))
                val ajuste=Preferencias(targetContext,escopos[0].preferencias)
                ajuste.definirUrlServidor("https://synthetic.example.test")
                for(escopo in escopos.drop(1)) {
                    val separado=ThermoTraceDb.abrir(targetContext,escopo).also(abertos::add)
                    check(separado.outboxDao().pendentes().isEmpty()) { "Fila vazou entre escopos" }
                    check(Preferencias(targetContext,escopo.preferencias).atual.urlServidor==null) { "Ajuste vazou" }
                }
                original.close(); abertos.remove(original)
                val reaberto=ThermoTraceDb.abrir(targetContext,escopos[0]).also(abertos::add)
                val fila=reaberto.outboxDao().pendentes()
                check(fila.size==1 && fila.single().corpoJson=="{\"sintetico\":true}") { "Fila não sobreviveu à reabertura" }
                check(Preferencias(targetContext,escopos[0].preferencias).atual.urlServidor=="https://synthetic.example.test")
            }
            resultado.putString("stream", "PASS: isolamento por empresa, operador e servidor; fila e ajustes preservados após reabrir.\n")
        } catch(e:Throwable) {
            resultado.putString("stream", "FAIL: ${e.javaClass.simpleName}: ${e.message}\n")
            codigo = Activity.RESULT_CANCELED
        } finally {
            abertos.forEach { it.close() }
            escopos.forEach { targetContext.deleteDatabase(it.banco); targetContext.deleteSharedPreferences(it.preferencias) }
        }
        finish(codigo,resultado)
    }
}
