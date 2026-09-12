package com.thermotrace.app.ui.components

import android.nfc.NfcAdapter
import android.util.Log
import com.journeyapps.barcodescanner.CaptureActivity

/** Mantém a câmera em primeiro plano mesmo com a etiqueta NFC atrás do celular. Não envia comandos à etiqueta. */
class CapturaDocumentoActivity : CaptureActivity() {
    private var leitor: NfcAdapter? = null
    override fun onResume() {
        super.onResume()
        leitor = NfcAdapter.getDefaultAdapter(this)
        runCatching {
            leitor?.enableReaderMode(this, { }, NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null)
        }.onFailure { Log.w("ThermoTraceScanner", "Não foi possível manter a captura NFC passiva", it) }
    }
    override fun onPause() {
        runCatching { leitor?.disableReaderMode(this) }
        super.onPause()
    }
}
