# Fase 2 — Modelo de dados

PostgreSQL 17. Este documento é a especificação; as migrações Alembic saem na Fase 3.

---

## 1. Decisão: o schema fica em português

Hoje há três vocabulários no projeto:

| Camada | Idioma | Exemplo |
|---|---|---|
| App Android (11 tabelas Room) | português | `remessa`, `etiqueta`, `leitura` |
| `db/01_schema.sql` (20 tabelas) | inglês | `shipment`, `tag`, `tag_read` |
| API (a escrever) | — | — |

**Padronizo tudo em português.** O argumento não é gosto: num sistema cujo núcleo é sincronizar
dados entre app e servidor, cada tradução é uma oportunidade de trocar dois campos. Com
`shipment.carrier_company_id` de um lado e `remessa.transportadora` do outro, o mapeamento vira
uma tabela mental que alguém vai errar às três da manhã, e o erro aparece como dado de auditoria
no volume errado.

O domínio já é português — *embarcador*, *remessa*, *excursão*, *custódia*, *NF-e* não têm
tradução natural. Alinhar app, API e banco elimina a camada de tradução inteira.

**Custo:** reescrever as 20 tabelas de `db/01_schema.sql`. É mecânico, acontece uma vez, e o
Alembic carrega. As funções de auditoria em PL/pgSQL (`mkt_celsius`, `detect_excursoes`,
`verify_chain`) são renomeadas junto.

### Convenções

- `snake_case`, tabelas no **singular** (`remessa`, não `remessas`)
- Chave primária: `id uuid PRIMARY KEY DEFAULT gen_random_uuid()`
  Exceção: `log_auditoria` usa `bigserial` (volume alto, ordem importa)
- Datas: sempre `timestamptz`. Nunca `timestamp` sem fuso.
- Sufixo `_em` para instantes (`criado_em`), `_id` para chaves estrangeiras
- Dinheiro/temperatura: `numeric`, nunca `float`. Séries longas usam `real[]` (precisão do
  sensor é 0,25 °C — `real` sobra e ocupa metade)
- `criado_em` / `atualizado_em` em tabelas mutáveis.
  **Tabelas de evidência não têm `atualizado_em`** — elas não mudam, e a ausência da coluna é
  documentação executável.

---

## 2. Visão geral

```
empresa ──┬── usuario ──── sessao_auth
          │       └─────── token_recuperacao
          ├── dispositivo
          ├── destinatario_alerta
          └── etiqueta ─── lote_etiqueta ─── arquivo (certificado)

empresa (embarcador) ──┐
empresa (transportadora)┴── remessa ──┬── documento
                                      ├── volume ── vinculo_etiqueta ── etiqueta
                                      ├── evento_custodia
                                      ├── ocorrencia ── acao_corretiva
                                      └── laudo ── arquivo

vinculo_etiqueta ── sessao_monitoramento ──┬── leitura_etiqueta ── serie_medicao
                                           └── excursao

perfil_termico → remessa, sessao_monitoramento
log_auditoria, chave_idempotencia, envio_alerta  (transversais)
```

**25 tabelas.** Auditei cada uma contra a regra "não criar tabela desnecessária" — a
justificativa de cada grupo está no início da seção.

---

## 3. Identidade e acesso *(novo — não existia)*

Este grupo não existe nem no app nem no SQL atual. É o que viabiliza o produto funcionar
entre duas empresas.

### `empresa`

| Campo | Tipo | Obrig. | Notas |
|---|---|---|---|
| id | uuid | ✔ | PK |
| razao_social | text | ✔ | |
| nome_fantasia | text | | |
| cnpj | text | ✔ | **UNIQUE**, 14 dígitos sem máscara |
| tipo | `tipo_empresa` | ✔ | embarcador · transportadora · ambos · plataforma |
| retencao_meses | integer | ✔ | default 60 (5 anos). Alavanca comercial. |
| ativa | boolean | ✔ | default true |
| criado_em / atualizado_em | timestamptz | ✔ | |

