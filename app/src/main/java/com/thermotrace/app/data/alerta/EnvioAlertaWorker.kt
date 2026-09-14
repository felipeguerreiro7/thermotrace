package com.thermotrace.app.data.alerta

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Compatibilidade com trabalhos persistidos pelos APKs anteriores.
 * O formato legado não contém vínculo autenticado empresa/operador/volume e
 * não é o contrato da API 0.4. Não transmitir nem retirar evidências da fila
 * com base em HTTP 2xx/409. A ingestão precisa de recibo validado e persistido.
 * Result.success conclui somente o agendamento antigo, sem alterar o outbox.
 */
class EnvioAlertaWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = Result.success()

    companion object {
        /** A fila permanece local até a integração autenticada da API 0.4. */
        @Suppress("UNUSED_PARAMETER")
        fun agendar(context: Context) = Unit
    }
}
