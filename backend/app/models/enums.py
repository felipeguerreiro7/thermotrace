"""Tipos enumerados.

São ENUM nativos do PostgreSQL, não `varchar` com `CHECK`. O banco recusa um
valor inexistente, o que evita que um bug de digitação vire estado inválido
persistido — e num sistema de auditoria, estado inválido persistido é dado
que não se explica depois.

Os valores em minúsculo batem com o que o app Android envia.
"""

from enum import StrEnum


class TipoEmpresa(StrEnum):
    EMBARCADOR = "embarcador"
    TRANSPORTADORA = "transportadora"
    AMBOS = "ambos"
    PLATAFORMA = "plataforma"


class PapelUsuario(StrEnum):
    OPERADOR = "operador"      # bipa etiqueta, cria remessa
    GESTOR = "gestor"          # tudo do operador + laudos e destinatários
    ADMIN = "admin"            # administração da plataforma; sem acesso implícito às evidências


class EstadoEtiqueta(StrEnum):
    ESTOQUE = "estoque"
    VINCULADA = "vinculada"
    MONITORANDO = "monitorando"
    LIDA = "lida"
    MANUTENCAO = "manutencao"
    APOSENTADA = "aposentada"


class StatusRemessa(StrEnum):
    PREPARACAO = "preparacao"
    AGUARDANDO_ACEITE = "aguardando_aceite"
    AGUARDANDO_COLETA = "aguardando_coleta"
    EM_TRANSPORTE = "em_transporte"
    ENTREGUE_AGUARDANDO_LEITURA = "entregue_aguardando_leitura"
    CONCLUIDA = "concluida"
    CANCELADA = "cancelada"


class StatusVolume(StrEnum):
    SEM_ETIQUETA = "sem_etiqueta"
    VINCULADO = "vinculado"
    MONITORANDO = "monitorando"
    ENCERRADO = "encerrado"


class StatusSessao(StrEnum):
    ATIVA = "ativa"
    ENCERRADA_NORMAL = "encerrada_normal"
    ENCERRADA_ANORMAL = "encerrada_anormal"
    ABANDONADA = "abandonada"


class TipoLeitura(StrEnum):
    ATIVACAO = "ativacao"       # obrigatória, escreve na etiqueta
    CHECKPOINT = "checkpoint"   # opcional, só lê
    FINAL = "final"             # obrigatória, só lê, encerra


class BaseDeTempo(StrEnum):
    """Convenção do epoch gravado na etiqueta no START.

    Os dois SDKs do fabricante divergem: o Android grava o instante do START,
    o iOS grava START + delay. Com delay = 0 as duas coincidem e o problema
    fica invisível.
    """

    INSTANTE_START = "instante_start"     # Android
    PRIMEIRA_JANELA = "primeira_janela"   # iOS


class Plataforma(StrEnum):
    ANDROID = "android"
    IOS = "ios"


class ParteCustodia(StrEnum):
    EMBARCADOR = "embarcador"
    TRANSPORTADORA = "transportadora"
    DESTINATARIO = "destinatario"


class OrigemEvento(StrEnum):
    """Como o fato entrou no sistema.

    A auditoria precisa distinguir "a etiqueta registrou" de "alguém digitou
    ontem à noite". Colapsar os três destrói o valor do laudo.
    """

    AUTOMATICO_NFC = "automatico_nfc"
    CONFIRMADO_TEMPO_REAL = "confirmado_tempo_real"
    INFORMADO_POSTERIORMENTE = "informado_posteriormente"


class TipoExcursao(StrEnum):
    ACIMA = "acima"
    ABAIXO = "abaixo"


class Gravidade(StrEnum):
    ALERTA = "alerta"
    ACAO = "acao"
    CRITICA = "critica"


class TipoOcorrencia(StrEnum):
    TERMICA = "termica"
    AVARIA = "avaria"
    ATRASO = "atraso"
    VOLUME_FALTANTE = "volume_faltante"
    DIVERGENCIA_DOCUMENTAL = "divergencia_documental"
    ETIQUETA_INATIVA = "etiqueta_inativa"
    BATERIA_BAIXA = "bateria_baixa"
    OUTRA = "outra"


class StatusOcorrencia(StrEnum):
    ABERTA = "aberta"
    ALERTA_ENVIADO = "alerta_enviado"
    EM_TRATAMENTO = "em_tratamento"
    RESOLVIDA = "resolvida"
    SEM_ACAO = "sem_acao"


class TipoAcaoCorretiva(StrEnum):
    TROCA_REFRIGERANTE = "troca_refrigerante"
    REACONDICIONAMENTO = "reacondicionamento"
    CAMARA_FRIA = "camara_fria"
    TROCA_EMBALAGEM = "troca_embalagem"
    CLIENTE_COMUNICADO = "cliente_comunicado"
    TRANSPORTE_RECUSADO = "transporte_recusado"
    PRODUTO_SEGREGADO = "produto_segregado"
    NENHUMA = "nenhuma"
    OUTRA = "outra"


class StatusEnvio(StrEnum):
    FILA = "fila"
    ENVIADO = "enviado"
    FALHA = "falha"
    DESCARTADO = "descartado"


class CategoriaArquivo(StrEnum):
    LAUDO = "laudo"
    EVIDENCIA = "evidencia"
    CERTIFICADO = "certificado"


class TipoDocumento(StrEnum):
    NFE = "nfe"
    NFCE = "nfce"
    CTE = "cte"
    CTE_OS = "cte_os"
    MDFE = "mdfe"
    AWB = "awb"
    PEDIDO = "pedido"
    OUTRO = "outro"
    SEM_DOCUMENTO = "sem_documento"


class VereditoLaudo(StrEnum):
    CONFORME = "conforme"
    ALERTA = "alerta"
    EXCURSAO = "excursao"
    INCONCLUSIVO = "inconclusivo"