Índices: `UNIQUE(cnpj)`, `INDEX(ativa) WHERE ativa`

### `usuario`

| Campo | Tipo | Obrig. | Notas |
|---|---|---|---|
| id | uuid | ✔ | PK |
| empresa_id | uuid | ✔ | FK → empresa |
| nome | text | ✔ | |
| email | citext | ✔ | **UNIQUE global** |
| senha_hash | text | ✔ | Argon2id. Nunca a senha. |
| papel | `papel_usuario` | ✔ | operador · gestor · admin |
| ativo | boolean | ✔ | default true |
| ultimo_login_em | timestamptz | | |
| criado_em / atualizado_em | timestamptz | ✔ | |

Índices: `UNIQUE(email)`, `INDEX(empresa_id)`

> Um usuário pertence a **uma** empresa no MVP. Se um dia precisar de vários vínculos, entra
> uma tabela `vinculo_usuario_empresa` sem quebrar nada — por isso `empresa_id` fica aqui e
> não espalhado.

### `dispositivo`

Cada celular que ativa ou lê etiqueta é um **instrumento de medição**. Precisa aparecer no laudo.

| Campo | Tipo | Obrig. | Notas |
|---|---|---|---|
| id | uuid | ✔ | PK |
| empresa_id | uuid | ✔ | FK → empresa |
| registrado_por | uuid | | FK → usuario |
| install_id | text | ✔ | UUID gerado pelo app. **Não é IMEI** (LGPD). |
| modelo / versao_so / versao_app | text | | |
| pilha_nfc | text | | `nfc_v` \| `nfc_a` |
| ativo | boolean | ✔ | revogação de celular perdido |
| primeiro_acesso_em / ultimo_acesso_em | timestamptz | | |

Índices: `UNIQUE(empresa_id, install_id)`, `INDEX(ativo) WHERE ativo`

### `sessao_auth` — refresh tokens

| Campo | Tipo | Obrig. | Notas |
|---|---|---|---|
| id | uuid | ✔ | PK |
| usuario_id | uuid | ✔ | FK → usuario |
| dispositivo_id | uuid | | FK → dispositivo |
| token_hash | text | ✔ | **SHA-256 do refresh**. Dump vazado não vira sessão. |
| familia_id | uuid | ✔ | rotação: reuso invalida a família inteira |
| expira_em | timestamptz | ✔ | |
| revogada_em | timestamptz | | |
| ip / user_agent | inet / text | | |
| criado_em | timestamptz | ✔ | |

Índices: `UNIQUE(token_hash)`, `INDEX(usuario_id)`, `INDEX(familia_id)`,
`INDEX(expira_em) WHERE revogada_em IS NULL`

### `token_recuperacao`

`id`, `usuario_id` FK, `token_hash` (UNIQUE), `expira_em` (30 min), `usado_em`, `criado_em`.
Uso único. Índice em `usuario_id`.

---

## 4. Catálogo de etiquetas

### `lote_etiqueta`

**A tabela mais importante do ponto de vista regulatório.** Sem certificado de calibração
rastreável, o laudo não se sustenta numa auditoria de distribuição de medicamentos.

| Campo | Tipo | Obrig. | Notas |
|---|---|---|---|
| id | uuid | ✔ | PK |
| fornecedor | text | ✔ | |
| ordem_compra | text | | |
| modelo_hardware | text | ✔ | `MI8654TE` |
| part_number_ci | text | | `FM13DT160` — a confirmar com o fornecedor |
| fabricante_ci | text | | Shanghai Fudan Microelectronics |
| fabricado_em / recebido_em | date | | |
| quantidade | integer | ✔ | > 0 |
| certificado_ref | text | | número do certificado |
| certificado_arquivo_id | uuid | | FK → arquivo (o PDF) |
| pontos_calibracao_c | numeric[] | | ex.: `{0.0, 5.0, 25.0}` |
| incerteza_c | numeric | | ex.: 0.5 |
| calibracao_valida_ate | date | | |
| mapa_comandos | text | | `nfcinstruct` \| `dt160_9_3_5` — os dois mapas divergem |
| observacoes | text | | |
| criado_em / atualizado_em | timestamptz | ✔ | |

