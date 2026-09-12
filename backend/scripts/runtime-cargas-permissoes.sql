-- Após as migrações da API 0.3, com dono das migrações; não altera senha/papel.
BEGIN;
GRANT SELECT, INSERT ON produto_configuracao, remessa, documento, volume, chave_idempotencia TO thermotrace_runtime;
GRANT UPDATE ON produto_configuracao, remessa TO thermotrace_runtime;
COMMIT;
