"""O mapa de restrições em `errors.py` bate com o banco?

Este teste existe porque escrevi o mapa de memória e cinco dos sete nomes
estavam errados. O sintoma seria silencioso: em vez da mensagem certa, o
operador receberia "conflito" genérico — e ninguém descobriria até alguém
reclamar do suporte.

Nome de restrição é um acoplamento entre código e banco. Acoplamento que
ninguém verifica é acoplamento que quebra.
"""

from sqlalchemy import text

from app.core.errors import registrar_tratadores  # noqa: F401  (garante import)


def _mapa_de_restricoes() -> dict[str, tuple[str, str]]:
    """Extrai o dicionário `conhecidas` de dentro do tratador.

    Ele é local à função por design (não é configuração, é detalhe do
    tratador), então o teste lê o código-fonte em vez de importar.
    """
    import inspect
    import re

    from app.core import errors

    fonte = inspect.getsource(errors.registrar_tratadores)
    return {m: ("", "") for m in re.findall(r'"(uq_[a-z0-9_]+)"', fonte)}


def test_todas_as_restricoes_mapeadas_existem_no_banco(sessao):
    reais = {
        linha[0]
        for linha in sessao.execute(
            text(
                "SELECT conname FROM pg_constraint "
                "WHERE connamespace = 'public'::regnamespace "
                "UNION "
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'public'"
            )
        ).all()
    }
    mapeadas = set(_mapa_de_restricoes())

    assert mapeadas, "o mapa de restrições ficou vazio — o regex quebrou?"
    faltando = mapeadas - reais
    assert not faltando, (
        f"Estas restrições estão mapeadas em errors.py mas não existem no "
        f"banco: {sorted(faltando)}. A mensagem amigável nunca vai disparar."
    )


def test_restricoes_criticas_estao_cobertas():
    """As restrições que o app precisa distinguir para decidir o que fazer.

    Sem mensagem específica nestas, o operador não sabe se reaproxima a
    etiqueta, se troca de etiqueta ou se liga para o suporte.
    """
    criticas = {
        "uq_etiqueta_nfc_uid",
        "uq_vinculo_etiqueta_ativo",
        "uq_leitura_etiqueta_chave_idempotencia",
        "uq_sessao_etiqueta_epoch",
        "uq_ocorrencia_chave_natural",
    }
    assert criticas <= set(_mapa_de_restricoes())
