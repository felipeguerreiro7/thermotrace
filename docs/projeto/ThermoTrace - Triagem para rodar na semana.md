---
tipo: proposta
projeto: ThermoTrace
contexto: pessoal-independente
status: rascunho-aguardando-decisao
atualizado: 2026-09-12
---

# Triagem — o que falta para rodar o app na semana

Pedido do usuário em 12/09/2026: revisão de desenvolvedor sênior sobre o que precisa
melhorar para rodar o app ainda nesta semana. Triagem contra esse objetivo, não lista de
desejos. Evidência de campo em [[ThermoTrace - Ensaio em aparelho 2026-09-12]].

## Antes de tudo: "rodar" significa duas coisas muito diferentes

**Ensaio interno controlado, com carga e etiqueta do próprio usuário: viável esta semana.**
É o que exercita o que nunca foi exercitado, sobretudo leitura final e STOP físico.

**Operação com cliente real de saúde: não nesta semana, e não por falta de código.** Três
razões, todas já registradas no próprio projeto:

1. **Calibração rastreável.** O `README` do repositório diz, com estas palavras, que é o
   maior risco aberto e que nenhum documento do projeto menciona certificado. Sem isso o
   laudo não se sustenta em auditoria de distribuição de medicamentos. É compra e contrato,
   não programação.
2. **Banco local não separado por cliente.** A própria entrega 0.8.0 declara que a aplicação
   não está liberada para aparelhos compartilhados entre clientes nem para operação
   comercial. TT-020 e a fila do TT-031 continuam abertos.
3. **Sem servidor, a evidência mora num só celular** — e `allowBackup=false` é deliberado, o
   que significa que não há cópia automática. Perder ou limpar o aparelho apaga a cadeia de
   custódia. A exportação em Excel é a única saída, e é manual.

Isto não é pessimismo: é o que separa um ensaio honesto de uma promessa que a documentação
do projeto já sabe que não pode cumprir.

## Blocos, por ordem de risco

### A — impedem liberação, não se resolvem com código nesta semana

1. Calibração rastreável (acima).
2. Isolamento por cliente no banco local (TT-020, TT-031).
3. Servidor de homologação (TT-064 a TT-066, em [[ThermoTrace - Hospedagem e custos]]).
4. TT-005: a mínima de −29,8 °C segue sem explicação. Agora é **detectada**; não é explicada.
5. Ensaio de hardware: uma etiqueta, um aparelho, nenhuma das 30 operações do TT-006, e
   leitura final e STOP nunca exercitados.

### B — código, feitos hoje

1. **Coleta perdida em silêncio** — era o pior defeito e tinha evidência de campo: downloads
   feitos e nunca persistidos, com a etiqueta gravando e o app dizendo SEM LEITURA. Barra de
   ação fixa, guarda de saída e, por decisão D08, registro automático do checkpoint.
2. **Reconciliação cabeçalho x série** — `Reconciliacao` em `SessionDecoder`, com aviso na
   tela só quando os dois discordam. Ver [[ThermoTrace - TT-005 reconciliação cabeçalho x série]].
3. **Linha do tempo com coletas**, não só custódia.
4. **Status da última coleta** no cartão do volume.
5. **Evidência bruta na exportação.** A aba Auditoria passou a carregar a mínima e a máxima
   declaradas pela etiqueta, as contagens de pontos fora da faixa, uma coluna "Reconciliado"
   e a **resposta bruta inteira**. Antes o Excel levava só os hashes: dava para provar que o
   bruto não mudou, mas não para redecodificar nada. Agora a leitura pode ser reprocessada
   fora do aparelho, meses depois, sem depender desta versão do decodificador — e o TT-005
   fica investigável a partir do arquivo exportado.
6. **Remoção de um cast morto** (`context as ComponentActivity`) na tela de leitura: código
   sem uso que podia lançar `ClassCastException` sem entregar nada.

### C — código, próximos e ainda abertos

1. **Leitura final e STOP.** É o passo obrigatório e o único nunca exercitado. O fluxo separa
   fechamento lógico de STOP físico de propósito, e a tela precisa deixar impossível achar
   que a etiqueta parou quando o STOP falhou. Prioridade máxima do bloco C, porque é o que o
   ensaio da semana vai encostar primeiro.
2. **Aviso de evidência não exportada.** Com um só celular e sem backup, o app deveria dizer
   quando existe coleta que nunca saiu do aparelho. Hoje ele não diz.
