"""Operação entre empresas: a transportadora bipa na carga que o dono compartilhou.

O dono decide, no convite, se a transportadora também pode **operar** a carga:
iniciar o monitoramento de um volume com etiqueta e aparelho dela e enviar
checkpoints e a leitura final. Documentos, cancelamento e novos
compartilhamentos continuam só com o dono.

Cada leitura passa a registrar a empresa que a enviou (`empresa_autora_id`).
Sem essa coluna, a única prova de quem bipou ficaria dentro do envelope, e a
conferência de integridade não teria com o que compará-lo.
"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql as pg

revision = "e10411100001"
down_revision = "d10411040003"
branch_labels = None
depends_on = None

TENANT = "nullif(current_setting('app.empresa_id', true), '')::uuid"


def operavel(coluna):
    """Compartilhamento vigente, com permissão de operar, para a empresa atual."""
    return f"""EXISTS (SELECT 1 FROM compartilhamento_remessa c
        WHERE c.remessa_id={coluna} AND c.empresa_parceira_id={TENANT} AND c.pode_operar
          AND c.aceito_em IS NOT NULL AND c.recusado_em IS NULL AND c.revogado_em IS NULL
          AND (c.expira_em IS NULL OR c.expira_em > now()))"""


def aparelho(tabela):
    return f"""({tabela}.dispositivo_id IS NULL OR EXISTS (SELECT 1 FROM dispositivo d
        WHERE d.id={tabela}.dispositivo_id AND d.empresa_id={TENANT}))"""


def upgrade():
    # O gatilho proteger_compartilhamento compara a linha inteira menos as colunas
    # de decisão; a coluna nova já nasce imutável depois do convite.
    op.add_column("compartilhamento_remessa",
                  sa.Column("pode_operar", sa.Boolean(), nullable=False, server_default=sa.text("false")))
    # Nula nas leituras anteriores: nelas só o dono da carga podia enviar.
    op.add_column("leitura_etiqueta",
                  sa.Column("empresa_autora_id", pg.UUID(as_uuid=True), sa.ForeignKey("empresa.id")))

    op.execute(f"""
      -- O dono enxerga o vínculo dos seus volumes mesmo quando a etiqueta é da transportadora.
      -- Sem isto, a sessão iniciada pela transportadora ficaria invisível para o dono da carga.
      CREATE POLICY dona_vinculo_etiqueta ON vinculo_etiqueta FOR SELECT
        USING (EXISTS (SELECT 1 FROM volume v JOIN remessa r ON r.id=v.remessa_id
                       WHERE v.id=vinculo_etiqueta.volume_id AND r.empresa_embarcador_id={TENANT}));

      -- A transportadora vincula etiqueta DELA a volume da carga que pode operar.
      CREATE POLICY operadora_vinculo_etiqueta ON vinculo_etiqueta FOR INSERT
        WITH CHECK (EXISTS (SELECT 1 FROM volume v WHERE v.id=vinculo_etiqueta.volume_id
                            AND {operavel('v.remessa_id')})
          AND EXISTS (SELECT 1 FROM etiqueta e WHERE e.id=vinculo_etiqueta.etiqueta_id AND e.empresa_id={TENANT})
          AND {aparelho('vinculo_etiqueta')});

      CREATE POLICY operadora_sessao_monitoramento ON sessao_monitoramento FOR INSERT
        WITH CHECK ({operavel('sessao_monitoramento.remessa_id')}
          AND EXISTS (SELECT 1 FROM remessa r JOIN vinculo_etiqueta ve ON ve.id=sessao_monitoramento.vinculo_id
                      JOIN volume v ON v.id=ve.volume_id
                      WHERE r.id=sessao_monitoramento.remessa_id AND v.remessa_id=r.id
                        AND ve.etiqueta_id=sessao_monitoramento.etiqueta_id
                        AND r.perfil_termico_id=sessao_monitoramento.perfil_termico_id)
          AND {aparelho('sessao_monitoramento')});

      -- Fechamento lógico pela leitura final. O gatilho de identidade limita as colunas.
      CREATE POLICY operadora_sessao_fechamento ON sessao_monitoramento FOR UPDATE
        USING ({operavel('sessao_monitoramento.remessa_id')})
        WITH CHECK ({operavel('sessao_monitoramento.remessa_id')});

      CREATE POLICY operadora_volume ON volume FOR UPDATE
        USING ({operavel('volume.remessa_id')}) WITH CHECK ({operavel('volume.remessa_id')});

      CREATE POLICY operadora_leitura_etiqueta ON leitura_etiqueta FOR INSERT
        WITH CHECK (leitura_etiqueta.empresa_autora_id={TENANT}
          AND EXISTS (SELECT 1 FROM sessao_monitoramento s WHERE s.id=leitura_etiqueta.sessao_id
                      AND s.etiqueta_id=leitura_etiqueta.etiqueta_id AND {operavel('s.remessa_id')})
          AND {aparelho('leitura_etiqueta')});

      -- Ninguém grava leitura em nome de outra empresa, nem o dono da carga.
      CREATE POLICY autoria_empresa ON leitura_etiqueta AS RESTRICTIVE FOR INSERT
        WITH CHECK (empresa_autora_id IS NULL OR empresa_autora_id={TENANT});

      -- A transportadora só muda o andamento do volume (monitorado/status).
      -- Descrição, peso e demais dados continuam sendo do dono da carga.
      CREATE FUNCTION proteger_volume_de_operadora() RETURNS trigger LANGUAGE plpgsql AS $$
      DECLARE
        tenant uuid := {TENANT};
        dona uuid;
        andamento text[] := ARRAY['monitorado','status','atualizado_em'];
      BEGIN
        IF tenant IS NULL THEN RETURN NEW; END IF;
        SELECT r.empresa_embarcador_id INTO dona FROM remessa r WHERE r.id=OLD.remessa_id;
        IF dona IS DISTINCT FROM tenant
           AND (to_jsonb(NEW)-andamento) IS DISTINCT FROM (to_jsonb(OLD)-andamento) THEN
          RAISE EXCEPTION 'Somente o dono da carga altera os dados do volume.' USING ERRCODE='42501';
        END IF;
        RETURN NEW;
      END; $$;
      CREATE TRIGGER volume_dados_do_dono BEFORE UPDATE ON volume
        FOR EACH ROW EXECUTE FUNCTION proteger_volume_de_operadora();
    """)


def downgrade():
    raise RuntimeError("Autorização de operação e autoria de evidências; restaurar backup revisado, sem downgrade automático.")
