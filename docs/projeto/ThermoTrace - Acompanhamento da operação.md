---
tipo: entrega
projeto: ThermoTrace
contexto: pessoal-independente
atualizado: 2026-11-04
---

# Acompanhamento da operação · API 0.10

Entrega de 04/11/2026 para o critério "sincronização sem perda" do [[ThermoTrace - Plano até 30-11-2026]]. Até aqui, uma etiqueta esquecida sem leitura final ou um celular com a fila de envio parada só apareciam quando alguém procurava a carga.

## O que o portal mostra

Página **Acompanhamento** (operador e gestor; só cargas da própria empresa, sem as compartilhadas):

- **Etiquetas sem leitura final:** carga, volume, início, última coleta, número de coletas e **fim previsto da memória** (início + atraso programado + intervalo × quantidade). Situações, nesta ordem:
  - *Memória programada acabou:* a etiqueta já parou de registrar. Fazer a leitura final.
  - *Sem coleta recente:* passou do limite de horas sem nenhuma coleta (padrão 24 h, ajustável na tela).
  - *Em andamento.*
- **Celulares da empresa:** último envio recebido, coletas e **maior atraso entre a leitura e a chegada ao servidor** nos últimos 7 dias, último operador e se o aparelho está ativo. Atraso grande indica celular sem rede ou fila parada.

Regra versionada `tt-acompanhamento-1`. Não altera dados nem conclui nada sobre temperatura.

## Evidências

- 184 testes API (3 novos): fim previsto e as três situações, leitura final tirando a sessão do painel, aparelho com atraso visível, isolamento entre empresas, equipe ThermoTrace recusada (403). 17 testes web.
- Conferido no navegador com dados fictícios em banco descartável: três sessões (memória acabou, em andamento, sem coleta com limite de 1 h), um aparelho, link para a carga e largura de celular sem rolagem horizontal.

## Limites

- Mostra só o que já chegou ao servidor. Coletas que ainda estão no celular não aparecem, e é justamente o atraso de envio que sinaliza isso.
- Fim previsto nominal: não corrige o relógio da etiqueta nem confirma o STOP físico.
- Sem alerta ativo (e-mail, push): o gestor precisa abrir a página. Alerta automático depende de canal de envio contratado.
- Até 200 sessões abertas por consulta.
