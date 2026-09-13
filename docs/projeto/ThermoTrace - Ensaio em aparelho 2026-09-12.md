---
tipo: evidencia
projeto: ThermoTrace
contexto: pessoal-independente
status: em-andamento
atualizado: 2026-09-12
---

# Ensaio em aparelho — 2026-09-12

Primeiro ensaio do Android 0.8.0 em aparelho físico. Fecha a lacuna registrada em
[[ThermoTrace - Decisões e pendências]] ("funcionaram em quais etiquetas/celulares?").
Evidência de ensaio, não certificação: amostra de uma etiqueta e um aparelho.

## Ambiente

1. Aparelho: Samsung Galaxy A57 (SM-A576B), serial ADB RXGL40BEDMZ. NFC presente e funcional.
2. Instalação: cabo USB, `installDebug` pelo Android Studio Quail 3 | 2026.1.3. Pacote `com.thermotrace.app.debug`, versionName 0.8.0-debug, versionCode 12.
3. Etiqueta: "etiqueta 1", UID `1DAE2B0B000870`, interface Type-A ISO 14443. Perfil aplicado: Resfriado, 7 a 14 °C, intervalo 10 min.
4. Remessa de ensaio: REM-2026-00184, ativada 2026-09-12 11:01, capacidade programada 648 registros.
5. Servidor: nenhum. Sem endereço HTTPS publicado, a tela Conta e cargas não foi exercitada; o app manteve 5 registros na fila local ("ainda não enviados ao servidor").

## Builds usados no dia

1. Build 00:29 — versão anterior às correções. É a que produziu as falhas abaixo.
2. Build 11:23 — correções de nota manual e de controle do NFC; 97 testes JVM passaram, sem falhas nem ignorados (eram 90 em 12/09 de manhã).
3. Build 11:38 — o mesmo conjunto mais a guarda de pré-condição da coleta automática. Instalado e exercitado no aparelho.
4. Build 12:12 — acrescenta o status da última coleta no cartão do volume. Instalado no aparelho às 12:17.
5. Build 13:22 — acrescenta a reconciliação entre o cabeçalho da etiqueta e a série decodificada, com aviso na tela de leitura só quando os dois discordam. Instalado no aparelho. Ver [[ThermoTrace - TT-005 reconciliação cabeçalho x série]].

Testes JVM: 97 passaram no build 11:23, nenhuma falha e nenhum ignorado. Não foram reexecutados sobre os builds 11:38 e 12:12.

## Observado no build 00:29

1. Ativação funcionou: etiqueta ligada, sessão criada, estado "Registrando", trilho com passo 1 concluído.
2. Leitura da nota fiscal por câmera falhou. Sem alternativa de digitação.
3. Da segunda coleta em diante, o Android abria a etiqueta fora do app — tela "Nova marca digitalizada" do sistema, exibindo o URI `t/demo.nss.fmsh` gravado na etiqueta.
4. Na tela de Checkpoint, o painel NFC exibia "Pronto" em vez de aguardar a etiqueta: nenhuma operação ficava armada ao abrir a tela, e a coleta só saía tocando em "Baixar histórico".
5. "Verificar agora" funcionou na tela de Checkpoint e devolveu "Confirmado agora, na etiqueta", com 1 de 648 registros. Ou seja: a leitura NFC do aparelho está boa; o que faltava era o app reivindicar a etiqueta e armar a operação.

## Causa e correção

1. Causa da tela do sistema: nem a tela da remessa nem a activity da câmera mantinham reader mode ativo. Fora do reader mode, o despacho de tag do Android entrega a etiqueta ao visualizador do sistema, porque o URI gravado (`demo.nss.fmsh`) não casa com o intent-filter do app (`app.thermotrace.com.br/t/`).
2. Correções aplicadas: reader mode passivo na tela da remessa e na `CapturaDocumentoActivity`; recarga da remessa a cada retomada da tela, para a coleta confirmada aparecer ao voltar; checkpoint e leitura final passam a entrar armados; digitação manual da nota, marcada no documento como entrada manual e sem validação fiscal.
3. Guarda adicionada em 11:38: a coleta só se arma sozinha com sessão ativa, etiqueta vinculada e monitoramento em aberto. Sem ela, abrir a tela de um volume já encerrado exibia um erro que o operador não provocou.

## Resultado do reteste — build 11:38, 12:03 a 12:08

1. Checkpoint entra armado. O painel passou a aguardar a etiqueta em vez de exibir "Pronto", e a coleta saiu sem tocar em botão. Corrigido.
2. A tela "Nova marca digitalizada" do sistema não apareceu mais em nenhuma das coletas seguintes. Corrigido.
3. Coleta concluída com 6 registros entre 12/09 11:13:10 e 12/09 12:03:10, fuso America/Recife. Seis pontos num intervalo de 10 min fecham os 50 min decorridos: a base de tempo está coerente.
4. "Verificar agora" segue funcionando e devolve "Registrando · confirmado agora, na etiqueta".
5. Desvio registrado entre a leitura e o último ponto: 232 s. A confirmar se é do relógio da etiqueta ou do arredondamento do último slot.
6. Após concluir a coleta, o painel volta a exibir "Pronto" — a operação é consumida. Comportamento esperado, mas a confirmar se convém rearmar para uma segunda coleta seguida na mesma tela.

