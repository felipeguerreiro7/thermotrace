# Análise crítica — ThermoTrace v0.1

Base analisada: `ChatGPT - NFC.pdf` (52 p.), `Especificacao_Funcional_MVP_Monitoramento_Termico_v1.0.docx`
e o código real de `ThermoTrace_MVP_Android_v0.1.zip`.

---

## 1. O que a análise do código muda em relação à conversa do PDF

A conversa deixou três pontos como "em aberto". Dois deles já estão respondidos **dentro do
próprio zip que o fornecedor entregou** — ninguém abriu o código.

### 1.1 O part number do CI está no código (pergunta considerada aberta)

O módulo `nfcinstruct/` não é código genérico: é o SDK do fabricante do silício.

- Package: `com.fmsh.nfcinstruct` → **FMSH = Shanghai Fudan Microelectronics**.
- O `README.md` do próprio zip diz: *"baseado no SDK Android **DT160**/FMTemperature"*.
- O datasheet da etiqueta declara **160 kbit** de memória — o mesmo "160" do nome.
- Em `INfcV.java`, **todo** comando é montado como `{0x02, <cmd>, 0x1D, ...}`. O byte `0x1D` é
  o código de fabricante ISO/IEC 7816-6 da Fudan Microelectronics, obrigatório nos comandos
  customizados ISO 15693.

Conclusão: o CI é da família **FM13DT160 (Fudan Microelectronics)**. `MI8654TE` é o
part number da etiqueta montada (encapsulamento + antena + bateria + sensor), não do silício.
A pergunta ao fornecedor deixa de ser *"qual é o chip?"* e passa a ser
*"confirme que é FM13DT160, em qual revisão, e envie o datasheet do IC"* — uma pergunta muito
mais difícil de despachar com evasiva.

> **Atualização (SDKs recebidos depois desta análise).** O pacote
> `IPHONE FMTemperature-SDK` e o `DT160 Android APP and source 9.3.5 SDK` confirmaram o que
> esta seção previa e trouxeram três divergências novas entre as plataformas. A análise
> completa está em [04-portabilidade-ios-android.md](04-portabilidade-ios-android.md);
> o resumo do que mudou:
>
> - **iOS deixou de ser hipótese.** O entitlement do app do fornecedor é só
>   `nfc.readersession.formats = [TAG]` e o transporte é `customCommandWithRequestFlag:` —
>   Core NFC público, ciclo completo implementado. Pode afirmar Android + iPhone.
> - **Base de tempo divergente:** o Android grava o epoch do START, o iOS grava
>   `START + delay`. Com `delay = 0` ninguém percebe; com delay a série sai deslocada em
>   silêncio. Tratado pelo tipo `time_base` no banco.
> - **UID invertido entre plataformas**, e **dois mapas de comandos incompatíveis** no
>   material (o app DT160 9.3.5 grava o relógio em outro endereço que o SDK e o iOS).

### 1.2 O iPhone é viável via Core NFC público — mas só por um dos dois caminhos

O SDK implementa duas pilhas paralelas: `INfcA` (ISO 14443-A) e `INfcV` (ISO 15693).
Isso é decisivo, porque o iOS trata as duas de forma radicalmente diferente:

| Caminho | Android | iOS (Core NFC público) |
|---|---|---|
| `INfcV` / ISO 15693 | `NfcV.transceive()` | **`NFCISO15693Tag.customCommand(...)`** — aceita comandos proprietários |
| `INfcA` / ISO 14443-A | `NfcA.transceive()` | `NFCMiFareTag.sendMiFareCommand()` — restrito a MIFARE; comandos `0xC0–0xCF` proprietários não passam |

Todos os comandos do ciclo crítico (`0xC2` START RTC, `0xC4` wakeup, `0xC5`/`0xC6` write/read
reg, `0xB1`/`0xB3` read/write memory, `0xCF` status) caem na faixa de **custom commands do
ISO 15693 (0xA0–0xDF)** e já vêm com o manufacturer code `0x1D`. É exatamente a assinatura que
`customCommand(requestFlags:customCommandCode:customRequestParameters:)` espera.

> **Decisão de arquitetura recomendada: padronizar a operação em ISO 15693 (NfcV) e tratar o
> caminho NfcA como legado.** Um único protocolo em Android e iOS, sem SDK proprietário no iOS,
> sem API privada da Apple.

