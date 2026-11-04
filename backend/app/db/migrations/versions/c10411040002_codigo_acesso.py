"""WEB-11: código de acesso de uso único para convite e redefinição de senha.

Reaproveita token_recuperacao (criada no schema inicial e sem uso até aqui).
Nenhum código antigo existe; as colunas novas não reinterpretam dados.
"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql as pg

revision = "c10411040002"
down_revision = "b10411040001"
branch_labels = None
depends_on = None


def upgrade():
    op.add_column("token_recuperacao", sa.Column("finalidade", sa.String(16), nullable=False, server_default="redefinicao"))
    op.add_column("token_recuperacao", sa.Column("criado_por", pg.UUID(as_uuid=True), sa.ForeignKey("usuario.id")))
    op.add_column("token_recuperacao", sa.Column("revogado_em", sa.DateTime(timezone=True)))
    op.create_check_constraint("finalidade_conhecida", "token_recuperacao", "finalidade IN ('convite','redefinicao')")


def downgrade():
    raise RuntimeError("Credenciais de acesso; restaurar backup revisado, sem downgrade automático.")
