"""TT-051: suporte da equipe ThermoTrace autorizado pelo dono, por carga e por prazo.

O dono libera uma carga específica para a empresa da plataforma, com motivo
(o chamado) e prazo de no máximo 72 horas. Reaproveita o compartilhamento do
WEB-10: o pedido fica pendente até alguém da equipe assumir (aceite com autor),
qualquer parte encerra, e as políticas de leitura da parceira já valem para a
plataforma. Suporte nunca exporta nem opera, e isso fica no banco, não só na API.
"""
from alembic import op
import sqlalchemy as sa

revision = "f10411220001"
down_revision = "e10411100001"
branch_labels = None
depends_on = None

TENANT = "nullif(current_setting('app.empresa_id', true), '')::uuid"
USER = "nullif(current_setting('app.usuario_id', true), '')::uuid"
T = "compartilhamento_remessa"


def upgrade():
    op.add_column(T, sa.Column("motivo_concessao", sa.Text()))
    op.drop_constraint("papel_conhecido", T, type_="check")
    op.create_check_constraint("papel_conhecido", T, "papel IN ('transportadora', 'suporte')")
    op.create_check_constraint("suporte_limitado", T, """papel <> 'suporte' OR (
        NOT pode_exportar AND NOT pode_operar
        AND expira_em IS NOT NULL AND expira_em <= criado_em + interval '72 hours'
        AND length(btrim(coalesce(motivo_concessao, ''))) >= 10)""")
    op.execute(f"""
      DROP POLICY compartilhamento_convite ON {T};
      CREATE POLICY compartilhamento_convite ON {T} FOR INSERT
        WITH CHECK (empresa_dona_id={TENANT} AND criado_por={USER}
          AND aceito_em IS NULL AND recusado_em IS NULL AND revogado_em IS NULL
          AND EXISTS (SELECT 1 FROM remessa r WHERE r.id={T}.remessa_id
                      AND r.empresa_embarcador_id={TENANT} AND r.codigo={T}.remessa_codigo)
          AND EXISTS (SELECT 1 FROM empresa e WHERE e.id={T}.empresa_parceira_id AND e.ativa
                      AND e.tipo::text IN (CASE WHEN {T}.papel='suporte' THEN 'plataforma' ELSE 'transportadora' END,
                                           CASE WHEN {T}.papel='suporte' THEN 'plataforma' ELSE 'ambos' END)));
    """)


def downgrade():
    raise RuntimeError("Autorização entre empresas e auditoria; restaurar backup revisado, sem downgrade automático.")
