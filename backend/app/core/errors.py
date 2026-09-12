"""Erros da aplicação e como eles viram resposta HTTP.

Formato único de erro, combinado com o app na Fase 1:

    {
      "erro": "etiqueta_ja_vinculada",
      "mensagem": "Etiqueta TT-A7K9P2X4 já está em outra remessa ativa.",
      "detalhes": {"remessa": "REM-2026-00184"},
      "request_id": "a3f91c07e2b41d55"
    }

`erro` é um código estável — é nele que o `when` do Kotlin decide o que fazer.
`mensagem` é a frase pronta para a tela. **O app não monta texto de erro.**
Se a redação melhorar, melhora para todo mundo sem publicar APK novo.
"""

from typing import Any

from fastapi import FastAPI, Request, status
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from sqlalchemy.exc import IntegrityError
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.core.logging import obter_logger, obter_request_id

log = obter_logger("erros")


class ErroApp(Exception):
    """Base de todo erro previsto. Erro previsto não é exceção de programa."""

    codigo = "erro_interno"
    http = status.HTTP_500_INTERNAL_SERVER_ERROR
    mensagem = "Erro interno."

    def __init__(
        self,
        mensagem: str | None = None,
        detalhes: dict[str, Any] | None = None,
    ) -> None:
        self.mensagem = mensagem or self.mensagem
        self.detalhes = detalhes or {}
        super().__init__(self.mensagem)


class NaoEncontrado(ErroApp):
    codigo = "nao_encontrado"
    http = status.HTTP_404_NOT_FOUND
    mensagem = "Registro não encontrado."


class DadosInvalidos(ErroApp):
    codigo = "dados_invalidos"
    http = status.HTTP_422_UNPROCESSABLE_CONTENT
    mensagem = "Dados inválidos."


class Conflito(ErroApp):
    """Conflito exige resolução. Replay idempotente retorna o sucesso original, não 409."""

    codigo = "conflito"
    http = status.HTTP_409_CONFLICT
    mensagem = "Registro já existe."


class NaoAutenticado(ErroApp):
    codigo = "nao_autenticado"
    http = status.HTTP_401_UNAUTHORIZED
    mensagem = "Credenciais ausentes ou inválidas."


class SemPermissao(ErroApp):
    codigo = "sem_permissao"
    http = status.HTTP_403_FORBIDDEN
    mensagem = "Sua empresa não tem acesso a este recurso."


class RegraDeNegocio(ErroApp):
    """A operação é sintaticamente válida mas o domínio recusa.

    Ex.: ativar etiqueta com bateria abaixo do piso, ler histórico de volume
    sem ativação registrada.
    """

    codigo = "regra_de_negocio"
    http = status.HTTP_422_UNPROCESSABLE_CONTENT
    mensagem = "Operação recusada pela regra de negócio."


class EvidenciaImutavel(ErroApp):
    """Tentativa de alterar dado de auditoria.

    Não deveria acontecer: o banco tem trigger que bloqueia. Se chegar aqui,
    é bug nosso — e é registrado como erro, não como aviso.
    """

    codigo = "evidencia_imutavel"
    http = status.HTTP_409_CONFLICT
    mensagem = "Evidência de auditoria não pode ser alterada."


def _resposta(codigo: str, mensagem: str, http: int, detalhes: dict) -> JSONResponse:
    return JSONResponse(
        status_code=http,
        content={
            "erro": codigo,
            "mensagem": mensagem,
            "detalhes": detalhes,
            "request_id": obter_request_id(),
        },
    )


