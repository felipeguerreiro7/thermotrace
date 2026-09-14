package com.thermotrace.app.data.conta

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID

class RepositorioConta(cofreOriginal: CofreConta, private val transporte: TransporteConta) {
    private val mutex = Mutex()
    private val _escopo = MutableStateFlow(runCatching { EscopoLocal.de(cofreOriginal.ler()) }.getOrDefault(EscopoLocal.LEGADO))
    val escopo = _escopo.asStateFlow()
    private val cofre = object : CofreConta {
        override fun ler() = cofreOriginal.ler()
        override fun gravar(sessao: SessaoConta) {
            val proximo = EscopoLocal.de(sessao)
            cofreOriginal.gravar(sessao)
            _escopo.value = proximo // Somente depois da confirmação da gravação segura.
        }
        override fun limpar() {
            try { cofreOriginal.limpar() }
            finally { _escopo.value = EscopoLocal.LEGADO }
        }
    }

    private fun chamar(s: SessaoConta, caminho: String, metodo: String = "GET", corpo: String? = null): String =
        resposta(transporte.enviar(s.endereco, caminho, metodo, s.acesso, corpo))

    private fun resposta(r: RespostaConta): String {
        if (r.status !in 200..299) throw FalhaHttpConta(r.status)
        return r.corpo
    }

    private fun carregar(): SessaoConta {
        val sessao = try { cofre.ler() } catch (_: Exception) {
            cofre.limpar(); throw LoginNecessario("Não foi possível recuperar sua sessão. Entre novamente.")
        } ?: throw LoginNecessario()
        if (sessao.renovacaoPendente) {
            cofre.limpar(); throw LoginNecessario("O acesso foi interrompido durante a renovação. Entre novamente.")
        }
        return sessao
    }

    private fun conferir(s: SessaoConta, eu: IdentidadeConta) {
        if (eu.id != s.usuarioId || eu.empresaId != s.empresaId) {
            cofre.limpar(); throw LoginNecessario("Sua conta ou empresa de acesso mudou. Entre novamente.")
        }
    }

    private fun renovar(s: SessaoConta): SessaoConta {
        // Marca antes de transmitir: se o processo morrer, não reutiliza um refresh possivelmente consumido.
        cofre.gravar(s.copy(renovacaoPendente = true))
        try {
            val body = JSONObject().put("refresh_token", s.renovacao).toString()
            val (access, refresh) = JsonConta.tokens(resposta(transporte.enviar(s.endereco, "/auth/refresh", "POST", null, body)))
            val novo = s.copy(acesso = access, renovacao = refresh, renovacaoPendente = false)
            conferir(novo, JsonConta.identidade(chamar(novo, "/auth/me")))
            cofre.gravar(novo)
            return novo
        } catch (_: Exception) {
            cofre.limpar()
            throw LoginNecessario("Não foi possível confirmar a renovação. Entre novamente.")
        }
    }

    private suspend fun <T> autenticado(bloco: (SessaoConta) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            var s = carregar()
            try { bloco(s) } catch (e: FalhaHttpConta) {
                if (e.status != 401) throw e
                s = renovar(s)
                try { bloco(s) } catch (e2: FalhaHttpConta) {
                    if (e2.status == 401) { cofre.limpar(); throw LoginNecessario() }
                    throw e2
                } catch (e2: LoginNecessario) { cofre.limpar(); throw e2 }
            } catch (e: LoginNecessario) { cofre.limpar(); throw e }
        }
    }

    suspend fun restaurar(): IdentidadeConta? = withContext(Dispatchers.IO) {
        // A ausência é normal na primeira abertura; corrupção vai pelo fluxo de reautenticação.
        val existe = mutex.withLock { runCatching { cofre.ler() != null }.getOrDefault(true) }
        if (!existe) null else autenticado { s -> JsonConta.identidade(chamar(s, "/auth/me")).also { conferir(s, it) } }
    }

    suspend fun entrar(endereco: String, email: String, senha: String): IdentidadeConta = withContext(Dispatchers.IO) {
        mutex.withLock {
            val base = EnderecoConta.normalizar(endereco)
            require(email.isNotBlank() && email.length <= 320 && senha.isNotEmpty() && senha.length <= 256) { "Confira e-mail e senha." }
            require(cofre.ler() == null) { "Saia da conta atual antes de entrar em outra." }
            val body = JSONObject().put("email", email.trim()).put("senha", senha).toString()
            val (access, refresh) = JsonConta.tokens(resposta(transporte.enviar(base, "/auth/login", "POST", null, body)))
            try {
                val eu = JsonConta.identidade(resposta(transporte.enviar(base, "/auth/me", "GET", access, null)))
                cofre.gravar(SessaoConta(base, access, refresh, eu.id, eu.empresaId))
                eu
            } catch (e: Exception) {
                cofre.limpar()
                runCatching { transporte.enviar(base, "/auth/logout", "POST", access, null) }
                throw e
            }
        }
    }

    suspend fun cargas(busca: String = "", offset: Int = 0): PaginaCargas = autenticado { s ->
        require(offset >= 0 && busca.trim().length <= 96)
        val valor = busca.trim()
        val caminho = if (valor.isBlank()) "/remessas?limite=30&offset=$offset" else
            "/remessas/localizar?valor=${URLEncoder.encode(valor, "UTF-8")}&limite=30&offset=$offset"
        JsonConta.pagina(chamar(s, caminho), s.empresaId, valor.isNotBlank())
    }

    suspend fun detalhe(id: String): DetalheCargaConta = autenticado { s ->
        val uuid = UUID.fromString(id).toString()
        JsonConta.detalhe(chamar(s, "/remessas/$uuid"), s.empresaId)
    }

    suspend fun sair(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val s = runCatching { cofre.ler() }.getOrNull()
            try {
                if (s == null) true else {
                    val atual = if (s.renovacaoPendente) null else s
                    if (atual == null) false else try {
                        chamar(atual, "/auth/logout", "POST"); true
                    } catch (e: FalhaHttpConta) {
                        if (e.status != 401) throw e
                        chamar(renovar(atual), "/auth/logout", "POST"); true
                    }
                }
            } catch (_: Exception) { false } finally { cofre.limpar() }
        }
    }
}
