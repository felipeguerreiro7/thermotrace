package com.thermotrace.app.data.conta

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Tokens criptografados; chave no Android Keystore e arquivo fora dos backups. */
class CofreAndroidConta(context: Context) : CofreConta {
    private val arquivo = AtomicFile(File(context.noBackupFilesDir, "conta-v1.bin"))
    private fun chave(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    override fun ler(): SessaoConta? {
        val bytes = try { arquivo.openRead().use { stream ->
            val buffer = ByteArray(32_769); var usados = 0
            while (usados < buffer.size) {
                val n = stream.read(buffer, usados, buffer.size - usados)
                if (n < 0) break
                usados += n
            }
            buffer.copyOf(usados)
        } }
            catch (_: FileNotFoundException) { return null }
        require(bytes.size in 30..32_768 && bytes[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, chave(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        cipher.updateAAD(ALIAS.toByteArray(Charsets.UTF_8))
        val o = JSONObject(String(cipher.doFinal(bytes.copyOfRange(13, bytes.size)), Charsets.UTF_8))
        return SessaoConta(EnderecoConta.normalizar(o.getString("endereco")), o.getString("acesso"),
            o.getString("renovacao"), JsonConta.uuid(o, "usuarioId"), JsonConta.uuid(o, "empresaId"), o.getBoolean("pendente"))
    }
    override fun gravar(sessao: SessaoConta) {
        val o = JSONObject().put("endereco", sessao.endereco).put("acesso", sessao.acesso)
            .put("renovacao", sessao.renovacao).put("usuarioId", sessao.usuarioId).put("empresaId", sessao.empresaId)
            .put("pendente", sessao.renovacaoPendente)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, chave()); cipher.updateAAD(ALIAS.toByteArray(Charsets.UTF_8))
        require(cipher.iv.size == 12)
        val bytes = byteArrayOf(1) + cipher.iv + cipher.doFinal(o.toString().toByteArray(Charsets.UTF_8))
        val stream = arquivo.startWrite()
        try {
            stream.write(bytes)
            stream.fd.sync()
            arquivo.finishWrite(stream)
        } catch (e: Exception) { arquivo.failWrite(stream); throw e }
        // AtomicFile pode apenas registrar uma falha de rename: conferir antes de transmitir refresh.
        if (ler() != sessao) throw IOException("Não foi possível confirmar o armazenamento da sessão.")
    }
    override fun limpar() {
        arquivo.delete()
        val nome = arquivo.baseFile.absolutePath
        if (listOf(nome, "$nome.bak", "$nome.new").any { File(it).exists() })
            throw IOException("Não foi possível remover a sessão deste aparelho.")
    }
    companion object { private const val ALIAS = "thermotrace.conta.v1" }
}