Restrições reais do iOS que mudam a UX (não bloqueiam):
- entitlement `com.apple.developer.nfc.readersession.formats = TAG` + `NFCReaderUsageDescription`;
- iPhone 7 ou superior, iOS 13+;
- a sessão NFC do iOS expira em ~20 s e não roda em background. Ler 4.864 pontos exige leitura
  em blocos com progresso e retomada — o `readData()` do SDK Android já lê em chunks de 248 bytes,
  o que é compatível com isso se for reescrito como fluxo retomável.

### 1.3 O que continua legitimamente em aberto

- Revisão/máscara exata do IC e datasheet do silício — e **qual dos dois mapas de comandos
  a nossa etiqueta usa** (ver portabilidade §2.3).
- **Certificado de calibração rastreável** do sensor por lote (§4.1) — ninguém pediu ainda, e é
  o item que mais pode inviabilizar o produto numa auditoria farmacêutica.
- Se `settingPassword` realmente bloqueia o STOP. No fluxo do iOS, parar o logger não exige
  segredo algum: a etiqueta fornece um número aleatório e o app o devolve embaralhado.
- Conversão exata do ADC de bateria em volts (o valor hoje é indicativo).
- Vida útil real da bateria no perfil de 10 min (5 mAh é pouco; §4.2).

---

## 2. Defeitos no código v0.1

Ordenados por impacto. Todos verificados no fonte.

### 2.1 Críticos — comprometem o dado de auditoria

**D1. A etiqueta é ativada sem verificar qual etiqueta é.**
`NfcTagService.handleTag()` executa a ação pendente em *qualquer* tag que entrar no campo.
O `onTagDiscovered(uid)` apenas grava `shipment.tagUid = uid` — não compara com nada.
A própria conversa do PDF identificou o cenário ("30 etiquetas sobre a bancada"), mas o código
não implementa a checagem. Consequência: ativa-se a etiqueta errada e o histórico inteiro fica
associado ao volume errado. É o pior defeito possível num sistema de auditoria.

**D2. A base de tempo de toda a auditoria é o relógio do celular, e isso não é registrado.**
`InstructMap.instruct*(22)` grava na etiqueta `System.currentTimeMillis()/1000`.
Não existe RTC confiável na etiqueta: o `startTime` devolvido em `response[1]` é literalmente o
relógio do telefone no instante do START. Se o celular do operador estiver 40 minutos adiantado,
**todo o histórico térmico está 40 minutos deslocado** e nada no sistema registra isso.
Um auditor derruba o laudo com essa única pergunta.
Correção: medir o desvio do dispositivo contra o relógio do servidor no START, persistir
`device_clock_utc` **e** `server_clock_utc`, e recalcular a deriva na leitura final.

**D3. `getLoggingResult` não devolve timestamps — e a v0.1 nem tenta reconstruí-los.**
`response[12...]` são apenas temperaturas. O timestamp do ponto *i* (0-based) é
`t_i = startTime + delay*60 + (i+1) * interval`.
`summarizeHistory()` em `NfcActivationActivity` imprime o resumo e **descarta a série**.
Hoje o app não produz cadeia fria — produz um resumo na tela.

**D4. A deriva do RTC não é medida, embora o dado esteja disponível de graça.**
No fim da leitura você tem `startTime`, `interval`, `n` pontos e o relógio atual. A diferença
entre `startTime + delay + n*interval` e o instante real da leitura é a deriva acumulada do
oscilador da etiqueta. Em 30 dias isso pode ser dezenas de minutos. O SDK chega a calcular
`timeDifference` (`NFCUtils.java:780`) e joga fora. Deve ser persistida, usada para corrigir
linearmente os timestamps, e declarada no laudo.

**D5. Ativação não é atômica nem verificada.**
`activate()` faz `initUHF()` → `startLogging()`. Se o operador afastar o celular no meio, a
etiqueta fica parcialmente configurada e o app não registra nada. Não há `checkStatus()` de
confirmação depois do START. É preciso: START → reaproximar/verificar → só então marcar o
volume como monitorado.

**D6. Persistência inexistente.** `InMemoryRepository` é uma `static List`. Morreu o processo,
morreu a remessa. É exatamente o que o banco de dados resolve.