### `etiqueta`

| Campo | Tipo | Obrig. | Notas |
|---|---|---|---|
| id | uuid | ✔ | PK |
| lote_id | uuid | ✔ | FK → lote_etiqueta |
| empresa_id | uuid | | FK → empresa (dona) |
| serial | text | ✔ | **UNIQUE** — identificador comercial |
| qr_payload | text | ✔ | **UNIQUE** — conteúdo exato do QR impresso |
| nfc_uid | text | ✔ | **UNIQUE** — forma canônica, maiúsculas, sem separador |
| uhf_epc / uhf_tid | text | | previstos, fora do MVP |
| estado | `estado_etiqueta` | ✔ | estoque · vinculada · monitorando · lida · manutencao · aposentada |
| ciclos_ativacao | integer | ✔ | default 0 |
| ultima_tensao_v | numeric(4,2) | | |
| ultima_tensao_em | timestamptz | | |
| aposentada_em / aposentada_motivo | timestamptz / text | | |
| criado_em / atualizado_em | timestamptz | ✔ | |

Índices: `UNIQUE(serial)`, `UNIQUE(qr_payload)`, `UNIQUE(nfc_uid)`,
`INDEX(empresa_id)`, `INDEX(estado) WHERE estado <> 'aposentada'`

> **`nfc_uid` UNIQUE é a barreira de identidade do produto inteiro.** Android e iOS devolvem o
> UID com os bytes invertidos — por isso "forma canônica" é obrigação da API, não sugestão.

### `perfil_termico`

`id`, `codigo`, `versao`, `rotulo`, `min_c`, `max_c`, `alerta_min_c`, `alerta_max_c`,
`tolerancia_segundos`, `tor_max_segundos`, `mkt_limite_c`, `empresa_id` (NULL = global),
`ativo`, `criado_em`.

`UNIQUE(codigo, versao)`, `CHECK (min_c < max_c)`.

**Versionado**: mudar a faixa de um perfil não pode reescrever o critério de laudos antigos.
A remessa aponta para uma *versão* específica.

---

## 5. Remessa

### `remessa`

| Campo | Tipo | Obrig. | Notas |
|---|---|---|---|
| id | uuid | ✔ | PK |
| codigo | text | ✔ | `REM-2026-00184` |
| empresa_embarcador_id | uuid | ✔ | FK → empresa |
| empresa_transportadora_id | uuid | | FK → empresa |
| identidade_documento | text | | **UNIQUE** — chave da NF-e. Deduplica. |
| destinatario_nome | text | ✔ | |
| destinatario_cnpj | text | | |
| destinatario_endereco | jsonb | | |
| destinatario_contato | jsonb | | |
| perfil_termico_id | uuid | ✔ | FK → perfil_termico |
| intervalo_segundos | integer | ✔ | default 600 |
| previsao_coleta_em / previsao_entrega_em | timestamptz | | |
| descricao_carga | text | | |
| status | `status_remessa` | ✔ | |
| criada_por | uuid | | FK → usuario |
| criado_em / atualizado_em | timestamptz | ✔ | |

Índices: `UNIQUE(empresa_embarcador_id, codigo)`, `UNIQUE(identidade_documento)`,
`INDEX(empresa_transportadora_id, status)`, `INDEX(empresa_embarcador_id, criado_em DESC)`,
`INDEX(atualizado_em)` ← **usado pelo *pull* de sincronização**

**Autorização** — o predicado único da Fase 1:

```sql
empresa_embarcador_id = :empresa OR empresa_transportadora_id = :empresa
```

