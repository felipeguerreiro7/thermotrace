package com.thermotrace.app

import android.content.Context
import androidx.room.withTransaction
import com.thermotrace.app.data.conta.*
import com.thermotrace.app.data.db.*
import com.thermotrace.app.domain.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

object EnvioInstrumentado {
    fun executar(context: Context) = runBlocking {
        val d = DadosEnvioTeste
        var atual: SessaoConta? = d.conta.copy(endereco="https://${UUID.randomUUID()}.example.test")
        val escopo = EscopoLocal.de(atual)
        val cofre = object:CofreConta {
            override fun ler()=atual
            override fun gravar(sessao:SessaoConta) { atual=sessao }
            override fun limpar() { atual=null }
        }
        var modo = "normal"
        val corpos = mutableMapOf<String,String>()
        val recibos = mutableMapOf<String,String>()
        var chamadas = 0
        val transporte = object:TransporteConta {
            override fun enviar(endereco:String,caminho:String,metodo:String,token:String?,corpo:String?):RespostaConta =
                RespostaConta(200,when {
                    caminho=="/auth/me" -> JSONObject().put("id",d.id(10)).put("empresa_id",d.id(11))
                        .put("nome","Operador sintético").put("empresa","Empresa sintética").put("papel","operador").toString()
                    caminho.startsWith("/remessas/") -> d.destino().toString()
                    caminho.startsWith("/etiquetas/") -> JSONObject().put("id",d.id(22)).put("empresa_id",d.id(11))
                        .put("uid_canonico",d.uid).toString()
                    else -> error("Rota inesperada")
                })
            override fun enviarIdempotente(endereco:String,caminho:String,token:String,corpo:String,chave:String):RespostaConta {
                chamadas++
                corpos[chave]?.let { check(it==corpo) { "Reenvio alterou bytes" } }
                corpos[chave]=corpo
                if(caminho=="/dispositivos") return RespostaConta(201,JSONObject().put("id",d.id(23)).put("empresa_id",d.id(11))
                    .put("install_id",d.id(24)).put("ativo",true).toString())
                if(modo=="conflito") return RespostaConta(409,"{}")
                val body=JSONObject(corpo); val evidencia=body.optJSONObject("evidencia") ?: body
                val evento=evidencia.getString("evento_id")
                val tipo=if(body.has("evidencia")) "ativacao" else body.getString("tipo")
                val pedido=PedidoEnvio(evento,d.id(1),"sintetico",tipo,caminho,corpo,ContratoEnvio.hash(corpo),chave,0)
                val recibo=recibos.getOrPut(chave) { d.recibo(pedido).put("leitura_id",UUID.randomUUID().toString()).toString() }
                if(modo=="perdeu_resposta") throw IOException("Resposta perdida após recebimento sintético")
                if(modo=="recibo_errado") return RespostaConta(201,JSONObject(recibo).put("volume_id",d.id(999)).toString())
                return RespostaConta(201,recibo)
            }
        }
        val conta=RepositorioConta(cofre,transporte)
        var db=ThermoTraceDb.abrir(context,escopo)
        fun engine()=EnvioColetas(db,conta,escopo,d.id(24),"Emulador sintético","teste","teste-086")
        suspend fun deveFalhar(bloco:suspend ()->Unit) {
            var falhou=false
            try { bloco() } catch(_:Exception) { falhou=true }
            check(falhou) { "Operação deveria ser recusada" }
        }
        try {
            db.withTransaction {
                db.remessaDao().inserir(RemessaEntity(d.id(2),"LOCAL-SINTETICO","","","","","","REFRIGERADO_2_8","",600,
                    null,null,null,StatusRemessa.EM_PREPARACAO,d.instante))
                db.volumeDao().inserirTodos(listOf(VolumeEntity(d.id(3),d.id(2),1,null,null,true,StatusVolume.MONITORANDO,d.id(4))))
                db.etiquetaDao().inserir(EtiquetaEntity(d.id(4),"ET-SINTETICA","ET-SINTETICA",d.uid,null,null,null,null,null,1,null,null))
                db.sessaoDao().inserir(d.sessao())
                db.outboxDao().enfileirar(d.inicio())
                for(l in listOf(d.leitura(30),d.leitura(31,TipoLeitura.FINAL))) {
                    db.leituraDao().inserir(l); db.outboxDao().enfileirar(d.outbox(l))
                }
                // Fila não suportada não deve ser apagada junto com as leituras.
                db.outboxDao().enfileirar(OutboxEntity(chaveIdempotencia="alerta-preservado",endpoint="alertas",corpoJson="{}",criadoEmMillis=1))
            }
            val e=engine()
            deveFalhar { e.vincular(d.id(1),d.id(20),d.id(999)) }
            check(db.envioDao().vinculo(d.id(1))==null)
            e.vincular(d.id(1),d.id(20),d.id(21))
            deveFalhar { e.vincular(d.id(1),d.id(20),d.id(21)) }
            modo="perdeu_resposta"; deveFalhar { e.enviar(d.id(1)) }
            check(db.envioDao().pedidos(d.id(1)).size==3)
            check(db.envioDao().pedidos(d.id(1)).all{it.recibo==null})
            check(db.leituraDao().naoSincronizadas().size==2 && db.outboxDao().pendentes().size==4)
            modo="recibo_errado"; deveFalhar { e.enviar(d.id(1)) }
            check(db.envioDao().pedidos(d.id(1)).all{it.recibo==null})
            modo="conflito"; deveFalhar { e.enviar(d.id(1)) }
            check(db.envioDao().pedidos(d.id(1)).first().erro!!.contains("Conflito"))
            modo="normal"
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER falha_recibo BEFORE UPDATE OF recibo ON pedido_envio WHEN NEW.recibo IS NOT NULL BEGIN SELECT RAISE(ABORT, 'falha sintetica'); END")
            deveFalhar { e.enviar(d.id(1)) }
            check(db.outboxDao().pendentes().size==4 && db.envioDao().pedidos(d.id(1)).all{it.recibo==null})
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER falha_recibo")
            db.close(); db=ThermoTraceDb.abrir(context,escopo)
            val reaberto=engine()
            check(reaberto.enviar(d.id(1))==3)
            check(reaberto.enviar(d.id(1))==0)
            check(db.leituraDao().naoSincronizadas().isEmpty())
            check(db.outboxDao().pendentes().single().chaveIdempotencia=="alerta-preservado")
            check(db.envioDao().pedidos(d.id(1)).all{it.recibo!=null})
            // FK RESTRICT protege histórico ligado ao servidor.
            deveFalhar { db.remessaDao().apagar(d.id(2)) }
            check(db.sessaoDao().buscar(d.id(1))!=null)
            atual=atual!!.copy(usuarioId=d.id(999))
            val antes=chamadas
            deveFalhar { reaberto.enviar(d.id(1)) }
            check(chamadas==antes)
        } finally { db.close(); context.deleteDatabase(escopo.banco) }
    }
}
