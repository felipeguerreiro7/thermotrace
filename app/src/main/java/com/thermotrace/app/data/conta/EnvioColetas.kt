package com.thermotrace.app.data.conta

import androidx.room.withTransaction
import com.thermotrace.app.data.db.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID

/** Envio explícito por sessão. A rede nunca fica dentro de uma transação SQLite. */
class EnvioColetas(private val db: ThermoTraceDb, private val conta: RepositorioConta,
    val escopo: EscopoLocal, private val installId: String, private val modelo: String,
    private val so: String, private val versao: String) {
    private val mutex = Mutex()
    private val dao get() = db.envioDao()
    suspend fun vinculo(id: String) = dao.vinculo(id)
    suspend fun pedidos(id: String) = dao.pedidos(id)
    suspend fun cargas(busca: String): List<CargaConta> {
        val eu = conta.autorEnvio(escopo)
        val raw = conta.consultarEnvio(escopo,"/remessas/localizar?valor=${java.net.URLEncoder.encode(busca.trim(),"UTF-8")}&limite=30&offset=0")
        return JsonConta.pagina(raw,eu.empresaId,true).cargas
    }
    suspend fun destino(id: String): DestinoEnvio {
        val eu = conta.autorEnvio(escopo)
        return ContratoEnvio.destino(conta.consultarEnvio(escopo,"/remessas/${UUID.fromString(id)}"),eu.empresaId)
    }
    private suspend fun aparelho(empresa: String): String {
        // Metadados da primeira inscrição ficam congelados inclusive após atualizar o APK.
        val inicial = AparelhoEnvio(installId=installId,corpo=JSONObject().put("install_id",installId)
            .put("modelo",modelo.take(128)).put("versao_so",so.take(64)).put("versao_app",versao.take(64)).toString(),
            chave="tt-aparelho-${UUID.randomUUID()}")
        dao.prepararAparelho(inicial)
        val salvo = checkNotNull(dao.aparelho())
        salvo.dispositivoId?.let { return it }
        val raw = conta.enviarPreservado(escopo,"/dispositivos",salvo.corpo,salvo.chave)
        val o = JSONObject(raw)
        require(JsonConta.uuid(o,"empresa_id") == empresa && JsonConta.uuid(o,"install_id") == salvo.installId && o.getBoolean("ativo"))
        val id = JsonConta.uuid(o,"id")
        dao.confirmarAparelho(id)
        return id
    }
    suspend fun vincular(sessaoId: String, remessaId: String, volumeId: String) = mutex.withLock {
        check(dao.vinculo(sessaoId) == null) { "Esta sessão já possui um destino fixado." }
        val s = checkNotNull(db.sessaoDao().buscar(sessaoId))
        val origem = checkNotNull(db.outboxDao().porChave("sessao:$sessaoId")) { "Evidência de início ausente. Preserve o laudo local." }
        check(JSONObject(origem.corpoJson).optJSONArray("resposta_start")?.length()?.let { it > 0 } == true) {
            "Ativação antiga sem resposta original de START. Ela permanece local; não reative a etiqueta para tentar enviar."
        }
        val eu = conta.autorEnvio(escopo)
        val d = destino(remessaId)
        val v = d.volumes.singleOrNull { it.id == volumeId } ?: error("Volume não pertence à carga selecionada.")
        check(s.intervaloSegundos == d.intervalo && java.math.BigDecimal.valueOf(s.minConfiguradoC).compareTo(d.minimo) == 0 &&
            java.math.BigDecimal.valueOf(s.maxConfiguradoC).compareTo(d.maximo) == 0) {
            "Faixa ou intervalo da etiqueta difere da carga online. Confira a configuração; os dados não serão alterados."
        }
        val tag = checkNotNull(db.etiquetaDao().buscar(s.etiquetaId))
        require(tag.uidNfc.matches(Regex("(?:[0-9A-F]{2}){4,16}")))
        val te = JSONObject(conta.consultarEnvio(escopo,"/etiquetas/localizar?uid=${tag.uidNfc}"))
        require(JsonConta.uuid(te,"empresa_id") == eu.empresaId && te.getString("uid_canonico") == tag.uidNfc)
        val aparelho = aparelho(eu.empresaId)
        val vinculo = VinculoEnvio(s.id,escopo.chave,eu.empresaId,eu.id,d.id,v.id,d.codigo,v.sequencia,
            JsonConta.uuid(te,"id"),tag.uidNfc,aparelho,System.currentTimeMillis())
        val inicio = ContratoEnvio.inicio(s,vinculo,origem)
        db.withTransaction { dao.vincular(vinculo); dao.preparar(inicio) }
    }
    suspend fun enviar(sessaoId: String): Int = mutex.withLock {
        val v = checkNotNull(dao.vinculo(sessaoId)) { "Escolha a carga e o volume antes de enviar." }
        val eu = conta.autorEnvio(escopo)
        check(v.escopo == escopo.chave && v.empresaId == eu.empresaId && v.usuarioId == eu.id) {
            "Somente a conta original pode enviar esta evidência."
        }
        db.withTransaction {
            val s = checkNotNull(db.sessaoDao().buscar(sessaoId))
            val existentes = dao.pedidos(sessaoId).map { it.eventoId }.toSet()
            db.leituraDao().porSessao(sessaoId).filter { it.id !in existentes }.forEach { l ->
                val origem = checkNotNull(db.outboxDao().porChave(l.chaveIdempotencia)) { "Conteúdo original de envio ausente; preserve a coleta." }
                dao.preparar(ContratoEnvio.leitura(s,v,l,origem))
            }
        }
        var enviados = 0
        for (p in dao.pedidos(sessaoId).filter { it.recibo == null }) {
            try {
                check(ContratoEnvio.hash(p.corpo) == p.sha256) { "Conteúdo de envio alterado. Exige conferência." }
                val raw = conta.enviarPreservado(escopo,p.caminho,p.corpo,p.chave)
                ContratoEnvio.conferirRecibo(raw,p,v)
                db.withTransaction {
                    dao.confirmar(p.eventoId,raw)
                    if (p.tipo != "ativacao") db.leituraDao().marcarSincronizada(p.eventoId)
                    db.outboxDao().removerPorChave(p.chaveLocal)
                }
                enviados++
            } catch (e: CancellationException) { throw e }
            catch(e: Exception) {
                val mensagem = when(e) {
                    is FalhaHttpConta -> when(e.status) {
                        409 -> "Conflito no servidor. A coleta foi mantida; confira o vínculo com o suporte."
                        403 -> "Acesso recusado. Confira a permissão da conta."
                        404 -> "Carga, sessão ou etiqueta indisponível no servidor."
                        422 -> "Servidor recusou os campos da coleta. Preserve a evidência para revisão."
                        else -> "Envio não confirmado (HTTP ${e.status}). Tente novamente."
                    }
                    is LoginNecessario -> "Entre novamente na conta original para continuar."
                    else -> "Envio não confirmado. A coleta foi mantida; tente novamente."
                }
                dao.falha(p.eventoId,mensagem)
                throw IllegalStateException(mensagem,e) // Para: não envia a leitura seguinte sem o recibo anterior.
            }
        }
        enviados
    }
}
