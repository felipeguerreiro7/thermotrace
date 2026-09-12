"""Cargas, documentos de entregas parciais, configuração de produto e idempotência."""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql as pg

revision = "91b030110001"
down_revision = "80dff1100901"
branch_labels = None
depends_on = None


def upgrade():
    op.create_table("produto_configuracao",
        sa.Column("id", pg.UUID(as_uuid=True), primary_key=True, server_default=sa.text("gen_random_uuid()")),
        sa.Column("criado_em", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.Column("empresa_id", pg.UUID(as_uuid=True), sa.ForeignKey("empresa.id"), nullable=False),
        sa.Column("codigo", sa.String(64), nullable=False),
        sa.Column("versao", sa.Integer(), nullable=False),
        sa.Column("perfil_termico_id", pg.UUID(as_uuid=True), sa.ForeignKey("perfil_termico.id"), nullable=False),
        sa.Column("intervalo_segundos", sa.Integer(), nullable=False),
        sa.Column("criado_por", pg.UUID(as_uuid=True), sa.ForeignKey("usuario.id"), nullable=False),
        sa.Column("ativo", sa.Boolean(), nullable=False, server_default=sa.text("true")),
        sa.UniqueConstraint("empresa_id", "codigo", "versao", name="uq_produto_empresa_codigo_versao"),
        sa.CheckConstraint("intervalo_segundos BETWEEN 1 AND 86400", name="intervalo_valido"),
        sa.CheckConstraint("versao > 0", name="versao_positiva"))
    op.create_index("uq_produto_empresa_codigo_ativo", "produto_configuracao", ["empresa_id", "codigo"],
                    unique=True, postgresql_where=sa.text("ativo IS true"))
    op.drop_constraint("uq_remessa_identidade_documento", "remessa", type_="unique")
    op.alter_column("remessa", "codigo", type_=sa.String(64), existing_type=sa.String(32))
    op.add_column("remessa", sa.Column("produto_configuracao_id", pg.UUID(as_uuid=True), sa.ForeignKey("produto_configuracao.id")))
    op.add_column("remessa", sa.Column("criterio_snapshot", pg.JSONB()))
    op.add_column("documento", sa.Column("identidade_hash", sa.String(64)))
    op.create_index("uq_documento_remessa_identidade", "documento", ["remessa_id", "identidade_hash"],
                    unique=True, postgresql_where=sa.text("identidade_hash IS NOT NULL"))
    op.add_column("chave_idempotencia", sa.Column("empresa_id", pg.UUID(as_uuid=True), sa.ForeignKey("empresa.id")))
    op.add_column("chave_idempotencia", sa.Column("requisicao_sha256", sa.String(64)))
    op.drop_constraint("uq_chave_idempotencia_chave", "chave_idempotencia", type_="unique")
    op.create_index("uq_idempotencia_escopo", "chave_idempotencia", ["empresa_id", "usuario_id", "endpoint", "chave"],
                    unique=True, postgresql_where=sa.text("empresa_id IS NOT NULL"))
    op.create_index("uq_idempotencia_legado", "chave_idempotencia", ["chave"],
                    unique=True, postgresql_where=sa.text("empresa_id IS NULL"))
    # Imutabilidade de novas configurações; registros legados não ganham evidência inventada.
    op.execute("""
      CREATE FUNCTION proteger_produto_configuracao() RETURNS trigger LANGUAGE plpgsql AS $$
      BEGIN
        IF TG_OP='DELETE' THEN
          RAISE EXCEPTION 'Configuração de produto não pode ser apagada.' USING ERRCODE='23514';
        END IF;
        IF (to_jsonb(NEW)-'ativo') IS DISTINCT FROM (to_jsonb(OLD)-'ativo') THEN
          RAISE EXCEPTION 'Configuração exige nova versão.' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
      END; $$;
      CREATE TRIGGER produto_configuracao_protegida BEFORE UPDATE OR DELETE ON produto_configuracao
        FOR EACH ROW EXECUTE FUNCTION proteger_produto_configuracao();
      CREATE FUNCTION proteger_criterio_remessa() RETURNS trigger LANGUAGE plpgsql AS $$
      BEGIN
        IF TG_OP='DELETE' THEN
          RAISE EXCEPTION 'Remessa deve ser cancelada, não apagada.' USING ERRCODE='23514';
        END IF;
        IF OLD.criterio_snapshot IS NOT NULL AND
           (to_jsonb(NEW)-'status'-'atualizado_em') IS DISTINCT FROM
           (to_jsonb(OLD)-'status'-'atualizado_em') THEN
          RAISE EXCEPTION 'Identidade e critério da remessa são imutáveis.' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
      END; $$;
      CREATE TRIGGER remessa_criterio_protegido BEFORE UPDATE OR DELETE ON remessa
        FOR EACH ROW EXECUTE FUNCTION proteger_criterio_remessa();
      CREATE TRIGGER documento_imutavel BEFORE UPDATE OR DELETE ON documento
        FOR EACH ROW EXECUTE FUNCTION recusar_alteracao();
      CREATE TRIGGER idempotencia_imutavel BEFORE UPDATE OR DELETE ON chave_idempotencia
        FOR EACH ROW EXECUTE FUNCTION recusar_alteracao();
    """)
    tenant = "nullif(current_setting('app.empresa_id', true), '')::uuid"
    user = "nullif(current_setting('app.usuario_id', true), '')::uuid"
    policies = {
        "produto_configuracao": f"empresa_id={tenant}",
        "remessa": f"empresa_embarcador_id={tenant}",
        "documento": "EXISTS (SELECT 1 FROM remessa r WHERE r.id=documento.remessa_id)",
        "volume": "EXISTS (SELECT 1 FROM remessa r WHERE r.id=volume.remessa_id)",
        "chave_idempotencia": f"empresa_id={tenant} AND usuario_id={user}",
    }
    for table, policy in policies.items():
        op.execute(f"ALTER TABLE {table} ENABLE ROW LEVEL SECURITY")
        op.execute(f"ALTER TABLE {table} FORCE ROW LEVEL SECURITY")
        op.execute(f"CREATE POLICY escopo_{table} ON {table} USING ({policy}) WITH CHECK ({policy})")


def downgrade():
    raise RuntimeError("Migração de segurança e histórico; restaurar backup revisado, sem downgrade automático.")
