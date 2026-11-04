# API 0.6 · WEB-10 · compartilhamento de cargas

Entrega de 04/11/2026, projeto pessoal ThermoTrace. Nota completa: `docs/projeto/ThermoTrace - Compartilhamento de cargas WEB-10.md`.

## Rotas novas

| Rota | Quem | O que faz |
|---|---|---|
| `POST /remessas/{id}/compartilhamentos` | gestor do dono | convida transportadora por `cnpj_parceira`; `pode_exportar` (padrão falso) e `expira_em` opcionais |
| `GET /remessas/{id}/compartilhamentos` | gestor do dono | histórico da carga |
| `GET /compartilhamentos/recebidos?situacao=` | gestor/operador da parceira | convites e cargas concedidas; operador vê só `ativo` |
| `POST /compartilhamentos/{id}/aceitar` · `/recusar` | gestor da parceira | decisão única |
| `POST /compartilhamentos/{id}/revogar` | gestor de qualquer parte | `motivo` obrigatório; efeito imediato |

Todos os POST exigem `Idempotency-Key`. Contrato em `docs/openapi-0.6.json`.

Rotas de leitura que passam a aceitar a parceira com concessão vigente: `GET /remessas/{id}`, `GET /volumes/{id}/sessoes`, `GET /sessoes/{id}/leituras`, `GET /sessoes/{id}/integridade` e `GET /sessoes/{id}/leituras/{id}/temperaturas`. Os formatos `csv`/`svg`/`html` exigem `pode_exportar`. Continuam exclusivas do dono: listagem e busca de cargas, evidência bruta e todas as escritas.

## Banco

Migração `b10411040001`: tabela `compartilhamento_remessa`, gatilho de imutabilidade e de decisão pela parte certa, políticas `parceira_*` só de leitura e políticas de escrita reescritas com o dono explícito.

Papel restrito já existente: depois da migração, aplicar `scripts/runtime-compartilhamento-permissoes.sql`. Sem esse GRANT, até a leitura de remessas falha, porque as políticas consultam a tabela nova. Papel novo: `scripts/runtime-permissoes.sql` já inclui a permissão.

## Validação

- 166 testes API/PostgreSQL (`scripts/testar.ps1`, com `alembic check` sem divergência).
- 15 testes web (`node --test backend/tests_web/*.test.mjs`).
- Ensaio no navegador com banco de demonstração local e dados fictícios: convite, aceite, consulta do gráfico, exportação negada, auditoria do dono e encerramento.

Sem teste físico e sem publicação confirmada no Render.
