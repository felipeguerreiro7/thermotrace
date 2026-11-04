package com.thermotrace.app.data.conta

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.thermotrace.app.ThermoTraceApp
import com.thermotrace.app.data.db.ThermoTraceDb
import java.util.concurrent.TimeUnit

/**
 * Fila automática de envio (Android 0.8.8).
 *
 * Usa exatamente o caminho do botão "Enviar coletas": mesmos pedidos preservados,
 * mesmas chaves, recibo conferido e gravado antes de marcar a coleta. Só envia
 * sessões que o operador já vinculou a carga/volume; não escolhe destino sozinho.
 * Sem conta conectada, ou com login expirado, termina sem tentar de novo: a próxima
 * entrada na conta agenda outra rodada.
 */
class EnvioAutomaticoWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as ThermoTraceApp
        val escopo = app.conta.escopo.value
        if (!escopo.vinculado) return Result.success()
        // Instância própria do banco: a tela pode fechar a dela a qualquer momento.
        val db = ThermoTraceDb.abrir(applicationContext, escopo)
        return try {
            val r = app.novoEnvio(db, escopo).enviarPendentes()
            when {
                r.loginNecessario -> Result.success()
                r.sessoesComFalha.isNotEmpty() && runAttemptCount < MAX_TENTATIVAS -> Result.retry()
                else -> Result.success()
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { if (runAttemptCount < MAX_TENTATIVAS) Result.retry() else Result.success() }
        finally { db.close() }
    }

    companion object {
        private const val UNICO = "tt-envio-agora"
        private const val PERIODICO = "tt-envio-periodico"
        private const val MAX_TENTATIVAS = 8
        private val rede = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Logo após uma coleta, um vínculo ou a entrada na conta; espera haver internet. */
        fun agendarAgora(context: Context) {
            val pedido = OneTimeWorkRequestBuilder<EnvioAutomaticoWorker>().setConstraints(rede)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            // APPEND_OR_REPLACE: uma coleta nova durante um envio garante outra rodada depois dele.
            WorkManager.getInstance(context).enqueueUniqueWork(UNICO, ExistingWorkPolicy.APPEND_OR_REPLACE, pedido)
        }

        /** Rede de segurança para coletas que ficaram para trás (menor intervalo permitido: 15 min). */
        fun agendarPeriodico(context: Context) {
            val pedido = PeriodicWorkRequestBuilder<EnvioAutomaticoWorker>(15, TimeUnit.MINUTES).setConstraints(rede).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODICO, ExistingPeriodicWorkPolicy.KEEP, pedido)
        }
    }
}
