"""Contrato de recepção e isolamento de sessões/evidências; legado preservado."""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql as pg

revision = 'a10409120001'
down_revision = '92b030110002'
branch_labels = None
depends_on = None


def upgrade():
    op.add_column('sessao_monitoramento',sa.Column('contrato_ingestao',sa.String(16)))
    for col in [sa.Column('contrato_ingestao',sa.String(16)),sa.Column('evento_id',pg.UUID()),
                sa.Column('ordem_recebimento',sa.Integer()),sa.Column('envelope_integridade',pg.JSONB())]:
        op.add_column('leitura_etiqueta',col)
    op.create_unique_constraint('uq_leitura_sessao_evento','leitura_etiqueta',['sessao_id','evento_id'])
    op.create_unique_constraint('uq_leitura_sessao_ordem','leitura_etiqueta',['sessao_id','ordem_recebimento'])
    op.create_check_constraint('envelope_ingestao_completo','leitura_etiqueta',
        "(contrato_ingestao IS NULL AND evento_id IS NULL AND ordem_recebimento IS NULL AND envelope_integridade IS NULL) OR (contrato_ingestao IS NOT NULL AND contrato_ingestao = '1' AND evento_id IS NOT NULL AND ordem_recebimento IS NOT NULL AND ordem_recebimento > 0 AND envelope_integridade IS NOT NULL)")
    op.create_index('uq_leitura_final_ingestao','leitura_etiqueta',['sessao_id'],unique=True,
                    postgresql_where=sa.text("tipo_leitura='final' AND contrato_ingestao IS NOT NULL"))
    tenant="nullif(current_setting('app.empresa_id',true),'')::uuid"
    policies={
        'dispositivo':f'empresa_id={tenant}',
        'etiqueta':f'empresa_id={tenant}',
        'vinculo_etiqueta':f'''EXISTS (SELECT 1 FROM volume v JOIN remessa r ON r.id=v.remessa_id
            JOIN etiqueta e ON e.id=vinculo_etiqueta.etiqueta_id
            WHERE v.id=vinculo_etiqueta.volume_id AND r.empresa_embarcador_id={tenant} AND e.empresa_id={tenant})''',
        'sessao_monitoramento':f'''EXISTS (SELECT 1 FROM remessa r JOIN vinculo_etiqueta ve ON ve.id=sessao_monitoramento.vinculo_id
            JOIN volume v ON v.id=ve.volume_id WHERE r.id=sessao_monitoramento.remessa_id
            AND r.empresa_embarcador_id={tenant} AND v.remessa_id=r.id AND ve.etiqueta_id=sessao_monitoramento.etiqueta_id
            AND r.perfil_termico_id=sessao_monitoramento.perfil_termico_id)''',
        'leitura_etiqueta':'''EXISTS (SELECT 1 FROM sessao_monitoramento s WHERE s.id=leitura_etiqueta.sessao_id
            AND s.etiqueta_id=leitura_etiqueta.etiqueta_id)''',
        'serie_medicao':'''EXISTS (SELECT 1 FROM leitura_etiqueta l WHERE l.id=serie_medicao.leitura_id
            AND l.sessao_id=serie_medicao.sessao_id)''',
        'excursao':'''EXISTS (SELECT 1 FROM leitura_etiqueta l WHERE l.id=excursao.leitura_id AND l.sessao_id=excursao.sessao_id)''',
    }
    for table,scope in policies.items():
        check=scope
        if table in ['vinculo_etiqueta','sessao_monitoramento','leitura_etiqueta']:
            check+=f' AND (dispositivo_id IS NULL OR EXISTS (SELECT 1 FROM dispositivo d WHERE d.id={table}.dispositivo_id AND d.empresa_id={tenant}))'
        op.execute(f'ALTER TABLE {table} ENABLE ROW LEVEL SECURITY; ALTER TABLE {table} FORCE ROW LEVEL SECURITY; CREATE POLICY escopo_{table} ON {table} USING ({scope}) WITH CHECK ({check})')
    # Congelar identidade; permitir apenas evolução operacional expressa.
    for table,mutable in {
        'dispositivo':['ativo','ultimo_acesso_em','atualizado_em'],
        'etiqueta':['estado','ciclos_ativacao','ultima_tensao_v','ultima_tensao_em','aposentada_em','aposentada_motivo','atualizado_em'],
        'vinculo_etiqueta':['liberada_em','motivo_liberacao'],
        'sessao_monitoramento':['status','encerrada_em','verificado_em','registrando_na_verificacao'],
    }.items():
        arr='ARRAY['+','.join("'"+c+"'" for c in mutable)+']'
        op.execute(f'''CREATE FUNCTION proteger_identidade_{table}() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
            IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Histórico não pode ser apagado.' USING ERRCODE='23514'; END IF;
            IF (to_jsonb(NEW)-{arr}) IS DISTINCT FROM (to_jsonb(OLD)-{arr}) THEN
              RAISE EXCEPTION 'Identidade de {table} é imutável.' USING ERRCODE='23514'; END IF;
            RETURN NEW; END; $$;
            CREATE TRIGGER identidade_protegida BEFORE UPDATE OR DELETE ON {table}
            FOR EACH ROW EXECUTE FUNCTION proteger_identidade_{table}();''')


def downgrade():
    raise RuntimeError('Evidências recebidas exigem restauração revisada; downgrade destrutivo recusado.')
