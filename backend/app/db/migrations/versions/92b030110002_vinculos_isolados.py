"""Protege também os vínculos entre registros de empresas diferentes."""
from alembic import op

revision = "92b030110002"
down_revision = "91b030110001"
branch_labels = None
depends_on = None


def upgrade():
    tenant = "nullif(current_setting('app.empresa_id',true),'')::uuid"
    op.execute(f"""
      ALTER POLICY escopo_produto_configuracao ON produto_configuracao
      WITH CHECK (empresa_id={tenant} AND EXISTS (
        SELECT 1 FROM perfil_termico p WHERE p.id=produto_configuracao.perfil_termico_id
          AND p.empresa_id=produto_configuracao.empresa_id));
      ALTER POLICY escopo_remessa ON remessa
      WITH CHECK (empresa_embarcador_id={tenant} AND EXISTS (
        SELECT 1 FROM perfil_termico p WHERE p.id=remessa.perfil_termico_id
          AND p.empresa_id=remessa.empresa_embarcador_id)
        AND (produto_configuracao_id IS NULL OR EXISTS (
          SELECT 1 FROM produto_configuracao p WHERE p.id=remessa.produto_configuracao_id
            AND p.empresa_id=remessa.empresa_embarcador_id
            AND p.perfil_termico_id=remessa.perfil_termico_id)));
      CREATE FUNCTION proteger_identidade_volume() RETURNS trigger LANGUAGE plpgsql AS $$
      BEGIN
        IF TG_OP='DELETE' THEN
          RAISE EXCEPTION 'Volume não pode ser apagado.' USING ERRCODE='23514';
        END IF;
        IF ROW(NEW.id,NEW.remessa_id,NEW.sequencia,NEW.identidade,NEW.criado_em)
            IS DISTINCT FROM ROW(OLD.id,OLD.remessa_id,OLD.sequencia,OLD.identidade,OLD.criado_em) THEN
          RAISE EXCEPTION 'Identidade do volume é imutável.' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
      END; $$;
      CREATE TRIGGER volume_identidade_protegida BEFORE UPDATE OR DELETE ON volume
        FOR EACH ROW EXECUTE FUNCTION proteger_identidade_volume();
    """)


def downgrade():
    raise RuntimeError("Remoção de isolamento exige restauração revisada.")
