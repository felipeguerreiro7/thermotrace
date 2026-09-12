# Portabilidade iOS + Android — os pontos de código que importam

Análise dos três pacotes do fornecedor:

| Pacote | O que é |
|---|---|
| `IPHONE FMTemperature-SDK (1).zip` | App iOS completo em Objective-C (`FMTemperature`) |
| `DT160 Andorid APP and source 9.3.5 SDK (3).zip` | App Android de referência `com.fmsh.DT160` v9.3.5 + módulo `nfcinstruct` |
| `DT160 Andorid APP_9.3.5 (1).zip` | O APK compilado do mesmo app |

---

## 1. O iPhone está confirmado. Não é mais uma pergunta em aberto.

A conversa do PDF tratava o iOS como "provável, precisa validar". **O material
entregue prova que roda, e roda em API pública.** Três evidências, todas no código:

**1. O entitlement.** `FMTemperature/FMTemperature.entitlements` contém exatamente isto:

```xml
<key>com.apple.developer.nfc.readersession.formats</key>
<array><string>TAG</string></array>
```

`TAG` é o formato público de leitura de tags. Não há entitlement especial, não há
API privada, não há hardware proprietário. `Info.plist` traz apenas o
`NFCReaderUsageDescription` obrigatório.

**2. O transporte.** `NFCHelper/NFCTagObject.m` envia todo comando proprietário por:

```objc
[blockTag customCommandWithRequestFlag:RequestFlagHighDataRate
                     customCommandCode:cmdCode
               customRequestParameters:cmdData
                     completionHandler:...];
```

`customCommandWithRequestFlag:` é Core NFC público desde o iOS 13. Ela existe
precisamente para comandos proprietários ISO 15693 — e o Core NFC insere sozinho
o manufacturer code (0x1D, Fudan), por isso os comandos iOS não o carregam,
enquanto no Android o `INfcV` o escreve à mão em `{0x02, cmd, 0x1D, ...}`.

**3. O ciclo inteiro está implementado.** `NFCTagHelper.m` tem `LoggingStartType`,
`LoggingStopType` e `LoggingResultType`: configurar → START → afastar → reaproximar
→ baixar histórico. É o ciclo que se queria validar comercialmente.

**Conclusão: pode afirmar Android + iPhone.** As restrições são de UX, não de
viabilidade — sessão de ~20 s, sem background, iPhone 7+/iOS 13+.

---

## 2. Três divergências entre os SDKs que quebram o produto em silêncio

Estas não aparecem em teste de bancada com um aparelho só. Aparecem em produção,
quando o embarcador ativa por Android e o destinatário lê por iPhone.

### 2.1 A base de tempo é diferente nas duas plataformas — **a mais grave**

Ambos gravam um epoch na etiqueta no momento do START. Só que gravam coisas
diferentes:

| | Onde | O que grava |
|---|---|---|
| Android | `InstructMap.instructA/V` case 22 | `System.currentTimeMillis()/1000` — o instante do START |
| iOS | `NFCTagHelper.m`, `CMD_SET_START_TIME` | `now + delayMinutes*60` — o instante da primeira janela |

```objc
// NFCTagHelper.m
NSInteger nowTimestamp = [[CommonUtils getNowTimeTimestamp] integerValue];
intValue = [CommonUtils getDecimalByHex: _hexDelayMinutes] * 60;
nowTimestamp = nowTimestamp + intValue;      // <-- o delay entra no epoch
```

```java
// InstructMap.java, case 22
long time = System.currentTimeMillis() / 1000;   // <-- o delay NÃO entra
```

Com `delay = 0` (o padrão do MVP) as duas coincidem e o problema fica invisível.
No dia em que alguém usar delay — e vai usar, é o jeito natural de esperar a caixa
fechar antes de começar a medir — **a série sai deslocada de `delay` minutos, sem
erro, sem aviso, com aparência perfeita.**