## Achado aberto — integridade do gráfico (TT-005)

A coleta devolveu mínima −29,8 °C e máxima 30,8 °C, com MKT 18,64 °C, 1 excursão e 30 min
fora da faixa de 7 a 14 °C. A etiqueta esteve sobre a mesa durante todo o período, em
Recife: os valores próximos de 30 °C são plausíveis, o de −29,8 °C não é. O gráfico
alterna entre os dois extremos ponto a ponto, padrão típico de erro de sinal, escala ou
ordem de bytes, ou de slot não gravado devolvido como sentinela.

Onde isto NÃO está: a decodificação do ThermoTrace não faz aritmética sobre a
temperatura. Em `nfc/SessionDecoder.kt` a amostra chega como texto já convertido e o app
apenas valida que é número finito (`parts[0].toDoubleOrNull()`). Ou seja, o valor vem do
módulo do fabricante (`nfcinstruct`), não de cálculo nosso. Há três hipóteses e nenhuma
está descartada: erro de decodificação no SDK do fabricante; slots ainda não gravados
devolvidos com valor sentinela; ou a etiqueta realmente registrou isso, o que contraria a
condição física do ensaio.

Evidência disponível para investigar: a resposta bruta de cada leitura está persistida em
`LeituraEntity.respostaBruta`, que nunca é sobrescrita, junto de `versaoSdk` e
`versaoDecodificador`. Próximo passo proposto: comparar a mesma etiqueta no app do
fabricante e conferir o termômetro ao vivo da tela Diagnóstico contra um termômetro
aferido, antes de mexer em qualquer fórmula. Não alterar decodificação sem essa comparação.

Isto é o TT-005 de [[ThermoTrace - Plano de ação e backlog]], e as Decisões de 11/09
registram integridade do gráfico como bloqueador de liberação. Continua bloqueador.

## Pedido do usuário — status da última coleta

Pedido em 12/09: o cartão do volume precisa dizer, sem entrar na tela de leitura, quando
foi a última coleta e qual a temperatura naquele momento. Implementado no build 12:12, em
`CartaoVolume` de `RemessaScreen.kt`: "Última coleta" (tipo e data/hora), "Temperatura na
coleta" (a medida instantânea daquela aproximação, não a série) e "Coletas feitas". Fonte:
`LeituraEntity.lidaEmMillis`, `tipo` e `temperaturaInstantaneaC`, já carregados pela
`RemessaViewModel`. Pendente de validação no aparelho.

## Pendente de reteste no aparelho

1. Instalar o build 12:12 e conferir o status da última coleta no cartão do volume.
2. Leitura da nota por câmera e, como alternativa, a digitação manual — número simples e chave de 44 dígitos.
3. Reexecutar `:app:testDebugUnitTest` sobre o build corrente.
4. Validação visual, ciclo de vida e ensaio do Keystore no aparelho, pendentes desde [[ThermoTrace - Entrega Android 0.8.0]].
5. Leitura final e STOP físico, ainda não exercitados neste ensaio.

## Limites deste ensaio

Uma etiqueta, um aparelho, sem termômetro aferido ao lado e sem servidor. Não substitui
TT-011 (matriz do app do fabricante), TT-004/TT-006 (leitura intermitente e as 30 operações
por combinação) nem TT-005 (integridade do gráfico contra exportação do fornecedor), todos
em [[ThermoTrace - Plano de ação e backlog]]. Integridade do gráfico segue bloqueador de liberação.

## Achado de usabilidade — coletas baixadas que nunca foram registradas (13:30)

Evidência: tela da remessa REM-2026-00184 às 13:30, depois de várias coletas no dia.

1. O selo do volume diz **SEM LEITURA**.
2. No trilho, a etapa 2 (Trajeto) está vazia — nenhum checkpoint contado.
3. A linha do tempo tem um único evento: a custódia da ativação, 11:13.
4. O status "Última coleta", entregue no build 12:12, **não aparece** — porque não há leitura nenhuma para mostrar.
5. Ao mesmo tempo, a etiqueta declara **14 de 648 registros**: ela está gravando normalmente.

Conclusão: os downloads aconteceram, mas **nenhuma coleta foi persistida**. Baixar o
histórico não cria `LeituraEntity`; isso só ocorre ao tocar em "Registrar checkpoint", que
fica no fim de uma tela longa, depois do resultado e do gráfico. Quem baixa, olha o
resultado e volta perde a coleta — sem erro, sem aviso, sem sinal.

Isto confirma em campo o risco levantado em [[ThermoTrace - Proposta de gráfico e coleta]]
(item 1 da tela de coleta) e promove o TT-043 de melhoria de conforto a **defeito de
perda de evidência**. É a causa de a linha do tempo estar vazia, mais do que a ausência das
coletas no eixo — que também era verdade e foi corrigida no build 13:38.

