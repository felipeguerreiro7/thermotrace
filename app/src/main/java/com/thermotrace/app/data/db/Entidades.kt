package com.thermotrace.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.thermotrace.app.domain.StatusRemessa
import com.thermotrace.app.domain.StatusVolume
import com.thermotrace.app.domain.TipoDocumento
import com.thermotrace.app.domain.TipoLeitura

/**
 * Banco local. Espelha o schema de auditoria em `db/01_schema.sql`, reduzido
 * ao que cabe no aparelho.
 *
 * Duas regras herdadas do servidor valem aqui também:
 *
 *  - **A leitura bruta é imutável.** `LeituraEntity.respostaBruta` guarda o
 *    array cru devolvido pelo SDK. Nunca sobrescreva: se o decodificador
 *    melhorar, recalcula-se a série a partir dele.
 *  - **Três tempos separados.** `ocorridoEm` (o fato), `registradoEm` (o
 *    lançamento) e o relógio do aparelho. Colapsar isso num campo só destrói
 *    a auditoria — e é o erro mais comum nesse tipo de sistema.
 */

@Entity(tableName = "remessa", indices = [Index("codigo", unique = true)])
data class RemessaEntity(
    @PrimaryKey val id: String,
    val codigo: String,
    val remetente: String,
    val transportadora: String,
    val destinatario: String,
    val destinoEndereco: String,
    val contatoRecebimento: String,
    val perfilTermicoCodigo: String,
    val descricaoCarga: String,
    val intervaloSegundos: Int,
    val previsaoColetaMillis: Long?,
    val previsaoEntregaMillis: Long?,
    /** Chave natural vinda do documento fiscal. Deduplica remessas. */
    val identidadeDocumento: String? = null,
    val status: StatusRemessa,
    val criadaEmMillis: Long,
)

