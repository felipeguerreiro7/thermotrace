# Arquitetura de dados e auditoria

Como o banco foi desenhado e por quê. Os arquivos estão em `db/`.

```
db/01_schema.sql            estrutura
db/02_funcoes_auditoria.sql MKT, TOR, excursões, ingestão idempotente, cadeia de hash
db/03_seed_e_exemplo.sql    perfis térmicos + exemplo ponta a ponta que roda
db/04_export_excel.sql      visões achatadas para planilha
tools/exportar_excel.py     gera o .xlsx do laudo
```

Ordem de execução: 01 → 02 → 03 → 04.

---

## 1. A pergunta que o banco precisa responder

Não é "qual foi a temperatura". É: **"prove que foi essa temperatura, nesse horário, nessa
caixa, e que ninguém mexeu no dado depois."** Tudo no schema sai daí.

Cinco princípios, escritos no topo do `01_schema.sql`:

| | Princípio | Como aparece no schema |
|---|---|---|
| P1 | Evidência é imutável | triggers `deny_mutation()` em `tag_read`, `measurement_series`, `report`, `audit_log` |
| P2 | O dado bruto sobrevive ao decodificador | `tag_read.raw_sdk_response` + `raw_memory_hex` + `decoder_version` |
| P3 | Três tempos diferentes, sempre separados | `occurred_at` / `recorded_at` / `device_at` + `source` |
| P4 | Toda avaliação grava a regra que a produziu | `rule_version` em `excursion` e `measurement_series` |
| P5 | Idempotência | `tag_read.ingest_key` único |

### Por que P2 não é exagero

Encontramos pelo menos um ponto onde o decodificador do fabricante pode estar errado (a
conversão do ADC de bateria) e uma divergência de base de tempo entre plataformas. Se só
guardássemos a série decodificada, corrigir o decodificador significaria perder todos os laudos
anteriores. Guardando o `raw_sdk_response`, uma correção futura permite **recalcular** a série
e mostrar as duas versões — que é o que uma auditoria aceita.

### Por que P3 não é preciosismo

A especificação funcional já tinha percebido isso (§11): "entregue às 14h, lançado às 19h" é uma
informação diferente de "entregue às 19h". A auditoria precisa distinguir evento automático,
confirmado em tempo real e informado depois. `custody_event.source` carrega isso, e a aba
Custódia da planilha mostra o atraso do lançamento em minutos.

---

## 2. A parte mais delicada: a base de tempo

**A etiqueta não tem relógio confiável.** O epoch de referência é gravado pelo celular no
momento do START (`InstructMap` case 22 no Android, `CMD_SET_START_TIME` no iOS). Toda a linha
do tempo do laudo depende do relógio daquele aparelho, naquele instante.

Três coisas precisam ser guardadas, e as três estão em `monitoring_session`:

```sql
tag_start_epoch       -- o que foi gravado NA etiqueta
device_start_at       -- o que o celular achava que era a hora
device_clock_skew_ms  -- o desvio medido contra uma referência
time_base             -- start_instant (Android) | first_window (iOS)
```

`time_base` existe porque as duas plataformas gravam coisas diferentes no mesmo campo — ver
[04-portabilidade-ios-android.md §2.1](04-portabilidade-ios-android.md). Sem essa coluna, uma
sessão ativada por iPhone e lida por Android sai deslocada de `delay` minutos, sem erro visível.

Na leitura, `ingest_tag_read` calcula a **deriva do oscilador** comparando o fim nominal da série
com o relógio real, e distribui a correção linearmente sobre os pontos. A deriva medida e o fato
de a correção ter sido aplicada vão para o laudo (`rtc_drift_seconds`, `timestamps_corrected`) —
declarar a correção é o que a torna aceitável.

---

## 3. Métricas: por que contar pontos não serve

A especificação original contava pontos fora da faixa. Auditoria de cadeia fria não trabalha
assim, e a diferença é grande:

- Abrir a caixa por 2 minutos gera 1 ponto fora → parece uma excursão.
- Um desvio de 4 horas a 9 °C gera 24 pontos → parece 24 problemas, não um problema grave.

O que `02_funcoes_auditoria.sql` calcula:

- **TOR (Time Out of Range)** — tempo acumulado fora da faixa, separado em acima e abaixo, e a
  **maior excursão contínua**. Essa última é a que decide se o produto ainda serve.
- **MKT (Mean Kinetic Temperature)** — média cinética de Arrhenius com ΔH = 83,144 kJ/mol
  (ΔH/R = 10.000 K). É a métrica que a indústria farmacêutica usa para julgar um lote; uma média
  aritmética esconde picos, a MKT não.
- **Segmentos**, não pontos: `detect_excursions()` devolve intervalos contínuos com início, fim,
  duração, pico e limite violado.
