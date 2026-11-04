---
tipo: entrega
projeto: ThermoTrace
contexto: pessoal-independente
atualizado: 2026-11-04
---

# Viagem consolidada · WEB-12 · API 0.9

Entrega de 04/11/2026, adiantada da semana 2 do [[ThermoTrace - Plano até 30-11-2026]]. Fecha o "WEB-12 restante": consolidação entre coletas, excursões, duração e relatório da viagem, sem somar históricos repetidos.

## Regra de consolidação (versão `tt-viagem-1`)

- **Base:** cada checkpoint ou leitura final traz o histórico inteiro da etiqueta até aquele momento. A viagem usa como base a coleta com **mais medições** (em empate, a mais recente).
- **Conferência:** as outras coletas são conferidas contra a base, **índice por índice** (temperatura e horário nominal). Qualquer diferença aparece como divergência e marca a viagem como **divergente**; nada é corrigido nem descartado.
- **Etiqueta reativada:** uma coleta que chega depois com **menos** medições que uma anterior gera aviso, porque pode indicar etiqueta reativada.
- **Excursões:** trechos contínuos fora da faixa preservada na sessão, com início, fim, quantidade de pontos, **duração nominal** (pontos × intervalo) e extremo.
- **Resumo:** período, mínima, máxima, percentual na faixa, tempo fora da faixa, coletas conferidas e se já há leitura final. Sem leitura final, a viagem aparece como **parcial**.
- **Integridade:** cadeia de evidências divergente bloqueia a viagem, como já acontecia no gráfico por coleta.
- **Rastreabilidade:** projeção com hash determinístico. Consulta e exportação (CSV, SVG e relatório HTML) ficam auditadas, inclusive na empresa dona quando o acesso vem de um compartilhamento.

## Portal

Na carga → volume → sessão, o botão **"Viagem consolidada"** mostra o gráfico da base, os indicadores, a tabela de excursões, as coletas consideradas (qual é a base, quais foram conferidas) e as divergências, se houver. Os downloads seguem o escopo do compartilhamento (WEB-10).

## Evidências

- 181 testes API (3 novos): base correta sem soma (3 + 6 + 9 coletadas → 9 medições na viagem), excursões acima e abaixo com duração, viagem parcial, divergência entre coletas visível, projeção determinística, auditoria e isolamento entre empresas. 17 testes web.
- Conferido no navegador com dados fictícios: viagem parcial com 30 medições, uma excursão acima da faixa (30 min, pico de 9,1 °C) e a coleta base identificada.

## Limites

- Uma viagem por sessão. Troca de etiqueta no meio do trajeto (duas sessões no mesmo volume) aparece como duas viagens; a junção entre sessões fica para depois.
- Duração nominal, pelo intervalo declarado; não corrige o relógio da etiqueta. Sem validação física e sem liberação automática da carga.
