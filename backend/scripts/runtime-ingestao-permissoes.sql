-- Delta 0.3 -> 0.4; executar como dono no banco correto, após as migrações.
BEGIN;
GRANT SELECT, INSERT ON dispositivo, vinculo_etiqueta, sessao_monitoramento, leitura_etiqueta TO thermotrace_runtime;
GRANT SELECT ON etiqueta TO thermotrace_runtime;
GRANT UPDATE ON volume, etiqueta, sessao_monitoramento TO thermotrace_runtime;
COMMIT;
-- Não concede INSERT em etiquetas/lotes: catálogo físico exige provisionamento separado.
