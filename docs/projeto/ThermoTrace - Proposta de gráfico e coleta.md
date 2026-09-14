---
tipo: proposta
projeto: ThermoTrace
contexto: pessoal-independente
status: rascunho-aguardando-decisao
atualizado: 2026-09-12
---

# Proposta — gráfico e tela de coleta

Origem: pedido do usuário em 12/09/2026, depois do primeiro ensaio em aparelho
([[ThermoTrace - Ensaio em aparelho 2026-09-12]]): "melhorar os gráficos e essa parte de
check-in, deixar mais claro e intuitivo". Rascunho para decisão, não implementado.
Escopo é apresentação e fluxo; nada aqui altera decodificação, evidência bruta ou critério
térmico, que continuam governados por [[ThermoTrace - Segurança e auditoria]].

## Problemas observados, com origem no código

### Gráfico (`GraficoTemperatura`, em `ui/components/Componentes.kt`)

1. **A escala é ditada pelo ponto mais extremo.** O código calcula
   `menor = min(dados, minC) - 1.5` e `maior = max(dados, maxC) + 1.5`. Com um ponto de
   −29,8 °C, a escala foi de −31,3 a 32,3 e a faixa aprovada de 7 a 14 °C virou uma tira
   fina no meio. A faixa é o critério de aprovação da carga: ela tem que dominar a leitura
   visual, não desaparecer.
2. **Não há eixo Y legível.** O único indicador numérico é a legenda "escala X a Y". Não se
   lê um valor no gráfico; só se compara forma.
3. **Não há marcas de tempo intermediárias.** Só início e fim no rodapé. Numa viagem de
   dias, não se localiza quando a excursão aconteceu.
4. **A excursão não é destacada.** O resumo diz "1 excursão, 30 min fora da faixa", mas o
   gráfico não mostra onde. O trecho fora da faixa deveria ser a coisa mais visível da tela.
5. **Os limites não são identificáveis.** Duas linhas tracejadas iguais, sem dizer qual é
   mínima e qual é máxima.
6. **Pontos fora da faixa têm a mesma cor dos conformes.**
7. **Lacunas existem no modelo mas não na leitura.** `prepararSerieGrafico` já marca
   `iniciaTrecho` e o componente calcula `lacunas`; falta comunicar isso ao operador, que é
   critério de aceite do TT-040 ("lacunas visíveis").

### Tela de coleta (`LeituraScreen`)

1. **A ação de registrar fica fora da tela.** A ordem é trilho, descrição, cartão de
   estado, Volume, painel NFC, botões, etiqueta encostada, resultado, gráfico e só então
   "Registrar checkpoint". Depois de uma coleta bem-sucedida o operador precisa rolar até o
   fim para concluir. É o risco maior: achar que terminou quando não registrou.
2. **O painel volta a "Pronto" depois de concluir.** Mesma palavra para "nada armado" e
   para "terminei". Ambíguo.
3. **Duas afordâncias parecidas.** "Verificar agora" (lê só o status) e a leitura automática
   competem visualmente, e a diferença entre elas não está na tela.
4. **Não há estado de sucesso explícito.** Falta o momento "registrado, pode afastar o
   telefone e seguir", com saída óbvia.
5. **Um só nome para dois momentos.** O usuário chama de "check-in"; a tela chama de
   Checkpoint e a etapa do trilho de "Trajeto". Vale unificar o vocabulário.

## Proposta

### Gráfico

1. Escala ancorada na faixa aprovada: a faixa mais uma folga fixa. Pontos além disso são
   desenhados no limite com marca de recorte e valor anotado. Nunca esconder um extremo;
   nunca deixá-lo achatar o critério.
2. Eixo Y com rótulos nos limites da faixa e nos extremos da escala.
3. Marcas de tempo intermediárias, em passo escolhido pela duração total.
4. Trechos de excursão sombreados, com duração anotada.
5. Limites rotulados no próprio gráfico ("máx 14", "mín 7").
6. Pontos fora da faixa em cor de excursão.
7. Lacuna desenhada como interrupção da linha, com aviso de quantos registros faltaram.
8. Leitura de topo com último valor, horário e situação, para quem só quer o número.

### Tela de coleta

1. Barra de ação fixa no rodapé, com o registrar sempre visível e habilitado conforme a
   regra. Fim do "role até o fim".
2. Estados nomeados e distintos no painel: aguardando etiqueta, lendo, baixado —
   confira e registre, registrado.
3. Estado de sucesso explícito ao registrar, com o que foi gravado e saída direta.
4. "Verificar agora" recuado para ação secundária, com uma linha dizendo que é só o status,
   sem baixar a série.
5. Resultado e gráfico acima, ação abaixo; o gráfico deixa de ser a última coisa da rolagem.

## Decisão pendente

Registrar o checkpoint deve continuar exigindo um toque de confirmação, ou deve ser
automático depois de uma coleta bem-sucedida?

Há razão documentada para o toque: o comentário de `LeituraViewModel` diz que a prévia
existe para "o operador ver o veredito antes de assinar embaixo dele", e a cadeia de
custódia em [[ThermoTrace - Segurança e auditoria]] trata o registro como ato do operador.
Automatizar dá o fluxo de um bipe que o produto promete, mas remove essa conferência.

Opção intermediária: registro automático quando a coleta está conforme, e confirmação
obrigatória quando há excursão — o operador só é interrompido quando há algo para decidir.
Não decidido; precisa de definição do usuário e, antes do piloto, do responsável de qualidade.

## Fora deste escopo

Nada aqui corrige o TT-005. A mínima de −29,8 °C do ensaio de 12/09 é anterior à
apresentação: melhorar o gráfico sem resolver a decodificação só deixaria um número errado
mais bonito. A ordem correta é comparar com o app do fabricante e com termômetro aferido,
depois desenhar.

## Executado em 14/09/2026 — commit 82354f1

Os sete problemas do gráfico listados acima foram atacados. O que mudou, e por quê:

1. **Escala ancorada na faixa aprovada**, com folga de 35% da largura da faixa. Ponto fora
   da escala é desenhado na borda com marca de recorte, e o extremo real da série aparece na
   legenda. Era o problema principal: no ensaio de 12/09 a faixa de 7 a 14 °C ocupava 11% da
   altura porque um ponto de −29,8 °C ditava a escala.
2. **Eixo Y rotulado** nos limites da faixa e nos extremos da escala, desenhado no próprio
   canvas. Antes o único número era a legenda "escala X a Y", então não se lia valor nenhum.
3. **Marca de tempo intermediária** além de início e fim.
4. **Trechos de excursão sombreados**, agrupando pontos consecutivos fora da faixa.
5. **Pontos fora da faixa em cor de excursão** e **lacunas como interrupção da linha** —
   ambos já existiam e foram preservados.
6. **Leitura por toque e arraste** (TT-046): linha de leitura sobre o gráfico e o valor,
   horário e situação do ponto no topo. No topo, e não em balão, porque balão num gráfico de
   celular fica embaixo do dedo que o invocou.
7. **Leitura de topo** com o último valor para quem só quer o número.

Decisão mantida: código próprio sobre o `Canvas` existente, sem biblioteca de gráfico. A
semântica de auditoria — faixa preservada na criação da carga, lacunas por `iniciaTrecho`,
excursões — não se encaixa no modelo de dados de uma biblioteca genérica sem a evidência se
acomodar ao gráfico.

**Não corrige o TT-005.** A legenda passou a mostrar sempre o extremo real da série ao lado
da faixa, justamente para o número suspeito continuar visível em vez de ficar escondido
atrás de um desenho melhor. Pendente de validação visual no aparelho.
