package com.thermotrace.app.domain

import java.time.Duration
import java.time.Instant

/**
 * Ocorrência e ação corretiva.
 *
 * Uma nota sobre o que este modelo NÃO promete: a etiqueta não tem rádio.
 * Ela registra sozinha, mas não avisa ninguém. Uma excursão só é *descoberta*
 * quando alguém encosta o celular — num checkpoint ou na leitura final.
 *
 * Por isso o alerta carrega três instantes distintos, e confundi-los é o erro
 * que mais destrói a credibilidade de um laudo:
 *
 *   detectadoEm  quando o app leu a etiqueta e viu o desvio
 *   ocorridoEm   quando a temperatura de fato saiu da faixa (vem da série)
 *   registradoEm quando o sistema gravou a ocorrência
 *
 * O e-mail diz as três coisas. "Excursão detectada agora, ocorrida há 6 horas"
 * é uma frase honesta; "excursão agora" seria mentira.
 */

enum class TipoOcorrencia(val rotulo: String) {
    TERMICA("Excursão térmica"),
    AVARIA("Avaria"),
    ATRASO("Atraso"),
    VOLUME_FALTANTE("Volume faltante"),
    DIVERGENCIA_DOCUMENTAL("Divergência documental"),
    ETIQUETA_INATIVA("Etiqueta inativa"),
    BATERIA_BAIXA("Bateria baixa"),
    OUTRA("Outra"),
}

enum class StatusOcorrencia(val rotulo: String) {
    ABERTA("Aberta"),
    ALERTA_ENVIADO("Alerta enviado"),
    EM_TRATAMENTO("Em tratamento"),
    RESOLVIDA("Resolvida"),
    SEM_ACAO("Encerrada sem ação"),
}

/** Catálogo da especificação funcional §9. */
enum class TipoAcaoCorretiva(val rotulo: String) {
    TROCA_REFRIGERANTE("Troca de elementos refrigerantes"),
    REACONDICIONAMENTO("Reacondicionamento"),
    CAMARA_FRIA("Transferência para câmara fria"),
    TROCA_EMBALAGEM("Transferência de embalagem"),
    CLIENTE_COMUNICADO("Cliente comunicado"),
    TRANSPORTE_RECUSADO("Transporte recusado"),
    PRODUTO_SEGREGADO("Produto segregado para avaliação"),
    NENHUMA("Nenhuma ação necessária"),
    OUTRA("Outra"),
}

enum class PapelDestinatario(val rotulo: String) {
    EMBARCADOR("Embarcador"),
    TRANSPORTADORA("Transportadora"),
    DESTINATARIO("Destinatário"),
    QUALIDADE("Qualidade / RT"),
    OUTRO("Outro"),
}

data class DestinatarioAlerta(
    val id: String,
    val nome: String,
    val email: String,
    val papel: PapelDestinatario,
    /** Só recebe alerta desta gravidade para cima. Evita fadiga de alerta. */
    val gravidadeMinima: GravidadeExcursao,
    val ativo: Boolean,
) {
    fun recebe(gravidade: GravidadeExcursao): Boolean =
        ativo && gravidade.ordinal >= gravidadeMinima.ordinal
}

/**
 * Conteúdo do e-mail de alerta.
 *
 * Fica no domínio, não na camada de UI nem na de rede, porque o mesmo texto
 * precisa sair por três caminhos: o backend (caminho normal), o app de e-mail
 * do operador (contingência sem backend) e o laudo em Excel.
 */
data class AlertaTermico(
    val ocorrenciaId: String,
    val codigoRemessa: String,
    val documento: String,
    val volume: String,
    val etiquetaSerial: String,
    val perfil: PerfilTermico,
    val gravidade: GravidadeExcursao,
    val tipo: TipoExcursao,
    val picoC: Double,
    val limiteC: Double,
    val duracao: Duration,
    val ocorridoEm: Instant,
    val detectadoEm: Instant,
    val tempoForaFaixaTotal: Duration,
    val mktC: Double?,
    val quantidadeExcursoes: Int,
    val transportadora: String,
    val destinatario: String,
    val destinatarios: List<DestinatarioAlerta>,
) {
    val atrasoDeteccao: Duration get() = Duration.between(ocorridoEm, detectadoEm)

    /** Uma excursão descoberta muito depois já não admite ação corretiva útil. */
    val acaoAindaUtil: Boolean get() = atrasoDeteccao.toHours() < 12
}
