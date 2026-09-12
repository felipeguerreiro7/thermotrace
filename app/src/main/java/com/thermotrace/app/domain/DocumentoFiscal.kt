package com.thermotrace.app.domain

import java.time.LocalDate
import java.time.YearMonth

/**
 * Identidade da remessa a partir do documento fiscal.
 *
 * Até aqui a identidade do sistema era a etiqueta. Isso é frágil: a etiqueta é
 * um insumo reutilizável, e quem existe no mundo — para o cliente, para a
 * transportadora e para a fiscalização — é o documento. A hierarquia certa é:
 *
 *     Documento fiscal → Remessa → Volume → Etiqueta → Sessão → Leituras
 *
 * A chave do documento vira a chave natural da remessa. Isso dá três coisas
 * de graça: deduplicação (a mesma NF-e não vira duas remessas), busca no
 * recebimento (o destinatário escaneia a DANFE e acha o laudo), e conferência
 * cruzada (o CNPJ do emitente na chave tem que bater com o remetente).
 *
 * Este arquivo NÃO consulta a SEFAZ. Ele valida a estrutura e o dígito
 * verificador, que é o que dá para fazer offline — e offline é a condição
 * normal de operação na doca.
 */

enum class TipoDocumentoFiscal(val rotulo: String, val modelo: String?) {
    NFE("NF-e", "55"),
    NFCE("NFC-e", "65"),
    CTE("CT-e", "57"),
    CTE_OS("CT-e OS", "67"),
    MDFE("MDF-e", "58"),
    AWB("AWB", null),
    PEDIDO("Pedido", null),
    OUTRO("Outro", null),
    SEM_DOCUMENTO("Sem documento", null);

    companion object {
        fun porModelo(modelo: String): TipoDocumentoFiscal? =
            entries.firstOrNull { it.modelo == modelo }
    }
}

/**
 * Documento reconhecido.
 *
 * `conteudoBruto` é sempre preservado — a especificação pede (§6.2) e a
 * auditoria depende disso. Se um dia o parser melhorar, reprocessamos o bruto;
 * se guardássemos só os campos interpretados, um erro de parsing seria
 * permanente.
 */
data class DocumentoFiscal(
    val tipo: TipoDocumentoFiscal,
    val chaveAcesso: String?,       // 44 dígitos, quando houver
    val numero: String,
    val serie: String?,
    val cnpjEmitente: String?,
    val ufEmitente: String?,
    val competencia: YearMonth?,
    val conteudoBruto: String,
    val simbologia: String,         // QR_CODE, CODE_128, DATA_MATRIX, MANUAL
    val validado: Boolean,
    val observacaoValidacao: String?,
) {
    /** Chave natural da remessa. Cai para o número quando não há chave. */
    val identidade: String get() = chaveAcesso ?: "${tipo.name}:$numero"

    val rotuloCurto: String
        get() = when {
            chaveAcesso != null -> "${tipo.rotulo} ${numero.trimStart('0')}"
            else -> "${tipo.rotulo} $numero"
        }

    /** Chave formatada em grupos de 4, como aparece na DANFE. */
    val chaveFormatada: String?
        get() = chaveAcesso?.chunked(4)?.joinToString(" ")
}

object LeitorDocumentoFiscal {

    /**
     * Interpreta o que veio do scanner ou da digitação.
     *
     * Aceita, em ordem:
     *  1. URL de QR de NFC-e/CT-e (extrai a chave dos parâmetros)
     *  2. 44 dígitos soltos (chave de acesso, com ou sem separadores)
     *  3. 11 dígitos no formato AWB
     *  4. qualquer outra coisa, como documento genérico
     */
    fun interpretar(bruto: String, simbologia: String = "QR_CODE"): DocumentoFiscal {
        val texto = bruto.trim()

        extrairChaveDeUrl(texto)?.let { chave ->
            return deChaveAcesso(chave, texto, simbologia)
        }

        val digitos = texto.filter { it.isDigit() }

        if (digitos.length == 44) {
            return deChaveAcesso(digitos, texto, simbologia)
        }

        if (digitos.length == 11 && pareceAwb(texto)) {
            return deAwb(digitos, texto, simbologia)
        }

        return DocumentoFiscal(
            tipo = TipoDocumentoFiscal.OUTRO,
            chaveAcesso = null,
            numero = texto.take(60),
            serie = null,
            cnpjEmitente = null,
            ufEmitente = null,
            competencia = null,
            conteudoBruto = bruto,
            simbologia = simbologia,
            validado = false,
            observacaoValidacao = "Não reconhecido como chave de acesso nem AWB. " +
                "Guardado como documento genérico.",
        )
    }

    /**
     * O QR da DANFE/NFC-e é uma URL da SEFAZ. O formato varia por estado, mas a
     * chave aparece sempre como `chNFe=` ou como os 44 primeiros dígitos do
     * parâmetro `p=`.
     */
    private fun extrairChaveDeUrl(texto: String): String? {
        if (!texto.startsWith("http", ignoreCase = true)) return null

        Regex("""ch(?:NFe|CTe)=(\d{44})""", RegexOption.IGNORE_CASE)
            .find(texto)?.let { return it.groupValues[1] }

        Regex("""[?&]p=(\d{44})""", RegexOption.IGNORE_CASE)
            .find(texto)?.let { return it.groupValues[1] }

        // Alguns estados publicam a chave sem nome de parâmetro, colada na URL.
        return Regex("""(?<!\d)(\d{44})(?!\d)""").find(texto)?.groupValues?.get(1)
    }