### `documento`

`id`, `remessa_id` FK, `tipo`, `numero`, `chave_acesso` (44 díg.), `cnpj_emitente`, `serie`,
`uf_emitente`, `competencia`, **`conteudo_bruto`**, `simbologia`, `validado`,
`observacao_validacao`, `digitado_manualmente`, `lido_em`, `criado_em`.

> `conteudo_bruto` guarda o que a câmera leu, *antes* de qualquer parsing. Se o interpretador
> melhorar, reprocessa-se. Guardar só os campos interpretados torna um erro de parsing
> permanente.

### `volume`

`id`, `remessa_id` FK, `sequencia`, `identidade` (`chave#V001`), `codigo_externo`,
`codigo_externo_bruto`, `monitorado`, `status`, `descricao`, `criado_em`, `atualizado_em`.

`UNIQUE(remessa_id, sequencia)`, `INDEX(remessa_id)`.

> A identidade do volume deriva do **documento**, não da etiqueta — é o que permite trocar
> uma etiqueta com defeito no meio do trajeto sem perder o histórico.

### `vinculo_etiqueta`

`id`, `volume_id` FK, `etiqueta_id` FK, `vinculada_em`, `vinculada_por` FK, `dispositivo_id` FK,
**`qr_escaneado`** bool, **`uid_conferiu`** bool, `liberada_em`, `motivo_liberacao`.

```sql
CREATE UNIQUE INDEX vinculo_ativo_unico
  ON vinculo_etiqueta (etiqueta_id) WHERE liberada_em IS NULL;
```

Uma etiqueta não pode estar em dois volumes ao mesmo tempo — garantido pelo banco, não pela
aplicação. `qr_escaneado` e `uid_conferiu` registram **como** a identidade foi provada.

---

## 6. Monitoramento — o núcleo da evidência

### `sessao_monitoramento`

Um ciclo START → leitura de uma etiqueta.

| Campo | Tipo | Obrig. | Notas |
|---|---|---|---|
| id | uuid | ✔ | PK |
| etiqueta_id / vinculo_id / remessa_id | uuid | ✔ | FKs |
| perfil_termico_id | uuid | ✔ | FK — a versão vigente na ativação |
| **epoch_inicio_etiqueta** | bigint | ✔ | o que foi gravado NA etiqueta |
| **inicio_dispositivo_em** | timestamptz | ✔ | o que o celular achava que era |
| **inicio_servidor_em** | timestamptz | ✔ | quando o servidor recebeu |
| **desvio_relogio_ms** | bigint | | desvio medido do celular |
| referencia_relogio | text | | `ntp` \| `servidor` \| `nenhuma` |
| **base_de_tempo** | `base_de_tempo` | ✔ | `instante_start` (Android) \| `primeira_janela` (iOS) |
| plataforma_ativacao | `plataforma` | | android \| ios |
| delay_minutos, intervalo_segundos, quantidade_planejada | integer | ✔ | config gravada |
| min_configurado_c / max_configurado_c | numeric(5,2) | ✔ | |
| modo_armazenamento | smallint | | 3 normal · 1 comprimido · 7 raw · 6 limit2 |
| ativacao_confirmada | boolean | ✔ | releitura após o START |
| ativacao_confirmada_em | timestamptz | | |
| ativada_por / dispositivo_id | uuid | | FKs |
| tensao_no_start_v | numeric(4,2) | | |
| verificado_em / registrando_na_verificacao | timestamptz / boolean | | "etiqueta ativa" |
| status | `status_sessao` | ✔ | |
| encerrada_em | timestamptz | | |
| criado_em | timestamptz | ✔ | |

`UNIQUE(etiqueta_id, epoch_inicio_etiqueta)` ← chave natural, impede sessão duplicada
`INDEX(remessa_id)`, `INDEX(status) WHERE status = 'ativa'`

