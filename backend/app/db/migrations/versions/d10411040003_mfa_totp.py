"""MFA por código de app autenticador (TOTP); obrigatória para a equipe ThermoTrace."""
from alembic import op
import sqlalchemy as sa

revision = "d10411040003"
down_revision = "c10411040002"
branch_labels = None
depends_on = None


def upgrade():
    op.add_column("usuario", sa.Column("mfa_segredo", sa.String(64)))
    op.add_column("usuario", sa.Column("mfa_ativado_em", sa.DateTime(timezone=True)))
    op.add_column("usuario", sa.Column("mfa_ultimo_passo", sa.BigInteger()))


def downgrade():
    raise RuntimeError("Proteção de acesso; restaurar backup revisado, sem downgrade automático.")
