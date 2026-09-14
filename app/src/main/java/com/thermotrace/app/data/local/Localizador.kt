package com.thermotrace.app.data.local

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 * Onde o celular estava quando o fix saiu. Nunca "onde a carga está".
 *
 * [idadeSegundos] existe porque um fix de dez minutos atrás é um fato
 * diferente de um fix de agora, e quem lê o laudo precisa distinguir os dois.
 */
data class FixLocal(
    val latitude: Double,
    val longitude: Double,
    val precisaoMetros: Double?,
    val provedor: String,
    val obtidoEmMillis: Long,
) {
    fun idadeSegundos(agoraMillis: Long = System.currentTimeMillis()): Long =
        ((agoraMillis - obtidoEmMillis) / 1000L).coerceAtLeast(0L)
}

/**
 * Localização da coleta, em paralelo ao bipe.
 *
 * **O bipe nunca espera o GPS.** Doca, câmara fria e caminhão são justamente
 * os lugares onde o fix demora ou não vem, e perder evidência térmica por
 * causa de um metadado seria trocar o essencial pelo acessório. Por isso o
 * pedido tem prazo curto e a falha é um resultado normal: devolve `null`, a
 * leitura é gravada assim mesmo e a tela diz "sem localização".
 *
 * Decisão D07 do projeto: Play Services, via `getCurrentLocation`, que a
 * documentação do Android chama de caminho recomendado para um fix novo. O
 * custo aceito é a primeira dependência Google do aplicativo; aparelho sem
 * serviços Google fica sem localização e continua operando inteiro.
 */
class Localizador(private val context: Context) {

    fun temPermissao(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private fun precisaFina(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * @param prazoMillis teto para desistir. Curto de propósito: a coleta é o
     *        que importa, e um fix que não chegou a tempo vira ausência
     *        explícita em vez de fila de espera.
     */
    suspend fun obter(prazoMillis: Long = PRAZO_PADRAO_MS): FixLocal? {
        if (!temPermissao()) return null
        val cliente = runCatching { LocationServices.getFusedLocationProviderClient(context) }
            .getOrElse {
                // Aparelho sem serviços Google. Não é erro de operação.
                Log.i(TAG, "Play Services indisponível; leitura seguirá sem localização")
                return null
            }
        val prioridade =
            if (precisaFina()) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        val cancelamento = CancellationTokenSource()
        return try {
            withTimeout(prazoMillis) {
                suspendCancellableCoroutine { continuacao ->
                    @Suppress("MissingPermission")
                    cliente.getCurrentLocation(prioridade, cancelamento.token)
                        .addOnSuccessListener { local ->
                            continuacao.resume(
                                local?.let {
                                    FixLocal(
                                        latitude = it.latitude,
                                        longitude = it.longitude,
                                        precisaoMetros = if (it.hasAccuracy()) it.accuracy.toDouble() else null,
                                        provedor = it.provider ?: "fused",
                                        // `time` é o instante do fix, não o do
                                        // pedido: é ele que permite dizer a
                                        // idade do ponto no laudo.
                                        obtidoEmMillis = it.time.takeIf { t -> t > 0 }
                                            ?: System.currentTimeMillis(),
                                    )
                                }
                            )
                        }
                        .addOnFailureListener {
                            Log.w(TAG, "Fix indisponível: ${it.message}")
                            continuacao.resume(null)
                        }
                    continuacao.invokeOnCancellation { cancelamento.cancel() }
                }
            }
        } catch (e: TimeoutCancellationException) {
            cancelamento.cancel()
            Log.i(TAG, "Fix não chegou em ${prazoMillis}ms; leitura seguirá sem localização")
            null
        } catch (e: SecurityException) {
            // Permissão revogada entre a checagem e a chamada.
            null
        }
    }

    companion object {
        private const val TAG = "ThermoTraceLocal"
        const val PRAZO_PADRAO_MS = 8_000L
    }
}

/** Texto curto para tela e laudo, com precisão e idade — nunca só a coordenada. */
fun FixLocal?.descricao(agoraMillis: Long = System.currentTimeMillis()): String {
    if (this == null) return "Sem localização registrada nesta coleta."
    val precisao = precisaoMetros?.let { " · precisão ~%.0f m".format(it) } ?: " · precisão desconhecida"
    val idade = idadeSegundos(agoraMillis)
    val quando = when {
        idade < 60 -> "no momento da coleta"
        idade < 3600 -> "fix de ${idade / 60} min antes"
        else -> "fix de ${idade / 3600} h antes"
    }
    return "%.5f, %.5f%s · %s · posição do celular, não da carga".format(latitude, longitude, precisao, quando)
}