Solução implementada: o tipo `TimeBase`
([SessionDecoder.kt](android/com/thermotrace/core/SessionDecoder.kt),
[ThermoTraceNFC.swift](ios/ThermoTraceNFC.swift)) e a coluna
`monitoring_session.time_base` no banco. A fórmula do primeiro ponto passa a
depender de quem ativou:

```
start_instant (Android):  t₀ = startEpoch + delay*60 + intervalo
first_window  (iOS):      t₀ = startEpoch            + intervalo
```

### 2.2 O UID vem invertido entre as plataformas

`GeneralNFC.getUid()` no Android devolve `byteToHex(tag.getId())` — direto.
`NFCTagObject.m` no iOS **inverte os 8 bytes** de `NFCISO15693Tag.identifier`:

```objc
self.uuid = [NSString stringWithFormat:@"%@%@%@%@%@%@%@%@",
             [self.uuid substringWithRange:NSMakeRange(14, 2)], ... ];
```

O fornecedor inverteu justamente para casar com o app Android — ou seja, a
diferença entre as plataformas é real e conhecida por eles.

Para nós isso é crítico porque o UID é chave única no banco (`tag.nfc_uid`) e é o
que valida o QR contra a etiqueta. Se as duas formas entrarem, a mesma etiqueta
vira dois registros e a verificação passa a recusar etiquetas válidas.
Solução: `TagIdentity.canonical()` / `FMTagTransport.canonicalUID`, aplicados na
borda, antes de comparar ou persistir.

### 2.3 Existem dois mapas de comandos incompatíveis no material

O endereço onde o relógio é gravado não é o mesmo nos dois códigos Android:

| Origem | Comando de escrita do relógio | Endereço |
|---|---|---|
| `nfcinstruct` (SDK) | `{0x40,0xB3,0x01,0x40,0x03,...}` | **0x0140** |
| app DT160 9.3.5 | `{0x40,0xB3,0x00,0x20,0x03,...}` | **0x0020** |
| iOS `CMD_SET_START_TIME_A` | `40B30140030000%@` | **0x0140** |

O iOS bate com o `nfcinstruct`. O app DT160 9.3.5 usa outro mapa — provavelmente
outra variante/revisão de chip.

**Adote `nfcinstruct` + `nfcTemperature.pch` como mapa canônico**, que é o único
presente nas duas plataformas. E confirme contra a etiqueta física antes de
produção: escrever no endereço errado corrompe a memória da etiqueta.

---

## 3. Tabela canônica de comandos (as duas plataformas)

Base: `nfcTemperature.pch` (iOS) e `InstructMap.java` (Android). O sufixo `_V` é
ISO 15693 e o `_A` é ISO 14443-A.

**Use sempre a coluna 15693.** É o único caminho que o iOS aceita para comandos
proprietários: `sendMiFareCommand` não passa códigos `0xC0–0xCF`. Padronizar nele
faz Android e iOS executarem byte a byte a mesma sequência.

