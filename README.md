# ThermoTrace — monitoramento térmico por NFC

## Estado em 13/09/2026

Projeto pessoal independente. Android **0.8.3-debug** acrescenta histórico e reconciliação de coletas; checkpoint/final ainda exigem reteste físico. API **0.4** recebe evidências e mantém acesso por empresa. Portal **0.1**, em `/portal`, inicia as áreas de equipe ThermoTrace, cliente (dono da carga) e contratante (transportadora), com login real e consulta de cargas/documentos/coletas. Consulte [a entrega do portal](backend/ENTREGA-PORTAL-0.1.md), [a entrega da API](backend/ENTREGA-0.4.md) e [as notas do projeto](docs/projeto/README.md).

A conta online ainda não está conectada às leituras e ao banco local do protótipo. Isolamento local, fila integrada, compartilhamento autorizado entre empresas e gráficos/laudos centrais permanecem pendentes. O serviço `thermotrace-api` aparece como publicado no Render no print do usuário; domínio comprado `thermotrace.com.br` ainda precisa de vínculo DNS/HTTPS. Isso não comprova a publicação deste commit nem homologação física. As seções antigas abaixo descrevem também fluxos do protótipo e não equivalem à validação do produto completo.

Cada entrega passa pelo GitHub com explicação do que mudou e por quê: [rotina de contribuição](CONTRIBUTING.md). Portal e API usam o mesmo serviço, sem recurso pago adicional.

Análise do material existente e base técnica corrigida: leitura da etiqueta por Android e
iPhone, banco de auditoria e laudo em Excel.

## O que tem aqui

```
docs/
  01-analise-critica-v0.1.md        o que foi feito, o que está quebrado, o que é risco
  02-arquitetura-dados-auditoria.md como o banco foi desenhado e por quê
  03-perguntas-fornecedor.md        o que ainda perguntar (e o que já foi respondido)
  04-portabilidade-ios-android.md   tabela canônica de comandos + as divergências entre SDKs

db/
  01_schema.sql                     estrutura do banco
  02_funcoes_auditoria.sql          MKT, TOR, excursões, ingestão idempotente, cadeia de hash
  03_seed_e_exemplo.sql             perfis térmicos + exemplo completo que roda
  04_export_excel.sql               visões achatadas para planilha

tools/
  exportar_excel.py                 gera o laudo .xlsx a partir do banco

app/src/main/java/com/thermotrace/app/
    domain/                         perfis térmicos, MKT, TOR, excursões
    nfc/                            NfcOperator + SessionDecoder
    data/db/                        Room: remessa, volume, etiqueta, sessão, leitura, custódia, outbox
    data/repo/Repositorio.kt        ponto único de escrita
    data/export/                    laudo .xlsx sem dependência externa
    ui/screens/                     Home, Nova remessa, Remessa, Leitura, Laudo, Etiquetas,
                                  Ocorrências, Ajustes, Diagnóstico
  data/prefs/Preferencias.kt      ajustes do aparelho (perfil, intervalo, folga, piso de tensão)
  nfcinstruct/                      módulo do fabricante (FMSH), intocado

ios/
  ThermoTraceNFC.swift              Core NFC público, mesmo ciclo do Android
```

## Rodando o app

```bash
./gradlew :app:assembleDebug
```

Saída em `app/build/outputs/apk/debug/app-debug.apk`.
Com o celular por USB e Depuração USB ativa, `./gradlew :app:installDebug` instala direto.
Emulador não serve — NFC exige a etiqueta física.

### O fluxo de campo: três bipes

```
origem       bipar → Ativar          um toque; a etiqueta é cadastrada, ligada e vira remessa
             bipar a nota fiscal     câmera lê a DANFE e amarra à remessa
recebimento  bipar → Finalizar       um toque; baixa o histórico, DESLIGA a etiqueta, dá o veredito
```

Nenhum formulário obrigatório. O perfil térmico, o intervalo e a duração vêm dos **Ajustes** e
valem para toda ativação; quem precisa de exceção abre **Alterar** na própria folha da etiqueta.
Finalizar exige a nota anexada — é o que impede um laudo sem dono.

O encerramento fecha o ciclo START/STOP do fabricante numa aproximação só: baixa primeiro,
desliga depois. Se o download falhar, o STOP não chega a ser enviado; se o STOP falhar, o
histórico já está salvo e a tela manda usar **Parar registro**.

Caminho longo, para remessa com vários volumes: **Nova remessa** → **Ativar** / **Checkpoint** /
**Leitura final** por volume.

### Alerta, estado da etiqueta e identidade fiscal (v0.3)

- **Alerta de excursão**: as excursões detectadas numa leitura viram ocorrências (dedup por
  sessão + segmento) e entram na fila de alerta. O e-mail sai do **servidor**, não do celular —
  credencial de SMTP num APK entrega a caixa da empresa. Sem backend, o alerta pode ser
  disparado pelo app de e-mail do operador. Na mesma tela se registra a **ação corretiva**, com
  a data em que ela ocorreu separada da data em que foi lançada.
- **Etiqueta ativa**: o cartão de estado distingue *confirmado agora* (bit de status lido por
  aproximação) de *presumido* (com "última verificação há X"). Mostra ocupação da memória,
  autonomia restante e bateria. O botão **Verificar agora** faz uma leitura curta, só do status.
