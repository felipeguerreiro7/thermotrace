---
tipo: plano
projeto: ThermoTrace
contexto: pessoal-independente
atualizado: 2026-11-04
---

# Plano até 30/11/2026 — piloto pronto

Pedido do usuário em 04/11/2026: concluir o projeto até o fim do mês. "Concluir" segue o critério de piloto pronto da [[ThermoTrace - Central do projeto]]: dois clientes isolados, operadores identificados, leitura real validada contra o fornecedor, sincronização sem perda/duplicação, fechamento explícito, gráfico e relatório reproduzíveis, restauração de backup comprovada e acesso ThermoTrace auditado.

## Situação em 04/11

| Critério | Situação | Responsável |
|---|---|---|
| Clientes isolados, operadores, acesso auditado | Código pronto (WEB-10, WEB-11) | — |
| Gráfico e relatório reproduzíveis | Por coleta pronto; falta a viagem consolidada (WEB-12) | Desenvolvimento |
| Sincronização sem perda | Envio manual pronto; falta fila automática no Android | Desenvolvimento (ambiente Android a instalar) |
| Fechamento explícito | Código pronto; falta ensaio físico | Usuário + desenvolvimento |
| Leitura real validada (TT-005, A57) | Aberto; maior risco | Usuário, com etiqueta e celular |
| Publicação Render/domínio/HTTPS | Sem conferência; URL pública não registrada | Usuário (painel) + desenvolvimento |
| Backup e restauração | Não feito; pode exigir plano pago | Usuário decide custo; desenvolvimento faz roteiro |

## Semanas

1. **Até 09/11:** MFA da equipe ThermoTrace; ambiente Android neste computador e suíte JVM rodando. Usuário: URL do Render e status dos deploys.
2. **Até 16/11:** fila automática de envio no Android; visão da viagem consolidada no portal (WEB-12). Usuário: ensaio físico com roteiro fornecido (TT-005, checkpoint/final/STOP).
3. **Até 23/11:** correções do ensaio; ensaio ponta a ponta no ambiente publicado (WEB-04); backup e restauração.
4. **Até 30/11:** APK de piloto, revisão final, notas e lista do que fica para depois.

Risco principal: o ensaio físico. Se ele atrasar, o prazo atrasa junto; o restante não depende dele.

## Andamento

- 04/11: MFA da equipe ThermoTrace concluída e publicada no GitHub (semana 1, item 1).
- 04/11: ambiente Android montado e suíte JVM rodando (150 testes). Fila automática de envio adiantada da semana 2: Android 0.8.8, ver [[ThermoTrace - Envio automático Android 0.8.8]]. Roteiro do ensaio físico pronto: [[ThermoTrace - Roteiro do ensaio físico de novembro]].
- Pendente do usuário: URL do Render; ensaio físico; A57 no cabo para os testes instrumentados.
