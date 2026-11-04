-- API 0.6 (WEB-10). Para banco que já tem thermotrace_runtime criado pelo
-- runtime-permissoes.sql anterior. Executar com o dono das migrações, depois
-- de `alembic upgrade head`. Não cria papel nem altera senha.
BEGIN;
GRANT SELECT, INSERT, UPDATE ON compartilhamento_remessa TO thermotrace_runtime;
COMMIT;
