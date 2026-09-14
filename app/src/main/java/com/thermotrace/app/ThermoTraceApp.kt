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
import com.thermotrace.app.data.conta.EscopoLocal

class DadosLocais(
    val escopo: EscopoLocal, val db: ThermoTraceDb, val repositorio: Repositorio,
    val alertas: RepositorioAlertas, val fluxo: FluxoRapido, val preferencias: Preferencias,
    val envio: com.thermotrace.app.data.conta.EnvioColetas,
)

/**
 * Container mínimo de dependências.
 *
 * Sem framework de injeção de propósito: o app tem um grafo pequeno e uma
 * biblioteca de DI aqui só adicionaria build time e uma camada a mais para
 * depurar quando o NFC não responder em campo.
 */
class ThermoTraceApp : Application() {
    val conta by lazy { RepositorioConta(CofreAndroidConta(this), TransporteHttpsConta()) }

    /** Ajustes do aparelho. Ver [Preferencias]. */
    lateinit var preferencias: Preferencias
        private set

    /** Identidade da instalação. Não é IMEI nem nada ligado à pessoa (LGPD). */
    lateinit var installId: String
        private set
    private lateinit var metadadosEnvio: org.json.JSONObject

    override fun onCreate() {
        super.onCreate()

        val prefs = getSharedPreferences("thermotrace", Context.MODE_PRIVATE)
        installId = prefs.getString(CHAVE_INSTALL_ID, null) ?: UUID.randomUUID().toString()
            .also { prefs.edit().putString(CHAVE_INSTALL_ID, it).apply() }

        // A mesma instalação deve ter o mesmo cadastro ao trocar de operador ou atualizar o app.
        val original = prefs.getString("metadados_instalacao_envio_v1", null)
        metadadosEnvio = if(original != null) org.json.JSONObject(original) else org.json.JSONObject()
            .put("modelo", android.os.Build.MODEL).put("so", android.os.Build.VERSION.RELEASE)
            .put("versao", versaoLegivel()).also {
                check(prefs.edit().putString("metadados_instalacao_envio_v1",it.toString()).commit()) {
                    "Não foi possível preservar a identidade da instalação."
                }
            }
        preferencias = Preferencias(this)
    }

    /** Um grafo fixo por contexto: nenhuma referência troca de empresa em uma operação em andamento. */
    fun abrirDados(escopo: EscopoLocal): DadosLocais {
        val db = ThermoTraceDb.abrir(this, escopo)
        val repositorio = Repositorio(
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
        val alertas = RepositorioAlertas(
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
        return DadosLocais(escopo, db, repositorio, alertas,
            FluxoRapido(repositorio, alertas, db), Preferencias(this, escopo.preferencias),
            com.thermotrace.app.data.conta.EnvioColetas(db, conta, escopo, installId,
                metadadosEnvio.getString("modelo"), metadadosEnvio.getString("so"), metadadosEnvio.getString("versao")))
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
