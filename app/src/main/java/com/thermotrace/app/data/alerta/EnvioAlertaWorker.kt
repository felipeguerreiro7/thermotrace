package com.thermotrace.app.data.alerta

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.thermotrace.app.ThermoTraceApp
import com.thermotrace.app.data.db.OutboxEntity
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Sobe o outbox para o servidor quando houver rede.
 *
 * O envio do e-mail é do backend, não do aparelho. Colocar credencial de SMTP
 * dentro de um APK entrega a caixa de e-mail da empresa para qualquer pessoa
 * que descompacte o arquivo — e o app é instalado em celular de galpão, que
 * troca de mão. O aparelho enfileira; o servidor envia e responde quem
 * recebeu.
 *
 * Enquanto não houver backend configurado, o item continua na fila e o alerta
 * pode sair pelo app de e-mail do operador (ver `AlertaEmail.intentEmail`).
 * Nada se perde por não haver servidor ainda.
 */
class EnvioAlertaWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as ThermoTraceApp
        val baseUrl = app.urlServidor()
            ?: return Result.success()   // sem servidor: fila permanece intacta

        val fila = app.alertas
        val pendentes = fila.pendentesDeEnvio()
        if (pendentes.isEmpty()) return Result.success()

        var houveFalha = false
        for (item in pendentes) {
            when (enviar(baseUrl, item)) {
                Envio.OK -> fila.confirmarEnvio(item)
                Envio.PERMANENTE -> fila.registrarFalhaEnvio(item, "rejeitado pelo servidor")
                Envio.TEMPORARIO -> {
                    fila.registrarFalhaEnvio(item, "sem resposta do servidor")
                    houveFalha = true
                }
            }
        }
        return if (houveFalha) Result.retry() else Result.success()
    }

    private enum class Envio { OK, TEMPORARIO, PERMANENTE }

    private fun enviar(baseUrl: String, item: OutboxEntity): Envio = try {
        val conexao = (URL("$baseUrl/${item.endpoint}").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            // O servidor deduplica por esta chave. Reenviar é seguro por
            // construção, o que permite retry agressivo sem medo.
            setRequestProperty("Idempotency-Key", item.chaveIdempotencia)
        }
        conexao.outputStream.use { it.write(item.corpoJson.toByteArray(Charsets.UTF_8)) }

        val codigo = conexao.responseCode
        conexao.disconnect()

        when {
            codigo in 200..299 -> Envio.OK
            codigo == 409 -> Envio.OK          // já recebido antes; idempotência funcionou
            codigo in 400..499 -> Envio.PERMANENTE
            else -> Envio.TEMPORARIO
        }
    } catch (e: Exception) {
        Envio.TEMPORARIO
    }

    companion object {
        private const val NOME = "thermotrace-outbox"

        fun agendar(context: Context) {
            val pedido = OneTimeWorkRequestBuilder<EnvioAlertaWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(NOME, ExistingWorkPolicy.APPEND_OR_REPLACE, pedido)
        }
    }
}
