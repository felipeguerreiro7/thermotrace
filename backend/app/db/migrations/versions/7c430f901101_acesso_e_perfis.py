"""Acesso, perfis com evidência e correção da proteção de excursões."""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql as pg

revision = "7c430f901101"
down_revision = "2be102580198"
branch_labels = None
depends_on = None


def upgrade():
    # Falha transacionalmente se o legado contiver e-mails equivalentes; requer saneamento explícito.
    op.execute("UPDATE usuario SET email = lower(trim(email))")
    op.create_index("uq_usuario_email_normalizado", "usuario", [sa.text("lower(trim(email))")], unique=True)
    op.create_table("limite_login",
        sa.Column("chave", sa.String(64), primary_key=True),
        sa.Column("inicio", sa.DateTime(timezone=True), nullable=False),
        sa.Column("tentativas", sa.Integer(), nullable=False))
    op.add_column("perfil_termico", sa.Column("evidencia", pg.JSONB()))
    op.add_column("perfil_termico", sa.Column("aprovado_em", sa.DateTime(timezone=True)))
    op.add_column("perfil_termico", sa.Column("aprovado_por", pg.UUID(as_uuid=True), sa.ForeignKey("usuario.id")))
    op.alter_column("perfil_termico", "ativo", server_default=sa.text("false"))
    # Registros anteriores não possuem aprovação comprovada. Preservar critério histórico,
    # mas retirar da seleção para NOVAS operações até revisão explícita.
    op.execute("UPDATE perfil_termico SET ativo = false")
    op.drop_constraint("uq_perfil_termico_codigo_versao", "perfil_termico", type_="unique")
    op.create_index("uq_perfil_cliente_versao", "perfil_termico", ["empresa_id", "codigo", "versao"],
                    unique=True, postgresql_where=sa.text("empresa_id IS NOT NULL"))
    op.create_index("uq_perfil_global_versao", "perfil_termico", ["codigo", "versao"],
                    unique=True, postgresql_where=sa.text("empresa_id IS NULL"))
    op.execute("""
      CREATE FUNCTION proteger_perfil_aprovado() RETURNS trigger LANGUAGE plpgsql AS $$
      BEGIN
        IF TG_OP = 'DELETE' THEN
          RAISE EXCEPTION 'Perfil deve ser retirado de uso, nunca apagado.' USING ERRCODE='23514';
        END IF;
        IF OLD.aprovado_em IS NOT NULL AND
           (to_jsonb(NEW) - 'ativo' - 'atualizado_em') IS DISTINCT FROM
           (to_jsonb(OLD) - 'ativo' - 'atualizado_em') THEN
          RAISE EXCEPTION 'Critério aprovado exige nova versão.' USING ERRCODE='23514';
        END IF;
        IF NEW.ativo AND (NEW.aprovado_em IS NULL OR NEW.aprovado_por IS NULL OR NEW.evidencia IS NULL) THEN
          RAISE EXCEPTION 'Perfil ativo exige aprovação e evidência.' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
      END; $$;
      CREATE TRIGGER perfil_aprovado_protegido BEFORE INSERT OR UPDATE OR DELETE ON perfil_termico
        FOR EACH ROW EXECUTE FUNCTION proteger_perfil_aprovado();
    """)
    # O gatilho anterior permitia mudar outros campos junto com substituida_por.
    op.execute("""
      CREATE OR REPLACE FUNCTION excursao_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
      BEGIN
        IF TG_OP = 'DELETE' THEN
          RAISE EXCEPTION 'excursao e append-only.' USING ERRCODE='23514';
        END IF;
        IF (to_jsonb(NEW) - 'substituida_por') IS DISTINCT FROM (to_jsonb(OLD) - 'substituida_por')
           OR (OLD.substituida_por IS NOT NULL AND NEW.substituida_por IS DISTINCT FROM OLD.substituida_por)
           OR NEW.substituida_por = NEW.id THEN
          RAISE EXCEPTION 'Apenas primeira substituição, sem alterar evidência.' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
      END; $$;
    """)


def downgrade():
    raise RuntimeError("Migração de segurança não tem rollback automático; restaurar backup revisado.")
