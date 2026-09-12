package com.thermotrace.app.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.thermotrace.app.domain.DocumentoFiscal
import com.thermotrace.app.domain.LeitorDocumentoFiscal

/**
 * Leitura do documento fiscal por câmera.
 *
 * A DANFE traz duas coisas legíveis: o QR (URL da SEFAZ com a chave embutida)
 * e o código de barras CODE-128 com os 44 dígitos da chave. O CT-e segue o
 * mesmo padrão; o AWB aéreo costuma vir em CODE-128 de 11 dígitos.
 *
 * Aceitamos os três formatos porque, na doca, o que estiver legível é o que
 * vai ser lido — e forçar o operador a achar justo o QR é o tipo de exigência
 * que faz ele voltar a digitar.
 */
class ScannerDocumento internal constructor(
    private val abrir: (ScanOptions) -> Unit,
) {
    fun escanear() {
        abrir(
            ScanOptions().apply {
                setDesiredBarcodeFormats(
                    ScanOptions.QR_CODE,
                    ScanOptions.CODE_128,
                    ScanOptions.DATA_MATRIX,
                    ScanOptions.PDF_417,
                )
                setPrompt("Aponte para o QR ou o código de barras da DANFE")
                setBeepEnabled(true)
                setOrientationLocked(false)
                setCaptureActivity(CapturaDocumentoActivity::class.java)
            }
        )
    }
}

/**
 * @param aoLer recebe o documento já interpretado e validado offline.
 *        O conteúdo bruto vai junto — nunca descartamos o que a câmera leu.
 */
@Composable
fun rememberScannerDocumento(aoLer: (DocumentoFiscal) -> Unit): ScannerDocumento {
    val launcher = rememberLauncherForActivityResult(ScanContract()) { resultado ->
        val bruto = resultado.contents ?: return@rememberLauncherForActivityResult
        val simbologia = resultado.formatName ?: "DESCONHECIDO"
        aoLer(LeitorDocumentoFiscal.interpretar(bruto, simbologia))
    }
    return remember { ScannerDocumento { opcoes -> launcher.launch(opcoes) } }
}

/** Mesma leitura, mas para o código impresso na etiqueta (não é fiscal). */
@Composable
fun rememberScannerEtiqueta(aoLer: (String) -> Unit): ScannerDocumento {
    val launcher = rememberLauncherForActivityResult(ScanContract()) { resultado ->
        resultado.contents?.let(aoLer)
    }
    return remember { ScannerDocumento { opcoes -> launcher.launch(opcoes) } }
}
