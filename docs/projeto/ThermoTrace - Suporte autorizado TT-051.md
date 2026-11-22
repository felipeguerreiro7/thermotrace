---
tipo: entrega
projeto: ThermoTrace
contexto: pessoal-independente
atualizado: 2026-11-22
---

# Suporte autorizado TT-051 (API 0.18)

Entrega de 22/11/2026, branch `feat/suporte-autorizado`. Fecha a parte que faltava do TT-051 no [[ThermoTrace - Plano de ação e backlog]]: acesso de suporte ao conteúdo das cargas, autorizado pelo cliente e auditado. A equipe continua sem ver carga nenhuma por padrão.

## Por quê

Quando um cliente disser "a coleta não aparece", a equipe hoje não tem como olhar sem pedir print, e o print expõe dados sem trilha. A outra saída, dar à equipe acesso irrestrito, contraria a decisão do WEB-07 e a política de privacidade. Faltava um meio-termo: o cliente abre uma carga, por um tempo, e vê quem olhou.

## Como funciona

1. **Cliente (gestor), na página da carga → Suporte ThermoTrace:** informa o motivo (o chamado) e o prazo (4, 24 ou 72 horas; 72 é o máximo).
2. **Equipe → Pedidos de suporte:** vê o pedido, o cliente e o motivo, mas não os dados da carga. Quem for atender clica em **Assumir**; o nome dessa pessoa fica registrado.
3. Assumido, a carga abre **só para consulta**: resumo, volumes, coletas, viagem, gráfico e integridade. Baixar CSV/SVG/relatório, ver a evidência bruta, bipar, anexar documento e cancelar continuam recusados.
4. **Cada consulta** aparece na Auditoria do cliente, com a pessoa da equipe e o IP.
5. O cliente encerra quando quiser (Encerrar acesso); a equipe também encerra quando o chamado termina. Vencido o prazo, o acesso acaba sozinho. O efeito vale na requisição seguinte.

## Decisões técnicas

- Reaproveita o compartilhamento do WEB-10, com papel `suporte` e a empresa da plataforma como parceira. Aceite, revogação, prazo, trava de imutabilidade, RLS de leitura e espelhamento da auditoria no dono já existiam e já tinham teste.
- O banco garante a regra, não só a API: restrição `suporte_limitado` (nunca exporta, nunca opera, prazo de até 72 h, motivo com no mínimo 10 caracteres) e política de inserção que só aceita a plataforma como parceira do papel suporte. O convite comum continua recusando o CNPJ da plataforma.
- Rotas de leitura de carga passam a aceitar a equipe (com MFA obrigatória) por uma dependência `leitor`. Sem liberação, a resposta é 404, igual a uma carga que não existe. Antes era 403; os testes antigos foram ajustados.
- A lista "Minhas cargas" e a busca continuam fechadas para a equipe. Ela só chega à carga pelo pedido de suporte.

## Evidências

- 4 testes novos em `test_suporte.py`: fluxo completo com auditoria nos dois lados; regras (73 h, motivo curto, operador, outra empresa, equipe se autoconcedendo, duplicidade, encerramento pela equipe, CNPJ da plataforma no convite comum); restrição do banco; RLS sem `WHERE` para a equipe antes e depois da liberação.
- Suíte da API: 243 testes verdes; 17 testes web; `alembic check` limpo.
- Conferido no navegador, em servidor local com as empresas de demonstração e uma conta fictícia da equipe com MFA: liberação pelo dono, pedido na fila, assumir, abrir a carga e a viagem, e auditoria nos dois lados com IP.

## O que falta

- [ ] Depois do deploy, o banco do Render precisa ter a empresa da plataforma cadastrada (a do `python -m app.cli`). Sem ela, liberar responde "Suporte indisponível".
- [ ] Incluir no ensaio [[ThermoTrace - Ensaio WEB-04 no Render]]: liberar uma carga DEMO para o suporte e conferir a auditoria do dono.
- [ ] A Auditoria do portal mostra a ação com o nome técnico (`suporte_concedido`, `remessa_consultada`). Traduzir os nomes é melhoria de tela, fora desta entrega.