| Operação | ISO 15693 (iOS: code + params) | Android `INfcV` monta | 14443-A |
|---|---|---|---|
| Acordar (sair do power-down) | `C4 00` | `{02,C4,1D,...}` | `40C40000000000` |
| Status do power-down | `C4 80` | idem | `40C48000000000` |
| Dormir | `C3 01` | idem | `40C30100000000` |
| Status do chip | `CF 010000` | idem | `40CF0100000000` |
| Init de registradores (`initUHF`) | `CE 00` | idem | `40CE0000000000` |
| Medição instantânea — disparo | `C0 0600` | idem | `40C00600000000` |
| Medição instantânea — resultado | `C0 8400` | idem | `40C08400000000` |
| Bateria (1) configurar reg. | `C5 C0120008` | idem | `40C5C012000800` |
| Bateria (2) medir | `C0 1200` | idem | `40C01200000000` |
| Bateria (3) ler | `C0 9200` | idem | `40C09200000000` |
| Bateria (4) restaurar | `C5 C0120000` | idem | `40C5C012000000` |
| Campo (field strength) | `D0 00` | idem | `40D00000000000` |
| Delay (registrador, min) | `C5 C08400 nn` | idem | `40C5C08400nn00` |
| Delay (EEPROM, p/ o app) | `B3 01100100 nn` | idem | `40B3011001000000nn` |
| Intervalo (registrador, s) | `C5 C085 nnnn` | idem | `40C5C085nnnn00` |
| Intervalo (EEPROM) | `B3 011401 nnnn` | idem | `40B30114010000nnnn` |
| Quantidade de registros | `B3 B09401 nnnn` | idem | `40B3B094010000nnnn` |
| Limite mínimo (°C × 4) | `B3 B08001 nnnn` | idem | `40B3B080010000nnnn` |
| Limite máximo (°C × 4) | `B3 B08201 nnnn` | idem | `40B3B082010000nnnn` |
| **Gravar o relógio** | `B3 014003 tttttttt` | idem | `40B30140030000tttttttt` |
| **START do logger** | `C2 0000000000` | idem | `40C20000000000` |
| Desafio para parar | `B2` | idem | `40B20000000000` |
| STOP do logger | `C2 80 rrrrrrrr` | idem | `40C280rrrrrrrr` |
| Ler o relógio gravado | `B1 01400003` | idem | `40B10140000300` |
| Tamanho da área de dados | `B1 B0540003` | idem | `40B1B054000300` |
| Quantidade programada | `B1 B0940003` | idem | `40B1B094000200` |
| Faixa min/max configurada | `B1 B0800003` | idem | `40B1B080000300` |
| Registros gravados (parado) | `B1 B1880000` | idem | `40B1B188000000` |
| Registros gravados (medindo) | `C6 C091` | idem | `40C6C09100000000` |
| Ler bloco de memória | `B1 aaaa llll` | idem | `40B1aaaallll00` |
| LED liga / desliga | `C9 02` / `C9 01` | idem | `40C90200000000` / `40C90100000000` |

Retornos: `5555` = saiu do power-down · `0000` = START aceito · `0100` = STOP aceito.

### Conversões que precisam bater nas duas pontas

```
Limites de temperatura   registrador = °C × 4       (LSB 0,25 °C)
                         negativos   = °C × 4 + 0x400   (complemento de 2, 10 bits)
Amostra de temperatura   10 bits com sinal ÷ 4  (ou ÷ 8 se o bit de config pedir)
Contagem / intervalo     little-endian, 2 bytes
Relógio                  epoch UNIX, 4 bytes big-endian
```

Capacidades por modo (as guardas estão em `NFCUtils.startLogging:539`):

| Modo | `mode` | Bytes/amostra | Capacidade |
|---|---|---|---|
| Normal | 3 | 4 → 1 | 4.864 |
| Raw | 7 | 4 → 2 | 9.728 |
| Comprimido | 1 | 4 → 3 | 14.592 |
| Limit2 | 6 | 4 → 8 | 38.912 — **grava a faixa, não o valor. Não serve para laudo.** |

---

## 4. Restrições de plataforma que mudam o desenho

### iOS

- **Sessão de ~20 s, sem background.** Baixar 4.864 pontos não cabe numa
  aproximação. O `readData()` do Android já lê em blocos de 248 bytes — no iOS
  isso vira leitura retomável: guarde o offset, atualize `session.alertMessage`
  com o progresso e peça reaproximação. Planeje a UI para isso desde o começo.
- Cada sessão precisa de gesto do usuário. Não existe "ler ao encostar" como no
  reader mode do Android.
- iPhone 7+ / iOS 13+. O código entregue usa async/await (iOS 15+); com
  completion handlers desce para iOS 13.
- Capability "Near Field Communication Tag Reading" no perfil de provisionamento.