- **`grace_seconds` por perfil**: desvios mais curtos que a tolerância operacional não viram
  ocorrência. Sem isso o sistema cria alarme para cada transferência de doca e o cliente para de
  olhar os alertas — o pior desfecho possível.

Os limiares (`grace_seconds`, `max_tor_seconds`, `mkt_limit_c`) estão em `thermal_profile` e
devem ser acordados com o responsável técnico do cliente, não escolhidos por nós.

---

## 4. Modelagem da série: uma decisão que vale explicar

4.864 pontos por leitura, várias leituras por remessa, milhares de remessas. Uma tabela
normalizada com uma linha por medição chega a centenas de milhões de linhas por ano.

Optamos por **array na `measurement_series`**, uma linha por leitura, com os agregados
pré-calculados, e a função `expand_series()` para produzir `(índice, instante, temperatura)`
quando o laudo ou a planilha precisar.

O raciocínio: a série é escrita uma vez, lida inteira, e nunca sofre `UPDATE` de ponto
individual. Não há consulta real do tipo "todas as medições acima de 8 °C no país" — as consultas
analíticas usam os agregados. Normalizar aqui custaria índice, vacuum e espaço sem trazer
capacidade de consulta.

Se um dia houver necessidade analítica cross-remessa pesada, o caminho é alimentar uma hypertable
TimescaleDB **a partir** daqui. A evidência continua sendo o `tag_read`, então nada se perde.

---

## 5. Offline em primeiro lugar

O operador ativa etiqueta na doca e o destinatário lê em câmara fria — os dois lugares sem sinal.
O celular grava numa fila local e reenvia até o servidor confirmar.

`ingest_tag_read` é idempotente por `ingest_key` (`dev:<install_id>:sess:<id>:read:<n>`,
gerado em `OutboxPayload.ingestKey`). Reenviar dez vezes produz uma linha. É isso que permite
retry agressivo sem medo.

A função também **rejeita a leitura se o UID não bater com a etiqueta da sessão** — a mesma
barreira que o app aplica antes de escrever, repetida no servidor. Validação de identidade em
dois lugares não é redundância: o app pode estar desatualizado.

---

## 6. Cadeia de integridade

Cada `tag_read` guarda:

```
payload_sha256    hash do dado bruto daquela leitura
prev_read_sha256  hash encadeado da leitura anterior da mesma sessão
chain_sha256      sha256(prev || payload)
```

`verify_chain(session_id)` recalcula tudo e aponta exatamente onde quebrou, se quebrar. É a
consulta que se roda na frente do auditor, e é ela que dá sentido ao `verification_code` impresso
no PDF do laudo.

Isso não substitui controle de acesso nem assinatura digital — detecta adulteração do dado
armazenado, que é o risco concreto de um sistema onde três empresas diferentes olham a mesma
remessa.

---

## 7. Saída para Excel

A planilha sai **do banco**, não do celular. O app de referência do fabricante exporta pelo
Android com `jxl` (`.xls`, formato de 1997, biblioteca abandonada) e pelo iOS com libxlsxwriter
(`.xlsx`) — dois arquivos incompatíveis para o mesmo dado, e nenhum deles regenerável depois.

```bash
python tools/exportar_excel.py --dsn "postgresql://usuario@host/thermotrace" --remessa REM-2026-00184
```

Seis abas:

| Aba | Conteúdo |
|---|---|
| Laudo | capa com o veredito geral e como conferir |
| Resumo | uma linha por volume: mín, máx, MKT, TOR, excursões, resultado |
| Medições | a série completa, com a coluna "Situação" já resolvida (sem fórmula no Excel) |
| Gráficos | temperatura × tempo por volume, com as linhas de limite |
| Excursões | cada desvio contínuo, com duração, pico, gravidade e versão da regra |
| Custódia | quando aconteceu × quando foi lançado, e o atraso entre os dois |
| Auditoria | quem leu, com qual aparelho, qual decodificador, e o SHA-256 do dado bruto |

A aba Auditoria é a que sustenta as outras. Sem ela a planilha é um gráfico bonito; com ela é
evidência.

Toda a lógica está nas visões `v_export_*` (`db/04_export_excel.sql`) — o Python só faz `SELECT`
e formata. Trocar o exportador por um serviço em outra linguagem não exige reescrever regra
nenhuma.

---

## 8. O que ainda falta modelar

- **Notificações e aceite da transportadora** (spec §7): estados Enviado → Visualizado → Aceito.
- **Permissões por papel**: o schema tem `membership`, mas as regras de quem vê o quê ainda são
  de aplicação. Row Level Security do Postgres é o caminho natural.
- **Retenção**: definir com o cliente por quanto tempo o dado fica (cadeia fria costuma pedir
  anos) e como se faz o arquivamento sem quebrar a cadeia de hash.
- **LGPD**: `custody_event.receiver_name` e `geo` são dado pessoal. Definir base legal, prazo de
  retenção e política de anonimização.
