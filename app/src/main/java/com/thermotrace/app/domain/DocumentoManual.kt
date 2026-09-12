package com.thermotrace.app.domain

object DocumentoManual {
    fun criar(valor: String, chaveCompleta: Boolean): DocumentoFiscal {
        val texto = valor.trim()
        if (chaveCompleta) {
            require(texto.matches(Regex("[0-9\\s.-]+"))) { "Informe a chave com 44 dígitos." }
            val digitos = texto.filter { it in '0'..'9' }
            require(digitos.length == 44) { "A chave precisa ter 44 dígitos." }
            val doc = LeitorDocumentoFiscal.interpretar(digitos, "MANUAL")
            require(doc.validado) { doc.observacaoValidacao ?: "Confira a chave informada." }
            require(doc.ufEmitente != null && doc.competencia != null) { "Confira o estado e a data da chave informada." }
            return doc.copy(conteudoBruto = valor, validado = false,
                observacaoValidacao = "Digitado manualmente; estrutura e dígito conferidos, sem consulta fiscal.")
        }
        require(texto.matches(Regex("[0-9]{1,9}"))) { "Informe o número da nota, com até 9 dígitos, ou selecione chave completa." }
        require(texto.any { it != '0' }) { "Informe um número diferente de zero." }
        return DocumentoFiscal(TipoDocumentoFiscal.NFE, null, texto.trimStart('0'), null, null, null, null,
            valor, "MANUAL", false, "Número informado manualmente, sem chave de acesso ou validação fiscal.")
    }
}