Consequência para o TT-005: as coletas do dia, inclusive a de −29,8 °C, provavelmente
**não têm evidência bruta guardada**, porque `respostaBruta` só é gravada junto da leitura
persistida. A investigação da decodificação precisa de uma coleta nova, registrada até o
fim, para ter payload a analisar. A confirmar abrindo o laudo e vendo se há medições.

## Linha do tempo unificada (build 13:38)

A linha do tempo mostrava apenas eventos de custódia; as coletas ficavam dentro do cartão
do volume. Agora custódia e coletas entram no mesmo eixo cronológico, cada coleta com tipo,
volume, quantidade de registros baixados e temperatura instantânea daquela aproximação.
A cor separa os tipos sem rótulo: azul para custódia, verde para coleta conforme, vermelho
para coleta com excursão. Quando não há nada, a seção explica o que vai aparecer ali em vez
de desaparecer da tela.

## TT-043 implementado (build 13:43) — fim da coleta perdida em silêncio

Correção do defeito registrado às 13:30. Três mudanças em `LeituraScreen`:

1. **Barra de ação fixa no rodapé.** O registrar saiu do fim da coluna e passou a viver no
   `bottomBar` do Scaffold, sempre visível enquanto houver coleta baixada, acompanhado da
   frase "Coleta baixada e ainda NÃO registrada" em cor de erro. A ordem do conteúdo não
   mudou — ler, conferir, assinar tem ordem própria — mudou só a posição da ação.
2. **Guarda de saída.** Sair da tela com coleta pendente, pelo botão de voltar do app ou
   pelo gesto do sistema, abre confirmação explícita: "Registrar e sair" ou "Descartar",
   com o texto dizendo que descartar tira a coleta do laudo e da linha do tempo e que a
   etiqueta continua registrando. Antes, voltar descartava sem dizer nada.
3. **Fim do "Pronto" ambíguo.** A mesma palavra servia para "nada armado" e para "coleta
   concluída". Agora: "Pronto para ler", "Aproxime o celular da etiqueta", "Lendo… mantenha
   o celular parado" e "Histórico baixado — confira e registre abaixo".

O botão duplicado no fim da coluna foi removido, para não haver duas ações com o mesmo
efeito em lugares diferentes.

Decisão que isto NÃO toma: o TT-044 segue aberto. O registro continua sendo ato explícito do
operador, coerente com a cadeia de custódia e com o comentário do `LeituraViewModel` sobre
ver o veredito antes de assinar. A correção elimina a perda silenciosa sem remover a
conferência.

Compilado às 13:43. Instalado no emulador; instalação no A57 pendente de cabo.

## Defeito encontrado na revisão — a leitura final nunca enviava o STOP (14:32)

Achado de leitura de código, antes do ensaio, ao preparar o TT-053b. Não precisou de
hardware para aparecer.

Existem duas operações diferentes no `NfcOperator`:

1. `Operation.Download(purpose)` — baixa o histórico e nada mais. `loggerParado` fica nulo.
2. `Operation.Encerrar(senha)` — baixa o histórico e, **só depois de garanti-lo**, envia o
   `stopLogging`. Devolve `loggerParado` com o resultado real do STOP.

O bipe rápido da tela inicial sempre usou `Encerrar`. A tela formal de **Leitura final**
usava `Download("destino")`. Consequência: a remessa era encerrada no banco e **a etiqueta
continuava gravando para sempre**, sem nunca ser liberada para o próximo ciclo — e sem
nenhum aviso, porque `loggerParado` nulo nunca era lido pela `LeituraViewModel`.

Dois caminhos para o mesmo ato, com comportamento diferente. É exatamente o risco que o
próprio código adverte em outro lugar, ao justificar o uso do `FluxoRapido`: "dois caminhos
gravando evidência de auditoria não podem divergir".

### Correção (build 14:32)

1. A leitura final passa a usar `Operation.Encerrar()`. O checkpoint continua com
   `Download("checkpoint")` e segue sem parar nada, como deve.
2. `etiquetaLiberada` entrou no estado da tela com três valores distinguíveis: parada e
   livre, **não parada** (histórico salvo, chip ainda gravando), e STOP não confirmado.
3. Seção "Etiqueta" na leitura final mostra qual dos três é o caso, em cor coerente, e
   oferece **"Parar registro"** quando o STOP não se confirmou — STOP falho deixa de ser beco
   sem saída.
4. `pararRegistro()` na ViewModel repete só o STOP, sem baixar de novo.
5. A instrução do painel foi corrigida: dizia que a etiqueta é parada "depois, em uma
   aproximação separada", o que não descreve o `Encerrar`.

Atende ao aceite do TT-012 na tela de leitura: STOP falho não aparece confirmado.

Compilado às 14:32 e instalado no emulador; instalação no A57 pendente de cabo. **Ainda não
exercitado contra etiqueta real** — é o primeiro item do ensaio.