- **Identidade pelo documento fiscal**: chave de 44 dígitos de NF-e/CT-e/MDF-e com validação de
  DV módulo 11 offline, extração da chave do QR da SEFAZ, AWB com módulo 7. A chave vira a chave
  natural da remessa (deduplica) e o volume deriva dela (`chave#V001`), o que permite trocar a
  etiqueta sem perder o histórico.

> A etiqueta não tem rádio: a excursão é **detectada** na leitura, não no instante em que ocorre.
> O alerta diz as duas horas, e o texto do e-mail é explícito sobre o atraso.

### Operação em campo e diagnóstico (v0.4)

- **O celular vibra e apita.** Encostar o aparelho numa caixa fechada ou dentro de câmara fria
  esconde a tela: o operador só descobria o resultado ao afastar o telefone — e afastar no meio
  da gravação é exatamente o que corrompe a ativação. Três sinais distintos ao toque: um pulso
  curto ao detectar a etiqueta, dois ao concluir, um longo na falha. Passa por
  `rememberNfcOperator`, então vale para toda tela, inclusive as que ainda não existem.
- **Diagnóstico de etiqueta** (menu ⋮ → Diagnóstico). Três operações, **todas somente leitura**,
  seguras de rodar numa etiqueta que está monitorando carga real:
  - *Diagnosticar*: interface física, UID, versão do SDK do fabricante, se o chip está acordado
    (`checkWakeUp`), se está registrando e o que mede agora.
  - *Ler configuração gravada*: o cabeçalho da sessão que está **dentro** do chip — faixa,
    intervalo, delay, programados, medidos. É onde a divergência entre o app e a etiqueta aparece.
  - *Termômetro ao vivo*: `getBasicData` em laço enquanto a etiqueta ficar no campo, com gráfico.
    É a conferência contra um termômetro aferido que o cliente pede antes de aceitar o laudo — e
    não exige ligar a etiqueta.
- **Veredito de iPhone, na bancada.** O diagnóstico diz em uma frase se a etiqueta expõe ISO 15693.
  Se não expuser, a versão iOS não é questão de esforço de programação: o Core NFC público só
  envia comando proprietário por esse padrão. Vira decisão de compra, e aparece antes da venda.
- **Ajustes** (menu ⋮ → Ajustes). Perfil térmico padrão, intervalo de medição, duração prevista,
  folga de gravação, piso de tensão para ativar, vibração, bipe, LED e URL do servidor — que antes
  morava escondida dentro da tela de Ocorrências. Vale da próxima ativação em diante: sessão já
  criada guarda o que foi gravado nela, porque ajuste que reescreve o passado acaba com a auditoria.
- **Busca e recortes** na lista de remessas (código, destinatário, transportadora, chave do
  documento) e na lista de etiquetas (código impresso ou UID). Os chips trazem o contador junto,
  para o operador ver que não há nada aguardando leitura sem precisar trocar de recorte.
- **A versão do SDK do fabricante passou a ser lida do próprio SDK.** Estava escrita à mão como
  `"fmsh-nfcinstruct-1.0.0"` dentro da evidência de auditoria — na primeira troca de biblioteca a
  coluna passaria a mentir sobre qual código produziu a leitura.

## Começando pelo banco

```bash
psql "$DSN" -f db/01_schema.sql -f db/02_funcoes_auditoria.sql -f db/03_seed_e_exemplo.sql -f db/04_export_excel.sql
```

O `03` cria uma remessa de exemplo com uma excursão de 50 minutos e imprime as consultas de
auditoria. Serve como teste de fumaça do schema inteiro.

Depois:

```bash
pip install "psycopg[binary]" xlsxwriter
python tools/exportar_excel.py --dsn "$DSN" --remessa REM-2026-00184
```

## As cinco conclusões que mais mudam o projeto

1. **O chip é FM13DT160 da Shanghai Fudan Microelectronics.** Estava no código o tempo todo
   (`com.fmsh`, `nfcinstruct`, "DT160" no README do SDK). `MI8654TE` é a etiqueta montada,
   não o silício.

2. **O iPhone está confirmado.** O SDK iOS do fornecedor usa só Core NFC público
   (`customCommandWithRequestFlag:`) e entitlement `TAG`. Pode vender Android + iPhone.

3. **Padronize tudo em ISO 15693 (NfcV).** É o único caminho pelo qual o iOS aceita comandos
   proprietários. No Android, force `FLAG_READER_NFC_V` — senão o SDK cai no caminho 14443 e
   as duas plataformas param de executar a mesma sequência.

4. **A base de tempo diverge entre as plataformas.** Android grava o epoch do START; iOS grava
   `START + delay`. Com `delay = 0` ninguém percebe. Resolvido pela coluna `time_base`.

5. **Certificado de calibração rastreável é o maior risco aberto.** Nenhum documento do projeto
   menciona. Sem ele, o laudo não se sustenta numa auditoria de distribuição de medicamentos —
   e é o tipo de coisa que só aparece depois da venda.

## Estado

Análise, modelagem e camada NFC entregues e revisadas contra o código do fornecedor.
**Nada foi executado contra hardware nem contra um Postgres real** — o SQL não foi rodado e o
código não foi compilado neste ambiente. O `03_seed_e_exemplo.sql` existe justamente para ser o
primeiro teste quando houver banco disponível.
