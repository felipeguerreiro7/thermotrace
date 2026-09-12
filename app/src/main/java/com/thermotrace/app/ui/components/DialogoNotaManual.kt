package com.thermotrace.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.input.KeyboardType
import com.thermotrace.app.domain.DocumentoFiscal
import com.thermotrace.app.domain.DocumentoManual

@Composable
fun DialogoNotaManual(aoFechar: () -> Unit, aoSalvar: (DocumentoFiscal) -> Unit) {
    var valor by remember { mutableStateOf("") }
    var chave by remember { mutableStateOf(false) }
    var erro by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = aoFechar, title = { Text("Digitar nota") }, text = {
        Column {
            Text("O documento ficará identificado como digitado manualmente.")
            TextButton(onClick = { chave = !chave; valor = ""; erro = null }) {
                Text(if (chave) "Usar apenas o número da nota" else "Usar chave completa de 44 dígitos")
            }
            OutlinedTextField(valor, { valor = it.take(80); erro = null }, singleLine = true,
                label = { Text(if (chave) "Chave de acesso" else "Número da nota") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = erro != null, supportingText = { erro?.let { Text(it) } })
        }
    }, confirmButton = {
        TextButton(enabled = valor.isNotBlank(), onClick = {
            runCatching { DocumentoManual.criar(valor, chave) }
                .onSuccess { aoSalvar(it); aoFechar() }.onFailure { erro = it.message }
        }) { Text("Vincular nota") }
    }, dismissButton = { TextButton(onClick = aoFechar) { Text("Cancelar") } })
}