### Android

- `enableReaderMode` **só com `FLAG_READER_NFC_V`**. Sem isso, `NFCUtils.parseTag`
  pega a primeira tech da lista e cai no caminho 14443 numa etiqueta de dupla
  interface — e aí o iOS não consegue reproduzir a mesma sequência.
- Somar `FLAG_READER_SKIP_NDEF_CHECK` e `FLAG_READER_NO_PLATFORM_SOUNDS`.
- **Uma única thread para todo o SDK.** `NFCUtils` guarda `chipType`, `standard`,
  `offset`, `detA`, `detB` em `static`, e `InstructMap.parameterArr` também.
  Concorrência aplica a calibração de uma etiqueta na outra.
- O SDK conecta a tag e nunca fecha. Feche explicitamente ao fim de cada operação.

---

## 5. Sobre o app de referência DT160 9.3.5: use como documentação, não como base

O código é útil para conferir a sequência de comandos. Como base de produto, não:

- `com.android.support:28.0.0` — pré-AndroidX, sem manutenção.
- Android Gradle Plugin 3.4.2 e **jcenter** — repositório desligado.
- `targetSdkVersion 30` — abaixo do mínimo exigido hoje pela Google Play; não sobe
  para a loja como está.
- `jxl.jar` para Excel — gera `.xls` de 1997, biblioteca abandonada.
- **`app/keystore/DT160.jks` está no zip, com `storePassword '123456'` em texto
  claro no `build.gradle`.** Nunca assine nada com essa chave e não a versione.

O que aproveitar: `ExcelUtils.java`, `PdfUtils.java` e `LineChartManager.java`
mostram o layout de relatório que o mercado já espera. Nós geramos o mesmo
conteúdo pelo backend (`db/04_export_excel.sql` + `tools/exportar_excel.py`), o
que resolve o problema de o Android gerar `.xls` e o iOS gerar `.xlsx` —
dois formatos incompatíveis para o mesmo dado.

---

## 6. Um ponto de segurança que muda o desenho

Parar o logger **não exige segredo nenhum**. O fluxo do iOS
(`LoggingStopType`) é: pedir um número aleatório à etiqueta (`CMD_GET_RANDOM`),
embaralhar os bytes segundo uma regra fixa e devolver em `CMD_STOP_LOGGING`. Toda
a "autenticação" está no código do app — qualquer pessoa com o app genérico FMSH
pode parar ou reinicializar uma etiqueta em trânsito.

O Android tem um caminho paralelo com senha (`stopLogging(pwd)`,
`settingPassword`, `updatePassword`), que sugere que o chip suporta proteção real.
**Validar com o fornecedor se `settingPassword` bloqueia o STOP** e, se bloquear,
provisionar senha no comissionamento das etiquetas. Enquanto isso não estiver
resolvido, o laudo precisa registrar que a etiqueta é interrompível por terceiros
— é uma limitação honesta, não um defeito escondido.

---

## 7. O que testar, nesta ordem

1. **Android, NfcV forçado**: identificar → ativar com delay = 0 → ler histórico.
   Confere UID canônico e a série com timestamps.
2. **iPhone, mesma etiqueta ativada pelo Android**: só leitura. Valida o UID
   canônico e a convenção `start_instant` vinda de outra plataforma.
3. **iPhone ativa, Android lê.** Valida `first_window`. Sem o `time_base` no
   banco, este teste sai com a série deslocada.
4. **Delay = 30 min nas duas plataformas.** É o teste que expõe a divergência 2.1;
   se passar, a base de tempo está resolvida.
5. **Sessão longa no iOS**: 2.000+ pontos, medindo quantas reaproximações são
   necessárias. Define a UX da leitura no destino.
6. **Leitura através da caixa de isopor fechada**, nas duas plataformas. A antena
   do iPhone é menor e mais direcional que a da maioria dos Android — pode passar
   num e não no outro.
