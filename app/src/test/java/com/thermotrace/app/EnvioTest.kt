package com.thermotrace.app

import com.thermotrace.app.data.conta.*
import com.thermotrace.app.domain.TipoLeitura
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EnvioTest {
    private val d = DadosEnvioTeste
    private fun inicio() = ContratoEnvio.inicio(d.sessao(),d.vinculo(),d.inicio())
    @Test fun preservaInicioOriginalSemNormalizar() {
        val p=inicio(); val a=JSONObject(p.corpo).getJSONObject("evidencia").getJSONArray("resposta_bruta")
        assertEquals(d.bruto,(0 until a.length()).map{a.getString(it)})
        assertEquals(p,inicio()); assertEquals(ContratoEnvio.hash(p.corpo),p.sha256)
    }
    @Test fun ativacaoAntigaNaoGanhaBrutoInventado() {
        val old=d.inicio().copy(corpoJson=JSONObject(d.inicio().corpoJson).apply { remove("resposta_start") }.toString())
        assertThrows(IllegalStateException::class.java) { ContratoEnvio.inicio(d.sessao(),d.vinculo(),old) }
    }
    @Test fun ativacaoSemConfirmacaoNaoEnvia() {
        assertThrows(IllegalStateException::class.java) { ContratoEnvio.inicio(d.sessao().copy(ativacaoConfirmada=false),d.vinculo(),d.inicio()) }
    }
    @Test fun baseIosNaoEhConvertidaPorSuposicao() {
        assertThrows(IllegalStateException::class.java) { ContratoEnvio.inicio(d.sessao().copy(baseDeTempo="FIRST_WINDOW"),d.vinculo(),d.inicio()) }
    }
    @Test fun checkpointEFinalPreservamIdentidadeEBruto() {
        for(tipo in listOf(TipoLeitura.CHECKPOINT,TipoLeitura.FINAL)) {
            val l=d.leitura(30,tipo)
            val p=ContratoEnvio.leitura(d.sessao(),d.vinculo(),l,d.outbox(l)); val o=JSONObject(p.corpo)
            assertEquals(l.id,o.getString("evento_id")); assertEquals(tipo.name.lowercase(),o.getString("tipo"))
            assertEquals(d.bruto[1],o.getJSONArray("resposta_bruta").getString(1))
            assertEquals(600,o.getInt("intervalo_relatado_s"))
        }
    }
    @Test fun brutoAcimaDoLimiteNaoEhTruncado() {
        val old=d.inicio().copy(corpoJson=JSONObject(d.inicio().corpoJson).put("resposta_start",JSONArray().put("á".repeat(131072)).put("x")).toString())
        assertThrows(IllegalArgumentException::class.java) { ContratoEnvio.inicio(d.sessao(),d.vinculo(),old) }
    }
    @Test fun reciboValidoConfere() { ContratoEnvio.conferirRecibo(d.recibo(inicio()).toString(),inicio(),d.vinculo()) }
    @Test fun reciboDeOutraColetaEmpresaCargaOuVolumeNaoConfirma() {
        for(campo in listOf("evento_id","sessao_id","empresa_id","volume_id","remessa_id")) {
            assertThrows(IllegalArgumentException::class.java) {
                ContratoEnvio.conferirRecibo(d.recibo(inicio()).put(campo,d.id(999)).toString(),inicio(),d.vinculo())
            }
        }
    }
    @Test fun reciboSimuladoOuSemHashNaoConfirma() {
        for((campo,valor) in listOf("origem" to "simulacao","hash_payload" to "invalido","stop_fisico" to "confirmado")) {
            assertThrows(IllegalArgumentException::class.java) {
                ContratoEnvio.conferirRecibo(d.recibo(inicio()).put(campo,valor).toString(),inicio(),d.vinculo())
            }
        }
    }
    @Test fun destinoDeOutraEmpresaRecusado() {
        assertThrows(IllegalArgumentException::class.java) { ContratoEnvio.destino(d.destino().toString(),d.id(999)) }
    }
    @Test fun cargaFechadaNaoRecebeVinculo() {
        assertThrows(IllegalStateException::class.java) { ContratoEnvio.destino(d.destino().put("status","concluida").toString(),d.id(11)) }
    }
    @Test fun contratoCompativelComFixtureDoServidor() {
        val cp=d.leitura(30); val fim=d.leitura(31,TipoLeitura.FINAL)
        val documento=JSONObject().put("aviso","Contrato Android gerado com dados sintéticos; nunca usar em produção.")
            .put("inicio",JSONObject(inicio().corpo))
            .put("checkpoint",JSONObject(ContratoEnvio.leitura(d.sessao(),d.vinculo(),cp,d.outbox(cp)).corpo))
            .put("final",JSONObject(ContratoEnvio.leitura(d.sessao(),d.vinculo(),fim,d.outbox(fim)).corpo))
        val fixture=java.io.File("../backend/docs/contrato-android-0.8.6.json")
        if(System.getProperty("tt.gerarContrato")=="true") {
            fixture.writeText(documento.toString(2),Charsets.UTF_8)
        }
        assertEquals("Fixture deve corresponder aos pedidos construídos pelo Android",JSONObject(fixture.readText()).toString(),documento.toString())
    }
    @Test fun trocaDeContaNaoReatribuiPedido() = runBlocking {
        val sessao=d.conta.copy(usuarioId=d.id(999))
        val cofre=object:CofreConta {
            override fun ler()=sessao
            override fun gravar(sessao:SessaoConta) { error("Não deve gravar") }
            override fun limpar() { error("Não deve apagar outra conta") }
        }
        val repo=RepositorioConta(cofre,TransporteConta { _,_,_,_,_-> error("Não deve transmitir") })
        try { repo.enviarPreservado(d.escopo,inicio().caminho,inicio().corpo,inicio().chave); fail("Deveria recusar") }
        catch(_: IllegalStateException) { }
    }
    @Test fun renovacaoDeTokenRepeteExatamenteCorpoEChave() = runBlocking {
        var atual: SessaoConta? = d.conta
        val cofre=object:CofreConta {
            override fun ler()=atual
            override fun gravar(sessao:SessaoConta) { atual=sessao }
            override fun limpar() { atual=null }
        }
        val pedidos=mutableListOf<Triple<String,String,String>>()
        val rede=object:TransporteConta {
            override fun enviar(endereco:String,caminho:String,metodo:String,token:String?,corpo:String?):RespostaConta =
                RespostaConta(200,when(caminho) {
                    "/auth/refresh" -> JSONObject().put("token_type","bearer").put("access_token","new.access")
                        .put("refresh_token","b".repeat(64)).toString()
                    "/auth/me" -> JSONObject().put("id",d.id(10)).put("empresa_id",d.id(11)).put("nome","Sintético")
                        .put("empresa","Sintética").put("papel","operador").toString()
                    else -> error("Rota inesperada")
                })
            override fun enviarIdempotente(endereco:String,caminho:String,token:String,corpo:String,chave:String):RespostaConta {
                pedidos+=Triple(corpo,chave,token)
                return RespostaConta(if(token=="new.access") 201 else 401,"{}")
            }
        }
        val p=inicio()
        assertEquals("{}",RepositorioConta(cofre,rede).enviarPreservado(d.escopo,p.caminho,p.corpo,p.chave))
        assertEquals(2,pedidos.size)
        assertEquals(pedidos[0].first,pedidos[1].first)
        assertEquals(pedidos[0].second,pedidos[1].second)
        assertNotEquals(pedidos[0].third,pedidos[1].third)
    }
}
