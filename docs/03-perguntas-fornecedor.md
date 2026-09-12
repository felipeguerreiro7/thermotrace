# Perguntas ao fornecedor — versão revisada

A lista da conversa original tinha sete itens. Depois de ler os três SDKs, **cinco já estão
respondidos pelo próprio material entregue**. Perguntar de novo o que eles já enviaram enfraquece
a posição na negociação e dá margem para resposta vaga.

## Já respondido — não perguntar

| Pergunta original | Onde está a resposta |
|---|---|
| Core NFC do iPhone permite START/STOP/configuração? | `FMTemperature.entitlements` (só `TAG`) + `NFCTagObject.m` (`customCommandWithRequestFlag:`). Sim, e em API pública. |
| Exemplo de código para inicializar e dar START no iOS | `NFCTagHelper.m`, bloco `LoggingStartType` |
| Exemplo de código para ler o histórico no iOS | `NFCTagHelper.m`, bloco `LoggingResultType` + `getTempDetailA:` |
| Alguma função depende de API privada da Apple? | Não. O entitlement é o padrão público de leitura de tags. |
| Fabricante do IC | `com.fmsh` = Shanghai Fudan Microelectronics; o README do SDK cita "DT160" |

## Perguntar — e por quê

**1. Confirmação formal do part number e da revisão do IC.**
Tudo indica FM13DT160 da Fudan Microelectronics. Peça a confirmação por escrito, com a revisão
de máscara, e o **datasheet do CI** — não o da etiqueta MI8654TE.
*Por que importa:* é o que permite trocar de montador sem trocar de software, e é o documento que
uma auditoria pede quando questiona a exatidão do sensor.

**2. Qual mapa de comandos a NOSSA etiqueta usa.**
Há dois mapas incompatíveis no material que vocês nos enviaram. O comando de gravação do relógio
aponta para endereços diferentes:

- SDK `nfcinstruct` e o app iOS → endereço **0x0140**
- app Android DT160 9.3.5 (`com/fmsh/temperature/util/NFCUtils.java`) → endereço **0x0020**

Pergunta objetiva: *para o modelo que vamos comprar, qual dos dois é o correto?*
*Por que importa:* escrever no endereço errado corrompe a memória da etiqueta.

**3. Certificado de calibração rastreável, por lote.** — *o item mais importante da lista*
Precisamos, para cada lote:
- certificado de calibração rastreável (RBC/INMETRO ou equivalente reconhecido);
- pontos de calibração usados (no mínimo três, ex.: 0 °C / 5 °C / 25 °C);
- incerteza declarada de medição;
- política e periodicidade de recalibração;
- se a calibração é por lote ou por peça.

*Por que importa:* sem isso, os ±0,5 °C do datasheet não valem como evidência numa auditoria de
distribuição de medicamentos. É o item que pode inviabilizar a venda para o cliente final.

**4. `settingPassword` bloqueia o STOP do logger?**
No fluxo do app iOS de vocês, parar a medição não exige segredo: a etiqueta devolve um número
aleatório (`CMD_GET_RANDOM`) e o app o devolve embaralhado. Qualquer pessoa com o app genérico
pode parar uma etiqueta em trânsito.
Perguntas: `settingPassword`/`updatePassword` protegem o `stopLogging`? Qual a senha de fábrica?
Como provisionar senha em lote na fabricação?

**5. Consumo real e vida útil no nosso perfil.**
Nosso caso é medição a cada 10 minutos, por até 30 dias, com 2 a 3 leituras NFC no período.
Peçam: curva de consumo declarada, tensão de corte em que o logger para de gravar, e o número de
ciclos de reutilização (`stopLogging` + re-init) suportados.
*Por que importa:* define a promessa comercial e a política de aposentadoria da etiqueta.

**6. Arquivo de vínculo do lote (Serial/QR ↔ NFC UID ↔ UHF EPC ↔ TID).**
A Anna já confirmou que conseguem fazer o vínculo. Falta o formato de entrega:
- CSV ou XLSX, uma linha por etiqueta, com as quatro colunas;
- **uma amostra de 10 a 20 linhas fictícias antes do pedido grande**, para prepararmos o
  importador e o banco;
- e uma definição importante: **o UID vem em qual ordem de bytes?** Android e iOS entregam a
  mesma etiqueta com os bytes invertidos um em relação ao outro — o próprio código de vocês faz
  a inversão no iOS. Precisamos saber qual das duas formas estará no arquivo.

**7. Conversão do ADC de bateria em volts.**
Os comandos `C5C0120008 → C01200 → C09200` devolvem um valor bruto. Qual a fórmula exata de
conversão para volts, e qual a tensão mínima em que o logger ainda grava de forma confiável?

**8. Versão mínima de iOS e modelos validados.**
O app de vocês declara iOS 13+. Confirmam que foi testado em iPhone 7 e em modelos recentes
(15/16)? Houve mudança de comportamento do Core NFC em alguma versão?

---

## Como enviar

Mande os oito itens numerados, em uma única mensagem, deixando explícito que os itens 1, 2 e 3
são bloqueantes para o pedido. Peça prazo de resposta. Para o item 3, deixe claro que
**um lote sem certificado de calibração não será aceito** — isso muda a resposta que você recebe.
