"""imutabilidade da evidencia

Gatilhos que impedem UPDATE e DELETE nas tabelas de auditoria.

Por que no banco e nao no servico: um bug no Python - ou uma API
comprometida - nao consegue driblar um gatilho de banco. Se "evidencia nao
se altera" morasse so na camada de aplicacao, bastaria um UPDATE mal escrito
para destruir silenciosamente o valor probatorio de um laudo, e ninguem
descobriria ate a auditoria.

Correcao de dado se faz por registro novo + substituida_por, nunca por
alteracao.

Revision ID: 2be102580198
Revises: 3f1f10825b1b
Create Date: 2026-08-23 12:27:52.756580
"""
from collections.abc import Sequence

from alembic import op

revision: str = '2be102580198'
down_revision: str | None = '3f1f10825b1b'
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


# Evidencia pura: nao muda nunca.
TABELAS_IMUTAVEIS = [
    "leitura_etiqueta",
    "serie_medicao",
    "laudo",
    "log_auditoria",
]


def upgrade() -> None:
    op.execute(
        """
        CREATE OR REPLACE FUNCTION recusar_alteracao() RETURNS trigger
        LANGUAGE plpgsql AS $$
        BEGIN
            RAISE EXCEPTION
              'A tabela % e append-only (evidencia de auditoria). Operacao % recusada.',
              TG_TABLE_NAME, TG_OP
              USING ERRCODE = '23514',
                    HINT = 'Corrija gerando um novo registro, nunca alterando o existente.';
        END; $$;
        """
    )

    for tabela in TABELAS_IMUTAVEIS:
        op.execute(
            f"""
            CREATE TRIGGER {tabela}_imutavel
                BEFORE UPDATE OR DELETE ON {tabela}
                FOR EACH ROW EXECUTE FUNCTION recusar_alteracao();
            """
        )

    # `excursao` e quase imutavel: aceita UPDATE apenas para preencher
    # `substituida_por` quando uma reavaliacao com decodificador novo
    # substitui o veredito anterior. A avaliacao original permanece.
    op.execute(
        """
        CREATE OR REPLACE FUNCTION excursao_append_only() RETURNS trigger
        LANGUAGE plpgsql AS $$
        BEGIN
            IF TG_OP = 'DELETE' THEN
                RAISE EXCEPTION 'excursao e append-only.'
                  USING ERRCODE = '23514';
            END IF;
            IF ROW(NEW.*) IS DISTINCT FROM ROW(OLD.*)
               AND NEW.substituida_por IS NOT DISTINCT FROM OLD.substituida_por THEN
                RAISE EXCEPTION 'excursao so aceita UPDATE de substituida_por.'
                  USING ERRCODE = '23514';
            END IF;
            RETURN NEW;
        END; $$;
        """
    )
    op.execute(
        """
        CREATE TRIGGER excursao_append_only
            BEFORE UPDATE OR DELETE ON excursao
            FOR EACH ROW EXECUTE FUNCTION excursao_append_only();
        """
    )


def downgrade() -> None:
    op.execute("DROP TRIGGER IF EXISTS excursao_append_only ON excursao;")
    op.execute("DROP FUNCTION IF EXISTS excursao_append_only();")
    for tabela in TABELAS_IMUTAVEIS:
        op.execute(f"DROP TRIGGER IF EXISTS {tabela}_imutavel ON {tabela};")
    op.execute("DROP FUNCTION IF EXISTS recusar_alteracao();")