### 2.2 Graves — erro silencioso de configuração

**D7. Comparação de perfil térmico por travessão.**

```java
shipment.thermalProfile.contains("15–25") ? 15 : 2
```

O caractere é `–` (en dash, U+2013). Se o perfil vier em qualquer ponto com `-` (hífen comum),
a comparação falha **silenciosamente** e a etiqueta é configurada como **2–8 °C**. Uma carga
15–25 °C monitorada com limites 2–8 °C gera excursão falsa em 100% dos casos.
Perfil térmico nunca pode ser string livre: tem que ser ID com faixa numérica.

**D8. `loggingCount` fixo em 1000 e sem tratamento de limite.**
`nfc.configure(min, max, 600, 1000)`. Em modo normal (mode 3) o teto é 4.864 pontos
(`NFCUtils.java:539`). 1000 × 600 s = 166 h ≈ 6,9 dias — silenciosamente insuficiente para
transportes mais longos; a etiqueta simplesmente para de gravar. Quando o limite é excedido o
SDK retorna `onResult(false, "0")` e o app mostra só *"A etiqueta recusou o início do logger"*.
A especificação já manda calcular esse número pela duração prevista — não foi implementado.

**D9. Limites térmicos só aceitam graus inteiros.**
`startLogging(..., int minTemperature, int maxTemperature, ...)` e os registradores gravam
`value * 4` (LSB de 0,25 °C). Perfis como `-0,5 °C` ou `2,5 °C` são impossíveis pela API pública
do SDK — mas são possíveis escrevendo o registrador direto (`writeReg` em `0xB080`/`0xB082`).
Se o cliente exigir faixa fracionária, o caminho existe.

**D10. Qualquer pessoa com o app pode parar o logger.**
`stopLogging(pwd, ...)`, `settingPassword` e `updatePassword` existem — mas o MVP nunca define
senha. Sem provisionamento de senha no comissionamento, um terceiro com um app genérico FMSH
pode parar ou reinicializar a etiqueta em trânsito. Isso destrói a integridade da prova.

### 2.3 Médios — engenharia

**D11. O SDK guarda estado global mutável entre etiquetas.**
`NFCUtils.chipType`, `standard`, `offset`, `detA`, `detB` são `static` (linha 1085+), e
`InstructMap.parameterArr` também. Ler a etiqueta A e depois a B pode aplicar as constantes de
calibração de A em B. `GeneralNFC` é singleton com `Tag` estático. **Serialize todo acesso ao
SDK numa única thread/mutex e reexecute a detecção de chip a cada tag.**

**D12. `pendingAction` sem `volatile`/sincronização** — escrito na UI thread, lido na thread de
reader mode do NFC.

**D13. Reader mode sem as flags corretas.** Falta `FLAG_READER_SKIP_NDEF_CHECK` (o check de NDEF
da plataforma interfere na sessão de transceive) e `FLAG_READER_NO_PLATFORM_SOUNDS`.

**D14. `android:allowBackup="true"`** num app que carrega evidência de auditoria.

**D15. `android.app.Activity` puro, sem `ViewModel`, `Shipment` `Serializable` via Intent.**
Rotação de tela no meio de uma ativação NFC perde o estado. O modelo de 1 etiqueta por remessa
(`shipment.tagUid`) contradiz a especificação (N volumes → N etiquetas).

---

## 3. Onde a especificação funcional está certa — e onde falta

A especificação v1.0 está boa: separa `occurred_at` de `recorded_at` (§11), separa aceite de
custódia (§7), reconhece a ausência de telemetria (§3), reserva EPC/TID. Isso é maduro.

Faltam quatro coisas para o produto sobreviver a uma auditoria real:

**F1. Métricas de excursão erradas para o setor farmacêutico.**
A especificação (§9, §14) conta *pontos* fora da faixa. Auditoria de cadeia fria não trabalha
assim. O padrão é:

- **TOR (Time Out of Range)** — minutos acumulados fora da faixa e a **maior excursão contínua**;
- **MKT (Mean Kinetic Temperature)** — temperatura cinética média (Arrhenius, ΔH = 83,144 kJ/mol),
  a métrica usada na indústria farmacêutica para decidir se um lote continua utilizável;
- limites de **alerta** (soft) separados dos limites de **ação** (hard).