def registrar_tratadores(app: FastAPI) -> None:
    """Liga os tratadores. Chamado uma vez, em main.py."""

    @app.exception_handler(ErroApp)
    async def _app(_: Request, exc: ErroApp):
        log.info("erro_previsto", codigo=exc.codigo, mensagem=exc.mensagem)
        return _resposta(exc.codigo, exc.mensagem, exc.http, exc.detalhes)

    @app.exception_handler(StarletteHTTPException)
    async def _http(_: Request, exc: StarletteHTTPException):
        # Sem este tratador, um 404 de rota inexistente sai como
        # {"detail": "Not Found"} — o formato do FastAPI, não o nosso. O app
        # leria `erro` e encontraria nulo. Erro tem que ter UM formato só.
        codigos = {
            401: "nao_autenticado",
            403: "sem_permissao",
            404: "nao_encontrado",
            405: "metodo_nao_permitido",
            409: "conflito",
            429: "muitas_requisicoes",
        }
        mensagens = {
            401: "Credenciais ausentes ou inválidas.",
            403: "Sua empresa não tem acesso a este recurso.",
            404: "Recurso não encontrado.",
            405: "Método não permitido para este endereço.",
            429: "Requisições demais. Tente novamente em instantes.",
        }
        return _resposta(
            codigos.get(exc.status_code, "erro_http"),
            mensagens.get(exc.status_code, str(exc.detail)),
            exc.status_code,
            {},
        )

    @app.exception_handler(RequestValidationError)
    async def _validacao(_: Request, exc: RequestValidationError):
        campos = [
            {
                "campo": ".".join(str(p) for p in e["loc"][1:]) or "corpo",
                "problema": e["msg"],
            }
            for e in exc.errors()
        ]
        log.info("erro_validacao", campos=campos)
        return _resposta(
            "dados_invalidos",
            "Alguns campos não passaram na validação.",
            status.HTTP_422_UNPROCESSABLE_CONTENT,
            {"campos": campos},
        )

    @app.exception_handler(IntegrityError)
    async def _integridade(_: Request, exc: IntegrityError):
        # As restrições UNIQUE do banco são a última linha de defesa contra
        # etiqueta duplicada, sessão duplicada e alerta repetido. Quando uma
        # dispara, respondemos 409 — que o app já sabe tratar.
        texto = str(getattr(exc, "orig", exc))
        diag = getattr(getattr(exc, "orig", None), "diag", None)
        log.warning("violacao_integridade", restricao=getattr(diag, "constraint_name", None))

        # Os nomes abaixo vêm da convenção definida em `app/db/base.py` e
        # foram conferidos contra o banco (`pg_constraint`). Se um deles
        # estiver errado, a mensagem amigável simplesmente nunca dispara e o
        # operador recebe "conflito" genérico — por isso existe um teste que
        # compara esta lista com as restrições reais.
        conhecidas = {
            "uq_etiqueta_nfc_uid": (
                "etiqueta_ja_cadastrada",
                "Já existe uma etiqueta cadastrada com este UID NFC.",
            ),
            "uq_etiqueta_serial": (
                "serial_ja_cadastrado",
                "Já existe uma etiqueta com este código impresso.",
            ),
            "uq_etiqueta_qr_payload": (
                "qr_ja_cadastrado",
                "Já existe uma etiqueta com este QR.",
            ),
            "uq_vinculo_etiqueta_ativo": (
                "etiqueta_ja_vinculada",
                "Esta etiqueta já está vinculada a outro volume ativo.",
            ),
            "uq_leitura_etiqueta_chave_idempotencia": (
                "leitura_ja_recebida",
                "Esta leitura já foi recebida.",
            ),
            "uq_leitura_sessao_hash": (
                "leitura_ja_recebida",
                "Esta leitura já foi recebida.",
            ),
            "uq_sessao_etiqueta_epoch": (
                "sessao_ja_registrada",
                "Esta ativação já foi registrada.",
            ),
            "uq_ocorrencia_chave_natural": (
                "ocorrencia_ja_registrada",
                "Esta ocorrência já foi registrada.",
            ),
            "uq_documento_remessa_identidade": (
                "documento_ja_vinculado",
                "Este documento já está vinculado a esta remessa.",
            ),
            "uq_remessa_embarcador_codigo": (
                "codigo_remessa_repetido",
                "Já existe uma remessa com este código.",
            ),
            "uq_usuario_email": (
                "email_ja_cadastrado",
                "Já existe um usuário com este e-mail.",
            ),
            "uq_empresa_cnpj": (
                "cnpj_ja_cadastrado",
                "Já existe uma empresa com este CNPJ.",
            ),
            "uq_dispositivo_empresa_install": (
                "dispositivo_ja_registrado",
                "Este aparelho já está registrado nesta empresa.",
            ),
            "uq_destinatario_empresa_email": (
                "destinatario_ja_cadastrado",
                "Este e-mail já recebe alertas desta empresa.",
            ),
        }
        for restricao, (codigo, msg) in conhecidas.items():
            if restricao in texto:
                return _resposta(codigo, msg, status.HTTP_409_CONFLICT, {})

        return _resposta(
            "conflito",
            "A operação viola uma restrição de integridade dos dados.",
            status.HTTP_409_CONFLICT,
            {},
        )

    @app.exception_handler(Exception)
    async def _inesperado(_: Request, exc: Exception):
        # Erro não previsto: log completo com stack, resposta genérica.
        # Nunca devolver traceback ao cliente — entrega estrutura interna
        # de graça para quem estiver sondando.
        log.exception("erro_inesperado", tipo=type(exc).__name__)
        return _resposta(
            "erro_interno",
            "Erro interno. A equipe foi notificada. "
            "Informe o request_id ao pedir suporte.",
            status.HTTP_500_INTERNAL_SERVER_ERROR,
            {},
        )
