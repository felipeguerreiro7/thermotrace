"""WEB-10: compartilhamento explícito de carga entre dono e transportadora.

Cria a autorização por carga (convite → aceite/recusa → revogação), dá à
parceira somente leitura sobre a carga concedida e torna explícito o dono em
todas as políticas de escrita que antes dependiam da visibilidade da remessa.
Sem isso, abrir a leitura da remessa para a parceira abriria também a escrita
de documentos, volumes e evidências por herança de política.
"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql as pg

revision = "b10411040001"
down_revision = "a20409120002"
branch_labels = None
depends_on = None

TENANT = "nullif(current_setting('app.empresa_id', true), '')::uuid"
USER = "nullif(current_setting('app.usuario_id', true), '')::uuid"


def vigente(coluna):
    """Compartilhamento aceito, não recusado, não revogado e dentro do prazo, para a empresa atual."""
    return f"""EXISTS (SELECT 1 FROM compartilhamento_remessa c
        WHERE c.remessa_id={coluna} AND c.empresa_parceira_id={TENANT}
          AND c.aceito_em IS NOT NULL AND c.recusado_em IS NULL AND c.revogado_em IS NULL
          AND (c.expira_em IS NULL OR c.expira_em > now()))"""


def upgrade():
    op.create_table("compartilhamento_remessa",
        sa.Column("id", pg.UUID(as_uuid=True), primary_key=True, server_default=sa.text("gen_random_uuid()")),
        sa.Column("criado_em", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.Column("remessa_id", pg.UUID(as_uuid=True), sa.ForeignKey("remessa.id"), nullable=False),
        sa.Column("remessa_codigo", sa.String(64), nullable=False),
        sa.Column("empresa_dona_id", pg.UUID(as_uuid=True), sa.ForeignKey("empresa.id"), nullable=False),
        sa.Column("empresa_parceira_id", pg.UUID(as_uuid=True), sa.ForeignKey("empresa.id"), nullable=False),
        sa.Column("papel", sa.String(32), nullable=False, server_default="transportadora"),
        sa.Column("pode_exportar", sa.Boolean(), nullable=False, server_default=sa.text("false")),
        sa.Column("expira_em", sa.DateTime(timezone=True)),
        sa.Column("criado_por", pg.UUID(as_uuid=True), sa.ForeignKey("usuario.id"), nullable=False),
        sa.Column("aceito_em", sa.DateTime(timezone=True)),
        sa.Column("aceito_por", pg.UUID(as_uuid=True), sa.ForeignKey("usuario.id")),
        sa.Column("recusado_em", sa.DateTime(timezone=True)),
        sa.Column("recusado_por", pg.UUID(as_uuid=True), sa.ForeignKey("usuario.id")),
        sa.Column("revogado_em", sa.DateTime(timezone=True)),
        sa.Column("revogado_por", pg.UUID(as_uuid=True), sa.ForeignKey("usuario.id")),
        sa.Column("motivo_revogacao", sa.Text()),
        sa.CheckConstraint("empresa_dona_id <> empresa_parceira_id", name="partes_distintas"),
        sa.CheckConstraint("papel = 'transportadora'", name="papel_conhecido"),
        sa.CheckConstraint("(aceito_em IS NULL) = (aceito_por IS NULL)", name="aceite_com_autor"),
        sa.CheckConstraint("(recusado_em IS NULL) = (recusado_por IS NULL)", name="recusa_com_autor"),
        sa.CheckConstraint("(revogado_em IS NULL) = (revogado_por IS NULL)", name="revogacao_com_autor"),
        sa.CheckConstraint("NOT (aceito_em IS NOT NULL AND recusado_em IS NOT NULL)", name="aceite_ou_recusa"),
        sa.CheckConstraint("expira_em IS NULL OR expira_em > criado_em", name="prazo_futuro"))
    op.create_index("uq_compartilhamento_vigente", "compartilhamento_remessa",
                    ["remessa_id", "empresa_parceira_id"], unique=True,
                    postgresql_where=sa.text("revogado_em IS NULL AND recusado_em IS NULL"))
    op.create_index("ix_compartilhamento_parceira", "compartilhamento_remessa", ["empresa_parceira_id", "criado_em"])
    op.create_index("ix_compartilhamento_remessa", "compartilhamento_remessa", ["remessa_id"])

    # Identidade congelada; aceite, recusa e revogação são de escrita única e
    # cada uma só pode ser feita pela parte a quem cabe.
    op.execute(f"""
      CREATE FUNCTION proteger_compartilhamento() RETURNS trigger LANGUAGE plpgsql AS $$
      DECLARE
        tenant uuid := {TENANT};
        estado text[] := ARRAY['aceito_em','aceito_por','recusado_em','recusado_por',
                               'revogado_em','revogado_por','motivo_revogacao'];
      BEGIN
        IF TG_OP='DELETE' THEN
          RAISE EXCEPTION 'Compartilhamento não pode ser apagado; revogar.' USING ERRCODE='23514';
        END IF;
        IF (to_jsonb(NEW)-estado) IS DISTINCT FROM (to_jsonb(OLD)-estado) THEN
          RAISE EXCEPTION 'Identidade do compartilhamento é imutável.' USING ERRCODE='23514';
        END IF;
        IF (OLD.aceito_em IS NOT NULL AND NEW.aceito_em IS DISTINCT FROM OLD.aceito_em)
           OR (OLD.recusado_em IS NOT NULL AND NEW.recusado_em IS DISTINCT FROM OLD.recusado_em)
           OR (OLD.revogado_em IS NOT NULL AND ROW(NEW.revogado_em,NEW.revogado_por,NEW.motivo_revogacao)
                IS DISTINCT FROM ROW(OLD.revogado_em,OLD.revogado_por,OLD.motivo_revogacao)) THEN
          RAISE EXCEPTION 'Decisão do compartilhamento já registrada é imutável.' USING ERRCODE='23514';
        END IF;
        IF (NEW.aceito_em IS DISTINCT FROM OLD.aceito_em OR NEW.recusado_em IS DISTINCT FROM OLD.recusado_em) THEN
          IF tenant IS DISTINCT FROM OLD.empresa_parceira_id THEN
            RAISE EXCEPTION 'Somente a empresa convidada aceita ou recusa.' USING ERRCODE='42501';
          END IF;
          IF OLD.aceito_em IS NOT NULL OR OLD.recusado_em IS NOT NULL OR OLD.revogado_em IS NOT NULL THEN
            RAISE EXCEPTION 'Convite já decidido ou revogado.' USING ERRCODE='23514';
          END IF;
        END IF;
        IF NEW.revogado_em IS DISTINCT FROM OLD.revogado_em THEN
          IF tenant IS NULL OR tenant NOT IN (OLD.empresa_dona_id, OLD.empresa_parceira_id) THEN
            RAISE EXCEPTION 'Somente as partes encerram o compartilhamento.' USING ERRCODE='42501';
          END IF;
          IF OLD.recusado_em IS NOT NULL THEN
            RAISE EXCEPTION 'Convite recusado não tem acesso a revogar.' USING ERRCODE='23514';
          END IF;
        END IF;
        RETURN NEW;
      END; $$;
      CREATE TRIGGER compartilhamento_protegido BEFORE UPDATE OR DELETE ON compartilhamento_remessa
        FOR EACH ROW EXECUTE FUNCTION proteger_compartilhamento();
    """)

    op.execute(f"""
      ALTER TABLE compartilhamento_remessa ENABLE ROW LEVEL SECURITY;
      ALTER TABLE compartilhamento_remessa FORCE ROW LEVEL SECURITY;
      CREATE POLICY compartilhamento_partes ON compartilhamento_remessa FOR SELECT
        USING (empresa_dona_id={TENANT} OR empresa_parceira_id={TENANT});
      CREATE POLICY compartilhamento_convite ON compartilhamento_remessa FOR INSERT
        WITH CHECK (empresa_dona_id={TENANT} AND criado_por={USER}
          AND aceito_em IS NULL AND recusado_em IS NULL AND revogado_em IS NULL
          AND EXISTS (SELECT 1 FROM remessa r WHERE r.id=compartilhamento_remessa.remessa_id
                      AND r.empresa_embarcador_id={TENANT} AND r.codigo=compartilhamento_remessa.remessa_codigo)
          AND EXISTS (SELECT 1 FROM empresa e WHERE e.id=compartilhamento_remessa.empresa_parceira_id
                      AND e.ativa AND e.tipo::text IN ('transportadora','ambos')));
      CREATE POLICY compartilhamento_decisao ON compartilhamento_remessa FOR UPDATE
        USING (empresa_dona_id={TENANT} OR empresa_parceira_id={TENANT})
        WITH CHECK (empresa_dona_id={TENANT} OR empresa_parceira_id={TENANT});
    """)

    # Escrita somente pelo dono, explicitamente. Antes, documento/volume/
    # leitura/série/excursão herdavam o escopo pela visibilidade da remessa.
    dono_remessa = lambda col: f"EXISTS (SELECT 1 FROM remessa r WHERE r.id={col} AND r.empresa_embarcador_id={TENANT})"
    dono_leitura = f"""EXISTS (SELECT 1 FROM sessao_monitoramento s JOIN remessa r ON r.id=s.remessa_id
        WHERE s.id=leitura_etiqueta.sessao_id AND s.etiqueta_id=leitura_etiqueta.etiqueta_id
          AND r.empresa_embarcador_id={TENANT})"""
    aparelho = f"(dispositivo_id IS NULL OR EXISTS (SELECT 1 FROM dispositivo d WHERE d.id=leitura_etiqueta.dispositivo_id AND d.empresa_id={TENANT}))"
    dono_derivado = lambda t: f"""EXISTS (SELECT 1 FROM leitura_etiqueta l JOIN sessao_monitoramento s ON s.id=l.sessao_id
        JOIN remessa r ON r.id=s.remessa_id
        WHERE l.id={t}.leitura_id AND l.sessao_id={t}.sessao_id AND r.empresa_embarcador_id={TENANT})"""
    for tabela, uso, check in [
        ("documento", dono_remessa("documento.remessa_id"), dono_remessa("documento.remessa_id")),
        ("volume", dono_remessa("volume.remessa_id"), dono_remessa("volume.remessa_id")),
        ("leitura_etiqueta", dono_leitura, f"{dono_leitura} AND {aparelho}"),
        ("serie_medicao", dono_derivado("serie_medicao"), dono_derivado("serie_medicao")),
        ("excursao", dono_derivado("excursao"), dono_derivado("excursao")),
    ]:
        op.execute(f"ALTER POLICY escopo_{tabela} ON {tabela} USING ({uso}) WITH CHECK ({check})")

    # Leitura pela parceira: somente SELECT, somente na carga concedida.
    leitura_parceira = {
        "remessa": vigente("remessa.id"),
        "documento": vigente("documento.remessa_id"),
        "volume": vigente("volume.remessa_id"),
        "vinculo_etiqueta": f"""EXISTS (SELECT 1 FROM volume v WHERE v.id=vinculo_etiqueta.volume_id
            AND {vigente('v.remessa_id')})""",
        "sessao_monitoramento": vigente("sessao_monitoramento.remessa_id"),
        "leitura_etiqueta": f"""EXISTS (SELECT 1 FROM sessao_monitoramento s WHERE s.id=leitura_etiqueta.sessao_id
            AND s.etiqueta_id=leitura_etiqueta.etiqueta_id AND {vigente('s.remessa_id')})""",
        "serie_medicao": f"""EXISTS (SELECT 1 FROM leitura_etiqueta l JOIN sessao_monitoramento s ON s.id=l.sessao_id
            WHERE l.id=serie_medicao.leitura_id AND l.sessao_id=serie_medicao.sessao_id AND {vigente('s.remessa_id')})""",
        "excursao": f"""EXISTS (SELECT 1 FROM leitura_etiqueta l JOIN sessao_monitoramento s ON s.id=l.sessao_id
            WHERE l.id=excursao.leitura_id AND l.sessao_id=excursao.sessao_id AND {vigente('s.remessa_id')})""",
    }
    for tabela, uso in leitura_parceira.items():
        op.execute(f"CREATE POLICY parceira_{tabela} ON {tabela} FOR SELECT USING ({uso})")


def downgrade():
    raise RuntimeError("Autorização entre empresas e auditoria; restaurar backup revisado, sem downgrade automático.")
