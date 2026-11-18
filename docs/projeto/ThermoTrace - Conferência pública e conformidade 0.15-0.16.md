---
tipo: entrega
projeto: ThermoTrace
contexto: pessoal-independente
atualizado: 2026-11-18
---

# Conferência pública do relatório e conformidade térmica (API 0.15 e 0.16)

Entregas de 18/11/2026, seguindo [[ThermoTrace - Demonstração do portal 0.14]]. PRs empilhados no GitHub, merge na ordem #1 → #2 → #3:

- #2 `feat/conferencia-relatorio` (API 0.15): conferência pública e QR code.
- #3 `feat/conformidade` (API 0.16): painel de conformidade, CSV e vírgula decimal.

## Conferência pública do relatório (0.15)

**Por quê:** quem recebe a carga (hospital, farmácia, fiscal) fica com o relatório na mão e não tinha como saber se é autêntico; o hash impresso só era conferido por gestor logado.

- `GET /api/v1/conferencia/viagem?sessao=&hash=`, sem login. A prova é a **auditoria**: procura uma exportação (`viagem_exportada`) com aquele par. Não compara só com a viagem atual, porque o hash muda quando chegam coletas e isso reprovaria relatórios legítimos antigos.
- Responde quando foi emitido e se ainda é a versão atual. Resumo só quando é a versão atual, e é o que o relatório já mostra.
- Privacidade: sem exportação, a resposta é só "não confere", igual para hash errado, sessão inexistente ou viagem só consultada. A empresa vem da exportação e só então o contexto do RLS é definido. 30 conferências por IP a cada 5 min; `viagem_conferida_publicamente` na auditoria do dono.
- Relatório HTML imprime o endereço e um **QR code** (SVG embutido, biblioteca `segno==1.6.6` autorizada pelo usuário).
- Portal: página pública `/portal/conferir` e link "Recebeu um relatório?" no login.

## Conformidade térmica (0.16)

**Por quê:** o gestor só descobria quais cargas tiveram problema abrindo uma por uma.

- `GET /api/v1/conformidade`: 100 viagens mais recentes das cargas da empresa, cada uma com a mesma viagem consolidada do relatório (mesmo hash).
- Situação, na ordem: sem dados → coletas divergentes → com excursão → em andamento → conforme. Taxa de conformidade só entre concluídas ou já com excursão.
- Portal: indicadores clicáveis (filtram a lista) e lista com % na faixa, tempo fora e pior extremo.
- `?formato=csv` para planilha; auditoria `conformidade_exportada` com SHA-256 do arquivo.
- **Vírgula decimal nos três CSVs** (coleta, viagem, conformidade) a pedido do usuário. Muda o formato de dois CSVs que já existiam: leitor automático precisa aceitar `9,5`.

## Evidências

- `scripts/testar.ps1` em PostgreSQL 17 descartável: 219 testes de API ao fim da 0.16, incluindo conferência com o papel mínimo da API (NOBYPASSRLS).
- 17 testes web. CI do GitHub verde nos PRs #1 e #2 (API, portal e Android).
- Navegador com a demo: "Confere: versão atual", "Não confere" com hash errado; painel 50% / 1 conforme / 1 com excursão (2 h, 11,5 °C) / 1 em andamento.

## O que falta

- [ ] Ler o QR com um celular (o navegador do painel não tem leitor). No local ele aponta para 127.0.0.1.
- [ ] Ensaiar conferência e conformidade no Render após o merge.
- [ ] Rever o custo do painel com volume real: cada viagem confere a própria cadeia (~100 consultas a mais por abertura).