Contar pontos superestima excursões curtas (abrir a caixa por 2 min) e subestima desvios longos.

**F2. Versionamento das regras.** Se a regra de excursão mudar em 2027, os laudos de 2026 têm de
continuar reproduzindo o veredito original. Toda avaliação precisa gravar `rule_version` junto
com o resultado.

**F3. Preservação do dado bruto.** A especificação manda guardar o conteúdo bruto do QR (§6.2),
o que é ótimo — mas não diz nada sobre o **payload bruto da etiqueta**. Auditoria exige
reprodutibilidade: o array cru devolvido pelo SDK (idealmente também os blocos hex) tem de ser
armazenado imutável, para que a série decodificada possa ser recalculada e conferida anos depois
com um decodificador corrigido.

**F4. Ciclo de vida da etiqueta.** Bateria de 5 mAh não recarregável, reutilizável via
`stopLogging` + re-init. Nada na especificação controla quantos ciclos a etiqueta já rodou, qual
a tensão atual e quando ela deve ser aposentada. Uma etiqueta que morre no meio do transporte
gera um vazio de dados que o cliente vai cobrar de você.

---

## 4. Riscos de negócio ainda não levantados

### 4.1 Calibração rastreável — risco existencial

`getBasicData` devolve temperatura, mas nenhum documento do projeto menciona **certificado de
calibração rastreável** (RBC/INMETRO ou equivalente). Numa auditoria ANVISA de distribuição de
medicamentos (RDC 430/2020 — Boas Práticas de Distribuição, Armazenagem e Transporte), a primeira
pergunta diante de um registrador de temperatura é *"cadê o certificado de calibração?"*.
Sem isso, os ±0,5 °C declarados em datasheet não valem como evidência.
Pedir ao fornecedor: certificado por lote, pontos de calibração (mín. 3, ex. 0/5/25 °C),
incerteza declarada e política de recalibração. Modelar isso no banco desde já.

> Confirme o enquadramento regulatório exato com o responsável técnico/RA do cliente. O ponto
> aqui é de engenharia: o dado tem de existir e ser rastreável, qualquer que seja a norma citada.

### 4.2 Orçamento energético

5 mAh, não recarregável, "mais de um mês" segundo o fabricante — com a ressalva de que depende da
frequência de medição. A 10 min são 144 medições/dia. Antes de qualquer promessa comercial:
teste de bancada com 3 etiquetas em ciclo real de 30 dias, medindo tensão a cada 48 h. E o app
deve **bloquear a ativação abaixo de um piso de tensão** (hoje ele lê a tensão e não faz nada).

### 4.3 Leitura através do isopor

A especificação (§15) reconhece que não foi testado. É um teste de 20 minutos que muda o desenho
do produto inteiro: se não ler, a posição da etiqueta precisa mudar, e a jornada do destinatário
— que é o fechamento do ciclo — não funciona. **Fazer esse teste antes dos wireframes.**

### 4.4 O destinatário sem conta (spec §12)

"Não obrigar o destinatário a criar conta" + "aproximar NFC" são incompatíveis em iOS sem app
instalado: Core NFC exige um app com entitlement. Em Android, Instant Apps foi descontinuado.
Na prática o destinatário vai precisar instalar o app. Melhor assumir isso agora: o QR leva a uma
página de laudo somente-leitura (sem NFC, sem conta) e o app só é exigido de quem faz a leitura
final da etiqueta.

---

## 5. Ordem de trabalho recomendada

| # | Ação | Por quê |
|---|---|---|
| 1 | Teste físico: NFC através de isopor fechado | Muda o produto inteiro; custa 20 min |
| 2 | Teste de bancada de bateria (30 dias, 3 etiquetas) | Define a promessa comercial |
| 3 | Corrigir D1, D2, D3, D5 no app | Sem isso não existe dado auditável |
| 4 | Subir o banco (`db/01_schema.sql`) e o outbox offline | Fim do `InMemoryRepository` |
| 5 | Provisionar senha nas etiquetas (D10) | Integridade da prova |
| 6 | Enviar `docs/03-perguntas-fornecedor.md` | Calibração e iOS |
| 7 | POC iOS: `customCommand` ISO 15693, ler UID + temperatura | Confirma paridade em 1 dia |
