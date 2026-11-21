package com.thermotrace.app.data.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.thermotrace.app.data.conta.RelatorioViagem
import java.io.File

/**
 * Relatório da viagem emitido pelo servidor, para quem entrega mandar a quem recebe
 * (hospital, farmácia, fiscal). Mesma pasta e mesmo FileProvider do laudo local.
 */
object CompartilharViagem {
    fun mensagem(codigoCarga: String, link: String?): String =
        "Relatório de temperatura da carga $codigoCarga (ThermoTrace)." +
            (link?.let { "\nPara conferir se o relatório é autêntico, sem login: $it" } ?: "")

    fun intent(context: Context, relatorio: RelatorioViagem, codigoCarga: String): Intent {
        val pasta = File(context.cacheDir, "laudos").apply { mkdirs() }
        val codigoSeguro = codigoCarga.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64)
        // Arquivo novo a cada envio: nunca sobrescrever um relatório que já saiu do celular.
        val arquivo = File.createTempFile("viagem_${codigoSeguro}_", ".html", pasta)
        arquivo.writeText(relatorio.html, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", arquivo)
        return Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/html"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Relatório da viagem $codigoCarga")
            putExtra(Intent.EXTRA_TEXT, mensagem(codigoCarga, relatorio.linkConferencia))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Compartilhar relatório")
    }
}