> **`base_de_tempo` não é detalhe.** O Android grava o instante do START; o iOS grava
> `START + delay`. Com `delay = 0` ninguém percebe. Com delay, a série sai deslocada em
> silêncio. Sem esta coluna a reconstrução do horário é adivinhação.

### `leitura_etiqueta` — evidência, **imutável**

| Campo | Tipo | Obrig. | Notas |
|---|---|---|---|
| id | uuid | ✔ | PK |
| sessao_id / etiqueta_id | uuid | ✔ | FKs |
| **chave_idempotencia** | text | ✔ | **UNIQUE** — `dev:<install>:sess:<id>:read:<n>` |
| tipo_leitura | `tipo_leitura` | ✔ | ativacao · checkpoint · final |
| dispositivo_id / lida_por | uuid | | FKs |
| lida_em_dispositivo | timestamptz | ✔ | relógio do celular |
| recebida_em_servidor | timestamptz | ✔ | default now() |
| desvio_relogio_ms | bigint | | |
| **resposta_bruta** | jsonb | ✔ | array cru do SDK — nunca descartar |
| memoria_bruta_hex | text | | blocos lidos, quando capturados |
| versao_sdk / versao_decodificador | text | ✔ | |
| codigo_estado | text | | `response[0]` |
| quantidade_medida / intervalo_relatado_s / delay_relatado_min | integer | | |
| tensao_v / temperatura_instantanea_c | numeric | | |
| bateria_ok | boolean | | |
| **deriva_rtc_segundos** | bigint | | deriva do oscilador |
| horarios_corrigidos | boolean | ✔ | |
| **hash_payload / hash_anterior / hash_encadeado** | bytea | ✔ | cadeia SHA-256 |
| ip_origem | inet | | |

`UNIQUE(chave_idempotencia)`, `UNIQUE(sessao_id, hash_payload)`,
`INDEX(sessao_id, lida_em_dispositivo)`

**Trigger:** `BEFORE UPDATE OR DELETE → RAISE EXCEPTION`. Já existe como `deny_mutation()`.

### `serie_medicao` — derivada, **recalculável**

1:1 com a leitura, mas separada de propósito: **ciclo de vida diferente**. A leitura é evidência
imutável; a série é *derivada* dela. Se o decodificador melhorar, gera-se uma nova série a
partir da mesma `resposta_bruta`, sem tocar na evidência.

`leitura_id` (PK/FK), `sessao_id`, `primeiro_ponto_em`, `intervalo_segundos`, `quantidade`,
`temperaturas_c real[]`, `bits_campo smallint[]`, `min_c`, `max_c`, `media_c`, `mkt_c`,
`tor_abaixo_s`, `tor_acima_s`, `maior_excursao_s`, `versao_regra`, `calculado_em`.

`CHECK (quantidade = cardinality(temperaturas_c))`

> Guardar a série como array, e não uma linha por medição: 2,2 milhões de pontos em 5 anos
> viram ~3.600 linhas de array em vez de 2,2 milhões de linhas. A série é escrita uma vez, lida
> inteira, nunca sofre `UPDATE` de ponto individual. Uma função `expandir_serie()` devolve
> `(índice, instante, temperatura)` quando o laudo precisar.

### `excursao`

`id`, `sessao_id`, `leitura_id`, `tipo` (acima/abaixo), `gravidade` (alerta/acao/critica),
`inicio_em`, `fim_em`, `duracao_segundos`, `quantidade_pontos`, `pico_c`, `limite_c`,
`indice_primeira_amostra`, **`versao_regra`**, `avaliada_em`, `substituida_por` (FK self).

`INDEX(sessao_id) WHERE substituida_por IS NULL`

> Segmento contínuo, não ponto solto. `versao_regra` faz o veredito ser reproduzível: se a
> regra mudar em 2027, os laudos de 2026 continuam explicáveis.

---

## 7. Custódia, ocorrências e alertas

