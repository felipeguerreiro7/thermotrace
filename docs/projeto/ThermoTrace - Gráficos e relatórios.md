---
tipo: projeto
projeto: ThermoTrace
status: em-planejamento
atualizado: 2026-09-11
---

# Gráficos e relatórios

## Série temporal
Cada checkpoint pode trazer novamente todo o histórico da etiqueta. Consolidar por sessão + índice original da amostra, preservando cada coleta bruta. Repetição idêntica acrescenta proveniência; temperatura ou horário divergente no mesmo índice gera conflito, sem sobrescrever silenciosamente. Troca de etiqueta inicia outra sessão.

Guardar hora do sensor, do celular e do servidor separadamente. Base de tempo e delay precisam de regra versionada; não assumir que o horário do upload é o da medição. Gráfico em fuso explícito, armazenamento temporal em UTC; correções preservam original e desvio aplicado.

## Visualização e download
- Temperatura × tempo com faixa mínima/máxima da sessão e marcadores dos checkpoints.
- Separar sessões/volumes; indicar lacunas e última atualização. Não unir lacunas como se fossem dados coletados.
- Durante o transporte: gráfico parcial. No fechamento: versão vinculada ao relatório.
- Exportar gráfico PNG/SVG, dados CSV/XLSX e relatório PDF.
- Gráficos podem reduzir pontos apenas para visualização, mantendo extremos; métricas e exportação usam série integral.

## Relatório mínimo
ID/verificação, versão, cliente, carga e documentos, volumes, etiquetas, responsáveis, período, perfil, intervalo, gráfico, mínimo/máximo/média amostral, cobertura, lacunas, excursões, horários das coletas, atrasos de sincronização, ocorrências e ações, calibração, evidências e hashes, metodologia/versão e limitações.

Média amostral deve ser nomeada assim. Tempo fora da faixa e MKT só após homologar algoritmo, unidade, ponderação temporal, tratamento de lacunas e intervalos; não inferir duração contínua a partir de um ponto isolado. “Sem desvio observado” não equivale a “carga aprovada”.

## Integridade da emissão
Servidor emite relatório a partir de snapshot validado, salva arquivo e manifesto, registra hash e evento. Leitura tardia não muda PDF anterior: gera versão substituta com justificativa. Link de verificação exige autenticação ou token de escopo mínimo, sem expor nota/carga publicamente.

O kit inicial trata apenas consolidação/deduplicação/conflito de pontos. Renderização, métricas térmicas e assinatura ainda precisam ser integradas.


## Atualização — Portal 0.2, 14/09/2026

Gráfico central por coleta disponível no Portal 0.2: curva, faixa, mínima/máxima, exploração de pontos, CSV, SVG e relatório HTML imprimível. Divergências ficam visíveis e a base nominal de tempo é explicitada. Hash e versão acompanham a projeção; não há fusão de snapshots, correção de relógio, diagnóstico de causa ou liberação automática de carga.

[[ThermoTrace - Portal 0.2 e gráficos por coleta]].