@Entity(
    tableName = "documento",
    foreignKeys = [ForeignKey(
        entity = RemessaEntity::class, parentColumns = ["id"],
        childColumns = ["remessaId"], onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("remessaId")]
)
data class DocumentoEntity(
    @PrimaryKey val id: String,
    val remessaId: String,
    val tipo: TipoDocumento,
    val numero: String,
    /** Chave de acesso de 44 dígitos (NF-e, CT-e, MDF-e). Identidade da remessa. */
    val chaveAcesso: String? = null,
    val cnpjEmitente: String? = null,
    val serie: String? = null,
    val ufEmitente: String? = null,
    val competencia: String? = null,
    /** Dígito verificador conferido offline. */
    val validado: Boolean = false,
    val observacaoValidacao: String? = null,
    /** Conteúdo EXATO lido do QR/código de barras, antes de qualquer parsing.
     *  A especificação pede isso (§6.2) e a auditoria depende disso. */
    val conteudoBruto: String?,
    val simbologia: String?,
    val digitadoManualmente: Boolean,
)

@Entity(
    tableName = "volume",
    foreignKeys = [ForeignKey(
        entity = RemessaEntity::class, parentColumns = ["id"],
        childColumns = ["remessaId"], onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("remessaId")]
)
data class VolumeEntity(
    @PrimaryKey val id: String,
    val remessaId: String,
    val sequencia: Int,
    val codigoExterno: String?,
    /** Derivada do documento: chave#V001. Sobrevive à troca de etiqueta. */
    val identidade: String? = null,
    val monitorado: Boolean,
    val status: StatusVolume,
    val etiquetaId: String?,
)

@Entity(tableName = "etiqueta", indices = [Index("uidNfc", unique = true), Index("serial", unique = true)])
data class EtiquetaEntity(
    @PrimaryKey val id: String,
    /** Identificador COMERCIAL: nosso serial impresso. */
    val serial: String,
    val qrPayload: String,
    /** Identificador TÉCNICO, sempre em forma canônica (ver TagIdentity). */
    val uidNfc: String,
    val uhfEpc: String?,
    val uhfTid: String?,
    val loteId: String?,
    val certificadoCalibracao: String?,
    val calibracaoValidaAteMillis: Long?,
    val ciclosAtivacao: Int,
    val ultimaTensaoV: Double?,
    val aposentadaEmMillis: Long?,
)

@Entity(
    tableName = "sessao",
    foreignKeys = [ForeignKey(
        entity = VolumeEntity::class, parentColumns = ["id"],
        childColumns = ["volumeId"], onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("volumeId"), Index("remessaId"), Index(value = ["etiquetaId", "epochInicioEtiqueta"], unique = true)]
)
data class SessaoEntity(
    @PrimaryKey val id: String,
    val remessaId: String,
    val volumeId: String,
    val etiquetaId: String,
    val perfilTermicoCodigo: String,

    // ---- base de tempo -------------------------------------------------
    /** Epoch gravado NA etiqueta pelo celular no START. Não é relógio confiável. */
    val epochInicioEtiqueta: Long,
    /** O que o relógio do aparelho achava que era, no START. */
    val inicioDispositivoMillis: Long,
    /** Desvio medido do relógio do aparelho. Positivo = adiantado. */
    val desvioRelogioMs: Long?,
    /**
     * START_INSTANT (Android) ou FIRST_WINDOW (iOS).
     * Os dois SDKs do fabricante gravam coisas diferentes no mesmo campo.
     * Sem isto, uma sessão ativada por iPhone e lida aqui sai deslocada.
     */
    val baseDeTempo: String,
    val plataformaAtivacao: String,

    // ---- configuração gravada na etiqueta ------------------------------
    val delayMinutos: Int,
    val intervaloSegundos: Int,
    val quantidadePlanejada: Int,
    val minConfiguradoC: Double,
    val maxConfiguradoC: Double,
    val modoArmazenamento: Int,

    /** Última vez que o bit de status foi lido de verdade. Ver EstadoEtiqueta. */
    val verificadoEmMillis: Long? = null,
    /** Resultado dessa verificação. null = nunca verificado. */
    val registrandoNaVerificacao: Boolean? = null,
    val ativacaoConfirmada: Boolean,
    val tensaoNoStartV: Double?,
    val encerradaEmMillis: Long?,

    /**
     * Instante em que a ETIQUETA confirmou o STOP fisico.
     *
     * Nao e o mesmo que [encerradaEmMillis], e a diferenca importa: aquilo
     * marca que a leitura final foi gravada aqui no celular; isto marca que o
     * chip parou de registrar. STOP falho e resultado possivel - a leitura
     * final pode estar salva enquanto a etiqueta continua gravando -, e sem
     * uma coluna propria o app so sabia disso enquanto a tela de coleta
     * estivesse aberta. Depois disso o volume aparecia como ENCERRADO e nada
     * contradizia essa palavra.
     *
     * `null` significa "nao confirmado", nunca "nao parou": pode ser STOP que
     * falhou, ou sessao anterior a este campo. As duas situacoes pedem a mesma
     * conduta - encostar a etiqueta e conferir - e por isso compartilham o
     * mesmo valor. Preenchido uma unica vez, nunca sobrescrito.
     */
    val loggerParadoEmMillis: Long? = null,
)

@Entity(
    tableName = "leitura",
    foreignKeys = [ForeignKey(
        entity = SessaoEntity::class, parentColumns = ["id"],
        childColumns = ["sessaoId"], onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sessaoId"), Index("chaveIdempotencia", unique = true)]
)
data class LeituraEntity(
    @PrimaryKey val id: String,
    val sessaoId: String,
    val tipo: TipoLeitura,
    /** Chave de idempotência do outbox. Reenviar não duplica. */
    val chaveIdempotencia: String,

    val lidaEmMillis: Long,
    val desvioRelogioMs: Long?,

    /** Evidência bruta. NUNCA sobrescrever. */
    @ColumnInfo(typeAffinity = ColumnInfo.TEXT) val respostaBruta: List<String>,
    val versaoSdk: String,
    val versaoDecodificador: String,

    val codigoEstado: String?,
    val quantidadeMedida: Int,
    val intervaloRelatado: Int,
    val tensaoV: Double?,
    val temperaturaInstantaneaC: Double?,

    /** Nome legado: diferença coleta–último ponto nominal; não mede deriva do sensor. */
    val derivaRelogioSegundos: Long,
    val horariosCorrigidos: Boolean,

    /** Instante do primeiro ponto, já resolvido. */
    val primeiroPontoMillis: Long,
    @ColumnInfo(typeAffinity = ColumnInfo.TEXT) val temperaturas: List<Double>,

    val hashPayload: String,
    val hashAnterior: String?,
    val hashEncadeado: String,

    /**
     * Onde o CELULAR estava quando o fix foi obtido — não onde a carga estava,
     * e não necessariamente no instante do bipe.
     *
     * Por isso a precisão e o instante do fix vêm junto e são obrigatórios
     * sempre que houver coordenada: um ponto com 2 km de erro apresentado como
     * "local da coleta" é pior que nenhum ponto. Tudo nulo significa, de forma
     * explícita, que não houve localização — nunca se inventa uma.
     *
     * A coordenada é evidência; o endereço legível não é, e por isso não mora
     * aqui: ele exige rede, e doca e câmara fria são justamente onde não há.
     */
    val latitude: Double? = null,
    val longitude: Double? = null,
    val precisaoMetros: Double? = null,
    val provedorLocal: String? = null,
    val localizadoEmMillis: Long? = null,

    /**
     * Quando esta leitura saiu do aparelho num laudo exportado.
     *
     * Nulo significa que a evidencia existe em UM lugar so. Com
     * `allowBackup=false` — que e deliberado, para nao mandar cadeia de
     * custodia para nuvem de terceiro — perder o celular apaga a prova. Esta
     * coluna e o que permite o app avisar antes de isso acontecer.
     *
     * Nao e o mesmo que [sincronizada]: aquilo e o servidor, isto e o arquivo.
     */
    val exportadaEmMillis: Long? = null,

    val sincronizada: Boolean = false,
)

@Entity(
    tableName = "custodia",
    foreignKeys = [ForeignKey(
        entity = RemessaEntity::class, parentColumns = ["id"],
        childColumns = ["remessaId"], onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("remessaId")]
)
data class CustodiaEntity(
    @PrimaryKey val id: String,
    val remessaId: String,
    val de: String?,
    val para: String,
    /** Quando o fato aconteceu (pode ser declarado retroativamente). */
    val ocorridoEmMillis: Long,
    /** Quando o sistema registrou. Nunca editável. */
    val registradoEmMillis: Long,
    /** automatico_nfc | confirmado_tempo_real | informado_posteriormente */
    val origem: String,
    val volumesConfirmados: Int?,
    val recebedor: String?,
    val observacao: String?,
    val local: String?,
)

/** Fila de envio. O aparelho opera sem sinal; nada se perde por isso. */
@Entity(tableName = "outbox", indices = [Index("chaveIdempotencia", unique = true)])
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chaveIdempotencia: String,
    val endpoint: String,
    val corpoJson: String,
    val criadoEmMillis: Long,
    val tentativas: Int = 0,
    val ultimoErro: String? = null,
)

// ---------------------------------------------------------------------

class Conversores {
    @TypeConverter fun statusRemessaParaTexto(v: StatusRemessa): String = v.name
    @TypeConverter fun textoParaStatusRemessa(v: String): StatusRemessa = StatusRemessa.valueOf(v)

    @TypeConverter fun statusVolumeParaTexto(v: StatusVolume): String = v.name
    @TypeConverter fun textoParaStatusVolume(v: String): StatusVolume = StatusVolume.valueOf(v)

    @TypeConverter fun tipoLeituraParaTexto(v: TipoLeitura): String = v.name
    @TypeConverter fun textoParaTipoLeitura(v: String): TipoLeitura = TipoLeitura.valueOf(v)

    @TypeConverter fun tipoDocumentoParaTexto(v: TipoDocumento): String = v.name
    @TypeConverter fun textoParaTipoDocumento(v: String): TipoDocumento = TipoDocumento.valueOf(v)

    // Séries são gravadas uma vez e lidas inteiras. Guardar como texto separado
    // por vírgula evita 4.864 linhas por leitura sem perder nada.
    @TypeConverter fun listaTextoParaTexto(v: List<String>): String = v.joinToString("")
    @TypeConverter fun textoParaListaTexto(v: String): List<String> =
        if (v.isEmpty()) emptyList() else v.split("")

    @TypeConverter fun listaDoubleParaTexto(v: List<Double>): String = v.joinToString(",")
    @TypeConverter fun textoParaListaDouble(v: String): List<Double> =
        if (v.isEmpty()) emptyList() else v.split(",").mapNotNull { it.toDoubleOrNull() }
}

/**
 * Recompõe o fix desta leitura, ou `null` se ela não teve localização.
 *
 * Exige as três coisas juntas — coordenada, provedor e instante do fix. Uma
 * coordenada sem o instante não permite dizer se o fix é do bipe ou de meia
 * hora antes, e é exatamente essa diferença que decide se o ponto significa
 * alguma coisa. Meia evidência aqui vira evidência inventada na tela.
 */
fun LeituraEntity.fixDaColeta(): com.thermotrace.app.data.local.FixLocal? {
    val lat = latitude ?: return null
    val lon = longitude ?: return null
    val provedor = provedorLocal ?: return null
    val obtidoEm = localizadoEmMillis ?: return null
    return com.thermotrace.app.data.local.FixLocal(
        latitude = lat,
        longitude = lon,
        precisaoMetros = precisaoMetros,
        provedor = provedor,
        obtidoEmMillis = obtidoEm,
    )
}
