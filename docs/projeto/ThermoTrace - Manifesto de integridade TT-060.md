---
tipo: entrega
projeto: ThermoTrace
contexto: pessoal-independente
atualizado: 2026-11-04
---

# Manifesto de integridade · TT-060 · API 0.11

Entrega de 04/11/2026. Fecha a parte de código do TT-060: a âncora externa da cadeia de evidências.

## Por que existe

A cadeia local (botão "Verificar integridade") percebe quando uma leitura é alterada. Ela **não** percebe quem tem acesso de dono ao banco, altera a evidência e recalcula a cadeia inteira: tudo continua batendo. Para isso a referência precisa estar fora do servidor, com o cliente.

## Como funciona

- **Portal → Integridade → Baixar manifesto** (gestor). O arquivo JSON lista cada sessão da empresa com a quantidade de leituras e o hash encadeado da última, mais um hash do próprio arquivo. Não leva temperaturas nem dados das cargas.
- O gestor guarda o arquivo **fora da ThermoTrace**: e-mail para si, pasta da qualidade ou impressão. A emissão fica na Auditoria com o hash do manifesto, o que comprova que aquele arquivo saiu do servidor naquela data.
- **Conferir manifesto:** o gestor envia o arquivo de volta e o servidor verifica, sessão a sessão, se o hash naquela posição continua o mesmo. Leituras recebidas depois não atrapalham, porque a cadeia só cresce.
- Resultados possíveis: confere; arquivo editado depois de emitido; sessão diferente do manifesto; cadeia alterada depois do ponto ancorado; sessão não encontrada.

## Evidências

- 189 testes API (4 novos). O principal reescreve uma evidência e recalcula todos os hashes, como faria alguém com acesso de dono ao banco: a verificação local continua dizendo "íntegra" e o manifesto acusa a sessão. Também cobertos: leituras posteriores aceitas, arquivo editado, manifesto de outra empresa recusado, operador e equipe ThermoTrace sem acesso, auditoria.
- Conferido no navegador com dados fictícios: download, conferência que confere, arquivo editado e arquivo inválido.

## Limites e rotina sugerida

- Só protege o que foi ancorado. Sugestão para o piloto: baixar um manifesto **ao final de cada viagem** e outro por semana.
- Não certifica temperatura, sensor nem relógio da etiqueta.
- O valor depende de onde o arquivo fica guardado. Uma cópia guardada no mesmo servidor não protege nada.
