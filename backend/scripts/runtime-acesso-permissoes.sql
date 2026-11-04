-- WEB-11 (códigos de acesso). Para banco que já tem thermotrace_runtime.
-- Executar com o dono das migrações, depois de `alembic upgrade head`.
-- Não cria papel nem altera senha. Sem DELETE: códigos são revogados, não apagados.
BEGIN;
GRANT SELECT, INSERT, UPDATE ON token_recuperacao TO thermotrace_runtime;
COMMIT;