### `evento_custodia`

`id`, `remessa_id`, `de_parte`, `para_parte`, `de_empresa_id`, `para_empresa_id`,
**`ocorrido_em`**, **`registrado_em`**, `dispositivo_em`, **`origem`**
(`automatico_nfc` · `confirmado_tempo_real` · `informado_posteriormente`),
`volumes_confirmados`, `recebedor`, `evidencia_arquivo_id`, `observacao`, `registrado_por`,
`dispositivo_id`, `geo jsonb`.

`INDEX(remessa_id, ocorrido_em)`

> Os três tempos, sempre separados. "Entregue às 14h, lançado às 19h" é informação diferente de
> "entregue às 19h", e a auditoria precisa distinguir.

### `ocorrencia`

`id`, `remessa_id`, `volume_id`, `sessao_id`, `excursao_id`, **`chave_natural`** (UNIQUE),
`tipo`, `gravidade`, `status`, `titulo`, `detalhe`, **`ocorrido_em`**, **`detectado_em`**,
**`registrado_em`**, `pico_c`, `limite_c`, `duracao_segundos`, `versao_regra`,
`alerta_enviado_em`, `alerta_canal`, `fechada_em`, `aberta_por`.

`UNIQUE(chave_natural)` ← `sessao#epoch_inicio#tipo`. **Impede que reler a etiqueta duplique
alertas já enviados** — o erro que faz o cliente desligar a notificação.

Os valores térmicos ficam congelados aqui de propósito: o laudo tem que mostrar *o que se sabia
quando o alerta saiu*, mesmo que uma leitura posterior mude os números.

### `acao_corretiva`

`id`, `ocorrencia_id` FK, `tipo`, `descricao`, **`ocorrido_em`**, **`registrado_em`**, `origem`,
`executada_por_nome`, `empresa_id`, `evidencia_arquivo_id`, `registrada_por`, `criado_em`.

### `destinatario_alerta`

`id`, `empresa_id` FK, `nome`, `email`, `papel`, **`gravidade_minima`**, `ativo`, `criado_em`.

`UNIQUE(empresa_id, email)`

> `gravidade_minima` existe contra fadiga de alerta: o motorista não recebe desvio de 5 minutos;
> o responsável técnico recebe tudo.

### `envio_alerta` — fila de e-mail

`id`, `ocorrencia_id` FK, `destinatario_email`, `assunto`, `corpo`, `provedor`,
`provedor_mensagem_id`, `status` (`fila` · `enviado` · `falha` · `descartado`), `tentativas`,
`ultimo_erro`, `enfileirado_em`, `enviado_em`.

`INDEX(status) WHERE status = 'fila'`

Uma linha por destinatário — sem isso não dá para responder "o RT recebeu?" numa auditoria.

---

## 8. Laudo, arquivos e transversais

### `laudo`

`id`, `remessa_id`, `versao`, `gerado_em`, `gerado_por`, `versao_regra`, `versao_decodificador`,
`veredito`, `payload jsonb` (snapshot congelado), `payload_sha256`,
**`codigo_verificacao`** (UNIQUE), `arquivo_xlsx_id`, `arquivo_pdf_id`.

`UNIQUE(remessa_id, versao)`. Imutável.

> O `codigo_verificacao` sustenta o portal público de conferência. **Fora do MVP** (o cliente
> recebe o laudo por e-mail), mas gravado agora para não exigir migração depois.

### `arquivo`

`id`, `empresa_id`, `nome_original`, `caminho`, `tipo_mime`, `tamanho_bytes`, `sha256`,
`categoria` (`laudo` · `evidencia` · `certificado`), `enviado_por`, `criado_em`.

Nunca servido por URL pública direta — sempre por endpoint autenticado.

### `chave_idempotencia`

`id`, `chave` (UNIQUE), `endpoint`, `usuario_id`, `resposta_status`, `resposta_corpo jsonb`,
`criado_em`.

