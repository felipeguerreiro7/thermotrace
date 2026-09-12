-- Executar no banco alvo com o dono das migrações. Não embute senha.
-- Exige papel novo: falha se já existir, para não mudar acesso silenciosamente.
BEGIN;
CREATE ROLE thermotrace_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
GRANT USAGE ON SCHEMA public TO thermotrace_runtime;
GRANT SELECT ON empresa, usuario, sessao_auth, perfil_termico, log_auditoria, limite_login TO thermotrace_runtime;
GRANT INSERT ON empresa, usuario, sessao_auth, perfil_termico, log_auditoria, limite_login TO thermotrace_runtime;
GRANT UPDATE ON usuario, sessao_auth, perfil_termico, limite_login TO thermotrace_runtime;
GRANT USAGE, SELECT ON SEQUENCE log_auditoria_id_seq TO thermotrace_runtime;
GRANT SELECT, INSERT ON produto_configuracao, remessa, documento, volume, chave_idempotencia TO thermotrace_runtime;
GRANT UPDATE ON produto_configuracao, remessa TO thermotrace_runtime;
GRANT SELECT, INSERT ON dispositivo, vinculo_etiqueta, sessao_monitoramento, leitura_etiqueta TO thermotrace_runtime;
GRANT SELECT ON etiqueta TO thermotrace_runtime;
GRANT UPDATE ON volume, etiqueta, sessao_monitoramento TO thermotrace_runtime;
COMMIT;
-- No psql, defina a senha interativamente: \password thermotrace_runtime
-- Ajuste pg_hba.conf/rede para autenticação SCRAM e TLS no ambiente publicado.
-- Amplie privilégios por tabela quando novas rotas entrarem; nunca GRANT ALL.
