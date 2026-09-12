package com.thermotrace.app

import android.app.Application
import android.content.Context
import com.thermotrace.app.data.db.ThermoTraceDb
import com.thermotrace.app.data.prefs.Preferencias
import com.thermotrace.app.data.repo.FluxoRapido
import com.thermotrace.app.data.repo.RepositorioAlertas
import com.thermotrace.app.data.repo.Repositorio
import java.util.UUID
import com.thermotrace.app.data.conta.CofreAndroidConta
import com.thermotrace.app.data.conta.RepositorioConta
import com.thermotrace.app.data.conta.TransporteHttpsConta

/**
 * Container mínimo de dependências.
 *
 * Sem framework de injeção de propósito: o app tem um grafo pequeno e uma
 * biblioteca de DI aqui só adicionaria build time e uma camada a mais para
 * depurar quando o NFC não responder em campo.
 */
class ThermoTraceApp : Application() {
    val conta by lazy { RepositorioConta(CofreAndroidConta(this), TransporteHttpsConta()) }

    lateinit var repositorio: Repositorio
        private set

    lateinit var alertas: RepositorioAlertas
        private set

    /**
     * A sequencia "etiqueta respondeu -> gravado e alertado".
     *
     * Compartilhada entre o bipe rapido da tela inicial e a tela de Leitura:
     * sao dois caminhos gravando evidencia de auditoria, e eles nao podem
     * divergir. Ver [FluxoRapido].
     */
    lateinit var fluxo: FluxoRapido
        private set

    /** Ajustes do aparelho. Ver [Preferencias]. */
    lateinit var preferencias: Preferencias
        private set

    /** Identidade da instalação. Não é IMEI nem nada ligado à pessoa (LGPD). */
    lateinit var installId: String
        private set

    override fun onCreate() {
        super.onCreate()

        val prefs = getSharedPreferences("thermotrace", Context.MODE_PRIVATE)
        installId = prefs.getString(CHAVE_INSTALL_ID, null) ?: UUID.randomUUID().toString()
            .also { prefs.edit().putString(CHAVE_INSTALL_ID, it).apply() }

        preferencias = Preferencias(this)

        val db = ThermoTraceDb.obter(this)
        repositorio = Repositorio(
            remessaDao = db.remessaDao(),
            documentoDao = db.documentoDao(),
            volumeDao = db.volumeDao(),
            etiquetaDao = db.etiquetaDao(),
            sessaoDao = db.sessaoDao(),
            leituraDao = db.leituraDao(),
            custodiaDao = db.custodiaDao(),
            outboxDao = db.outboxDao(),
            installId = installId,
        )
        alertas = RepositorioAlertas(
            ocorrenciaDao = db.ocorrenciaDao(),
            acaoDao = db.acaoCorretivaDao(),
            destinatarioDao = db.destinatarioAlertaDao(),
            outboxDao = db.outboxDao(),
            sessaoDao = db.sessaoDao(),
            volumeDao = db.volumeDao(),
            remessaDao = db.remessaDao(),
            documentoDao = db.documentoDao(),
            etiquetaDao = db.etiquetaDao(),
            leituraDao = db.leituraDao(),
        )
        fluxo = FluxoRapido(repositorio, alertas, db)
    }

    /**
     * URL do backend que envia os e-mails de alerta.
     *
     * Nula enquanto o servidor não existir — e isso não é um estado de erro:
     * o outbox segue acumulando e o operador ainda pode disparar o alerta
     * pelo próprio app de e-mail. Nada se perde.
     *
     * Mora em [Preferencias] desde a v0.4, junto com o resto dos ajustes;
     * estes dois métodos ficam como atalho para quem já dependia deles.
     */
    fun urlServidor(): String? = preferencias.atual.urlServidor

    fun definirUrlServidor(url: String?) = preferencias.definirUrlServidor(url)

    /**
     * Versão do app como o suporte precisa ver.
     *
     * Lida do `PackageManager`, não de uma constante: uma constante escrita à
     * mão diverge do APK instalado na primeira vez que alguém esquece de
     * atualizá-la, e aí o operador reporta uma versão que não existe.
     */
    fun versaoLegivel(): String = runCatching {
        val info = packageManager.getPackageInfo(packageName, 0)
        val codigo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P)
            info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        "${info.versionName} ($codigo)"
    }.getOrDefault("desconhecida")

    companion object {
        private const val CHAVE_INSTALL_ID = "install_id"
    }
}