Torna **qualquer POST** seguro para repetir, não só a ingestão de leitura. O app já tem retry
agressivo no WorkManager; isso o torna inofensivo.

### `log_auditoria`

`id bigserial`, `em`, `usuario_id`, `empresa_id`, `dispositivo_id`, `acao`, `entidade`,
`entidade_id`, `antes jsonb`, `depois jsonb`, `ip`, `observacao`.

`INDEX(entidade, entidade_id, em DESC)`, `INDEX(em DESC)`. Append-only por trigger.

---

## 9. Tipos enumerados

```
tipo_empresa       embarcador · transportadora · ambos · plataforma
papel_usuario      operador · gestor · admin
estado_etiqueta    estoque · vinculada · monitorando · lida · manutencao · aposentada
status_remessa     preparacao · aguardando_aceite · aguardando_coleta · em_transporte
                   entregue_aguardando_leitura · concluida · cancelada
status_sessao      ativa · encerrada_normal · encerrada_anormal · abandonada
tipo_leitura       ativacao · checkpoint · final
base_de_tempo      instante_start · primeira_janela
plataforma         android · ios
parte_custodia     embarcador · transportadora · destinatario
origem_evento      automatico_nfc · confirmado_tempo_real · informado_posteriormente
tipo_excursao      acima · abaixo
gravidade          alerta · acao · critica
tipo_ocorrencia    termica · avaria · atraso · volume_faltante · divergencia_documental
                   etiqueta_inativa · bateria_baixa · outra
status_ocorrencia  aberta · alerta_enviado · em_tratamento · resolvida · sem_acao
status_envio       fila · enviado · falha · descartado
categoria_arquivo  laudo · evidencia · certificado
```

---

## 10. Retenção

`empresa.retencao_meses`, default 60. Rotina noturna que:

1. seleciona remessas concluídas há mais que a retenção da empresa;
2. **registra o expurgo em `log_auditoria` antes de apagar** — apagar sem rastro é pior que
   não apagar;
3. remove série, leituras, excursões e arquivos; preserva um resumo mínimo da remessa.

No seu volume, 5 anos ocupam pouco mais que nada. Cobrar diferente por retenção é decisão
**comercial**, não recuperação de custo.

---

## 11. O que mudou em relação a `db/01_schema.sql`

| | |
|---|---|
| **Renomeado** | as 20 tabelas, de inglês para português |
| **Novo** | `usuario` (era `app_user` sem senha), `sessao_auth`, `token_recuperacao`, `destinatario_alerta`, `envio_alerta`, `arquivo`, `chave_idempotencia` |
| **Ampliado** | `empresa` ganha `tipo` e `retencao_meses`; `dispositivo` ganha `ativo`; `sessao_monitoramento` ganha `verificado_em` e `registrando_na_verificacao`; `lote_etiqueta` ganha `mapa_comandos` |
| **Removido** | `membership` — no MVP o usuário pertence a uma empresa só, então virou `usuario.empresa_id` |
| **Mantido** | triggers de imutabilidade, cadeia de hash, `mkt_celsius`, `detect_excursoes`, `verify_chain`, `expandir_serie` |

---

## 12. Riscos deste modelo

1. **`usuario.empresa_id` único** limita quem trabalha para duas empresas. Consciente: sai mais
   barato adicionar `vinculo_usuario_empresa` depois do que carregar a complexidade agora.
2. **Sem RLS no Postgres.** A autorização é de aplicação. Um bug no `deps.py` vaza dado entre
   empresas. Mitigação: um único ponto de checagem + teste dedicado na Fase 7. RLS entra quando
   houver mais de um cliente pagante.
3. **`identidade_documento` UNIQUE global** impede que duas empresas cadastrem a mesma NF-e.
   Correto no domínio (uma NF-e tem um emitente), mas vai gerar suporte no dia em que alguém
   digitar a chave errada.
