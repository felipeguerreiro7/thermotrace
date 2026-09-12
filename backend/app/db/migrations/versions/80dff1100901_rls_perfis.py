"""Segunda barreira de isolamento dos perfis térmicos no PostgreSQL."""
from alembic import op

revision = "80dff1100901"
down_revision = "7c430f901101"
branch_labels = None
depends_on = None


def upgrade():
    op.execute("""
      ALTER TABLE perfil_termico ENABLE ROW LEVEL SECURITY;
      ALTER TABLE perfil_termico FORCE ROW LEVEL SECURITY;
      CREATE POLICY perfil_empresa ON perfil_termico
      USING (empresa_id = nullif(current_setting('app.empresa_id', true), '')::uuid)
      WITH CHECK (empresa_id = nullif(current_setting('app.empresa_id', true), '')::uuid);
    """)


def downgrade():
    raise RuntimeError("Remoção de isolamento exige restauração revisada; sem downgrade automático.")
