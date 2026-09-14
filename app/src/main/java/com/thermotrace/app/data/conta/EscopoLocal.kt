package com.thermotrace.app.data.conta

import java.security.MessageDigest
import java.util.UUID

/** A origem faz parte da identidade: homologação e produção nunca compartilham o banco. */
@ConsistentCopyVisibility
data class EscopoLocal private constructor(val chave: String, val vinculado: Boolean) {
    val banco get() = if (vinculado) "thermotrace-$chave.db" else "thermotrace.db"
    val preferencias get() = if (vinculado) "thermotrace-$chave" else "thermotrace"

    companion object {
        val LEGADO = EscopoLocal("legado", false)

        fun de(sessao: SessaoConta?): EscopoLocal {
            if (sessao == null) return LEGADO
            fun uuid(valor: String): String {
                val normal = UUID.fromString(valor).toString()
                require(normal.equals(valor, ignoreCase = true))
                return normal
            }
            val origem = EnderecoConta.normalizar(sessao.endereco).removeSuffix(":443")
            val identidade = listOf("tt-local-1", origem, uuid(sessao.empresaId), uuid(sessao.usuarioId)).joinToString("\n")
            val hash = MessageDigest.getInstance("SHA-256").digest(identidade.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return EscopoLocal(hash, true)
        }
    }
}
