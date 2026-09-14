package com.thermotrace.app

import com.thermotrace.app.data.conta.*
import com.thermotrace.app.data.db.*
import com.thermotrace.app.domain.*
import org.json.JSONArray
import org.json.JSONObject

/** Dados exclusivamente sintéticos, compartilhados pelos ensaios JVM e Android. */
object DadosEnvioTeste {
    fun id(n: Int) = "%08d-1111-4111-8111-111111111111".format(n)
    val conta = SessaoConta("https://envio.example.test","sintetico.access","a".repeat(64),id(10),id(11))
    val escopo = EscopoLocal.de(conta)
    val uid = "0102030405060708"
    val bruto = listOf("  original\n", "separador\u001fpreservado", "áçã🌡")
    val instante = 1789214400000L
    fun sessao() = SessaoEntity(id=id(1),remessaId=id(2),volumeId=id(3),etiquetaId=id(4),
        perfilTermicoCodigo="REFRIGERADO_2_8",epochInicioEtiqueta=instante/1000,inicioDispositivoMillis=instante,
        desvioRelogioMs=null,baseDeTempo="START_INSTANT",plataformaAtivacao="android",delayMinutos=0,
        intervaloSegundos=600,quantidadePlanejada=648,minConfiguradoC=2.0,maxConfiguradoC=8.0,
        modoArmazenamento=0,ativacaoConfirmada=true,tensaoNoStartV=null,encerradaEmMillis=null)
    fun vinculo() = VinculoEnvio(id(1),escopo.chave,id(11),id(10),id(20),id(21),"CARGA-SINTETICA",1,id(22),uid,id(23),instante)
    fun inicio() = OutboxEntity(chaveIdempotencia="sessao:${id(1)}",endpoint="sessoes",criadoEmMillis=instante,
        corpoJson=JSONObject().put("sessao_id",id(1)).put("nfc_uid",uid).put("resposta_start",JSONArray(bruto))
            .put("versao_sdk","sintetico-sem-hardware-1").toString())
    fun leitura(n: Int, tipo: TipoLeitura = TipoLeitura.CHECKPOINT) = LeituraEntity(id=id(n),sessaoId=id(1),tipo=tipo,
        chaveIdempotencia="leitura-sintetica-$n",lidaEmMillis=instante+n*600000L,desvioRelogioMs=null,
        respostaBruta=bruto,versaoSdk="sintetico-sem-hardware-1",versaoDecodificador="teste-1",codigoEstado="1",
        quantidadeMedida=1,intervaloRelatado=600,tensaoV=null,temperaturaInstantaneaC=5.0,derivaRelogioSegundos=0,
        horariosCorrigidos=false,primeiroPontoMillis=instante,temperaturas=listOf(5.0),hashPayload="a".repeat(64),
        hashAnterior=null,hashEncadeado="b".repeat(64))
    fun outbox(l: LeituraEntity) = OutboxEntity(chaveIdempotencia=l.chaveIdempotencia,endpoint="leituras",criadoEmMillis=l.lidaEmMillis,
        corpoJson=JSONObject().put("session_id",l.sessaoId).put("nfc_uid",uid).put("raw_sdk_response",JSONArray(bruto)).toString())
    fun recibo(p: PedidoEnvio, v: VinculoEnvio = vinculo()) = JSONObject().put("evento_id",p.eventoId)
        .put("sessao_id",p.sessaoId).put("empresa_id",v.empresaId).put("volume_id",v.volumeOnline)
        .put("remessa_id",v.remessaOnline).put("leitura_id",id(90)).put("tipo",p.tipo)
        .put("origem","declaracao_android").put("ordem_recebimento",1).put("recebida_em_servidor","2026-09-14T12:00:00Z")
        .put("hash_payload","a".repeat(64)).put("hash_anterior",JSONObject.NULL).put("hash_encadeado","b".repeat(64))
        .put("algoritmo_integridade","tt-evidencia-1").put("situacao","evidencia_recebida_sem_validacao_fisica")
        .put("stop_fisico","nao_confirmado_pelo_servidor")
    fun destino() = JSONObject().put("id",id(20)).put("empresa_id",id(11)).put("codigo","CARGA-SINTETICA")
        .put("destinatario_nome","Dono fictício").put("status","preparacao").put("intervalo_segundos",600)
        .put("criterio",JSONObject().put("perfil",JSONObject().put("min_c","2.00").put("max_c","8.00")))
        .put("volumes",JSONArray().put(JSONObject().put("id",id(21)).put("sequencia",1).put("identidade",JSONObject.NULL).put("monitorado",true)))
}