    /**
     * Chave de acesso, 44 dígitos:
     *
     *   cUF  2 | AAMM 4 | CNPJ 14 | mod 2 | série 3 | nNF 9 | tpEmis 1 | cNF 8 | cDV 1
     *
     * O dígito verificador é módulo 11 com pesos cíclicos 2..9 da direita para
     * a esquerda — o mesmo do CNPJ, com a convenção de que resto 0 ou 1 vira 0.
     */
    fun deChaveAcesso(chave: String, bruto: String, simbologia: String): DocumentoFiscal {
        val uf = chave.substring(0, 2)
        val aamm = chave.substring(2, 6)
        val cnpj = chave.substring(6, 20)
        val modelo = chave.substring(20, 22)
        val serie = chave.substring(22, 25)
        val numero = chave.substring(25, 34)
        val dvInformado = chave.last().digitToInt()
        val dvCalculado = digitoVerificadorModulo11(chave.substring(0, 43))

        val competencia = runCatching {
            YearMonth.of(2000 + aamm.substring(0, 2).toInt(), aamm.substring(2, 4).toInt())
        }.getOrNull()

        val tipo = TipoDocumentoFiscal.porModelo(modelo) ?: TipoDocumentoFiscal.OUTRO
        val dvOk = dvInformado == dvCalculado

        return DocumentoFiscal(
            tipo = tipo,
            chaveAcesso = chave,
            numero = numero,
            serie = serie,
            cnpjEmitente = cnpj,
            ufEmitente = SIGLAS_UF[uf],
            competencia = competencia,
            conteudoBruto = bruto,
            simbologia = simbologia,
            validado = dvOk && tipo != TipoDocumentoFiscal.OUTRO,
            observacaoValidacao = when {
                !dvOk -> "Dígito verificador não confere (informado $dvInformado, " +
                    "calculado $dvCalculado). Chave digitada errada ou documento inválido."
                tipo == TipoDocumentoFiscal.OUTRO ->
                    "Modelo $modelo não reconhecido. Chave estruturalmente válida."
                competencia == null -> "Competência ilegível na chave."
                else -> null
            },
        )
    }

    /**
     * AWB aéreo: 3 dígitos de prefixo da cia + 7 de série + 1 verificador.
     * O verificador é o resto da divisão da série por 7 — regra da IATA.
     */
    fun deAwb(digitos: String, bruto: String, simbologia: String): DocumentoFiscal {
        val prefixo = digitos.substring(0, 3)
        val serie = digitos.substring(3, 10)
        val dvInformado = digitos.last().digitToInt()
        val dvCalculado = serie.toLong() % 7

        return DocumentoFiscal(
            tipo = TipoDocumentoFiscal.AWB,
            chaveAcesso = null,
            numero = "$prefixo-$serie$dvInformado",
            serie = prefixo,
            cnpjEmitente = null,
            ufEmitente = null,
            competencia = null,
            conteudoBruto = bruto,
            simbologia = simbologia,
            validado = dvInformado.toLong() == dvCalculado,
            observacaoValidacao =
                if (dvInformado.toLong() != dvCalculado)
                    "Dígito verificador do AWB não confere (módulo 7)."
                else null,
        )
    }

    private fun pareceAwb(texto: String): Boolean =
        Regex("""^\d{3}[-\s]?\d{8}$""").matches(texto.trim()) ||
            Regex("""^\d{11}$""").matches(texto.filter { it.isDigit() })

    /** Módulo 11 com pesos 2..9 cíclicos, da direita para a esquerda. */
    fun digitoVerificadorModulo11(base: String): Int {
        var peso = 2
        var soma = 0
        for (i in base.indices.reversed()) {
            soma += base[i].digitToInt() * peso
            peso = if (peso == 9) 2 else peso + 1
        }
        val resto = soma % 11
        return if (resto == 0 || resto == 1) 0 else 11 - resto
    }

    fun formatarCnpj(cnpj: String?): String? {
        if (cnpj == null || cnpj.length != 14) return cnpj
        return "${cnpj.substring(0, 2)}.${cnpj.substring(2, 5)}.${cnpj.substring(5, 8)}" +
            "/${cnpj.substring(8, 12)}-${cnpj.substring(12)}"
    }

    private val SIGLAS_UF = mapOf(
        "11" to "RO", "12" to "AC", "13" to "AM", "14" to "RR", "15" to "PA",
        "16" to "AP", "17" to "TO", "21" to "MA", "22" to "PI", "23" to "CE",
        "24" to "RN", "25" to "PB", "26" to "PE", "27" to "AL", "28" to "SE",
        "29" to "BA", "31" to "MG", "32" to "ES", "33" to "RJ", "35" to "SP",
        "41" to "PR", "42" to "SC", "43" to "RS", "50" to "MS", "51" to "MT",
        "52" to "GO", "53" to "DF",
    )
}

/**
 * Identidade do volume, derivada do documento.
 *
 * A etiqueta pode ser trocada no meio do trajeto (bateria, avaria); o volume
 * não muda. Ancorar o volume no documento — e não na etiqueta — é o que
 * permite substituir uma etiqueta sem perder o histórico da remessa.
 */
object IdentidadeVolume {
    fun gerar(identidadeDocumento: String, sequencia: Int): String =
        "$identidadeDocumento#V${sequencia.toString().padStart(3, '0')}"

    fun rotulo(doc: DocumentoFiscal?, sequencia: Int): String =
        doc?.let { "${it.rotuloCurto} · volume $sequencia" } ?: "Volume $sequencia"
}
