"""Autoria na inserção, exclusividade de volume e fechamento irreversível da sessão."""
from alembic import op
import sqlalchemy as sa
revision='a20409120002'
down_revision='a10409120001'
branch_labels=None
depends_on=None


def upgrade():
    op.create_index('uq_vinculo_volume_ativo','vinculo_etiqueta',['volume_id'],unique=True,
                    postgresql_where=sa.text('liberada_em IS NULL'))
    actor="nullif(current_setting('app.usuario_id',true),'')::uuid"
    for table,column in {'dispositivo':'registrado_por','vinculo_etiqueta':'vinculada_por',
                         'sessao_monitoramento':'ativada_por','leitura_etiqueta':'lida_por'}.items():
        op.execute(f'CREATE POLICY autoria_insercao ON {table} AS RESTRICTIVE FOR INSERT WITH CHECK ({column}={actor})')
    op.execute('''CREATE FUNCTION proteger_fechamento_sessao() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
        IF OLD.contrato_ingestao='1' AND OLD.encerrada_em IS NOT NULL AND
          ROW(NEW.encerrada_em,NEW.status) IS DISTINCT FROM ROW(OLD.encerrada_em,OLD.status) THEN
          RAISE EXCEPTION 'Fechamento lógico da sessão é imutável.' USING ERRCODE='23514'; END IF;
        RETURN NEW; END; $$;
        CREATE TRIGGER fechamento_protegido BEFORE UPDATE ON sessao_monitoramento
        FOR EACH ROW EXECUTE FUNCTION proteger_fechamento_sessao();''')


def downgrade():
    raise RuntimeError('Remoção de proteção exige restauração revisada.')