3. Gráfico (TT-042/TT-046) e histórico de coletas (TT-045), já especificados em
   [[ThermoTrace - Proposta de gráfico e coleta]] e
   [[ThermoTrace - Transparência da coleta e localização]]. Depois do TT-005, por decisão de
   ordem já tomada.
4. Localização (TT-047), com o Play Services decidido em D07.
5. Suíte de testes: 97 passaram no build 11:23; desde então entraram cinco casos de
   `ReconciliacaoTest` e várias mudanças que **não foram testadas**. Rodar
   `:app:testDebugUnitTest` é o menor esforço com maior retorno em confiança hoje.

## Roteiro do ensaio interno da semana

1. Rodar a suíte e fechar o que quebrar.
2. Diagnóstico na etiqueta 1: anotar mínima e máxima declaradas e as contagens. Separa erro
   de decodificação de problema anterior a ela.
3. Ciclo completo num volume: ativação, dois ou três checkpoints, **leitura final e STOP**.
4. Exportar o Excel e conferir a aba Auditoria: bruto presente, coluna Reconciliado, hashes.
5. Anotar tudo em [[ThermoTrace - Ensaio em aparelho 2026-09-12]], inclusive o que falhar.
6. Só então decidir se o gráfico entra antes ou depois da localização.

## Limite desta triagem

Um aparelho, uma etiqueta, sem termômetro aferido e sem servidor. Nada aqui substitui
TT-011, TT-004, TT-006 ou o aval do responsável de qualidade, todos em
[[ThermoTrace - Plano de ação e backlog]].

## Sessão de 14/09/2026 — o que saiu

Trabalho feito nos itens que o Codex não havia pego, para não colidir com a frente dele
(portal e backend). Commits no repositório, com o porquê em cada mensagem.

1. **`291c329` — isolamento por empresa e operador.** Estava pronto no disco desde 13/09
   19:16 e **não commitado**: 18 arquivos sem rede de proteção. Revisado e registrado.
   Fecha TT-020/TT-031 do lado Android.
2. **`82354f1` — gráfico (TT-042 e TT-046).** `Componentes.kt` estava intocado desde o
   commit inicial. Escala ancorada na faixa, eixo Y rotulado, excursão sombreada, marca de
   tempo intermediária e leitura por toque. Detalhe deliberado: a legenda mostra sempre o
   extremo real da série, para o número suspeito do TT-005 continuar visível.
3. **`188b620` — localização da coleta (TT-047).** Detalhes em
   [[ThermoTrace - Transparência da coleta e localização]].
4. **`09fb961` — correção de fim de linha.** Erro meu; a regra ficou registrada.
5. **`6ee0cab` — aviso de evidência não exportada (TT-054b).** Cada leitura registra quando
   saiu do aparelho num laudo; a tela inicial avisa quantas existem somente no celular.
   Separado de `sincronizada` de propósito: aquilo é fila para servidor, isto é cópia em
   arquivo — e enquanto não há servidor, a exportação é a única cópia possível.

### O que segue aberto, e por quê

1. **TT-005** continua o bloqueador declarado. Precisa da leitura do Diagnóstico na etiqueta
   e da comparação com o app do fabricante — nada disso se resolve em código.
2. **Suíte de testes sem executar** desde o build de 12/09 11:23. Entraram desde então o
   `ReconciliacaoTest`, o `EscopoLocalTest`, o `LeituraViewModelTest` e o
   `ExportacaoAuditoriaTest`, além de tudo desta sessão. `:app:testDebugUnitTest` é o menor
   esforço com maior retorno em confiança hoje.
3. **Nada desta sessão foi exercitado em aparelho.** Gráfico novo, fix de GPS e aviso de
   exportação foram compilados, não usados.
4. **WEB-10** (vínculo dono da carga ↔ transportadora) deixado para o Codex, que declarou
   como próximo passo dele. Dois agentes no mesmo arquivo foi o que quase deu errado em
   12/09.

### Atualização no fim da sessão — bloco C, item 1, fechado em código

O item que esta triagem declarou prioridade máxima do bloco C — "a tela precisa deixar
impossível achar que a etiqueta parou quando o STOP falhou" — saiu no commit `65431d9`.
A confirmação do STOP deixou de ser frase de tela e virou coluna da sessão, com aviso na
remessa e coluna própria na aba Auditoria. Detalhe e limites em
[[ThermoTrace - Fechamento do ciclo e STOP]].

Continua valendo, sem atenuação: **nada disso foi exercitado em etiqueta real**, e o
ensaio da semana encosta nisso primeiro.
