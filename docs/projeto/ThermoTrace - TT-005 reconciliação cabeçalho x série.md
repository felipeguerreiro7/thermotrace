---
tipo: evidencia
projeto: ThermoTrace
contexto: pessoal-independente
status: em-implementacao
atualizado: 2026-09-12
---

# TT-005 — reconciliação entre cabeçalho e série

Primeiro passo da investigação do gráfico incorreto, decidido com o usuário em 12/09/2026
como prioridade antes de qualquer melhoria de apresentação. Origem do problema em
[[ThermoTrace - Ensaio em aparelho 2026-09-12]].

## O achado que destrava a investigação

A resposta que a etiqueta devolve não é só a série. Lendo `nfc/SessionDecoder.kt`, o
formato tem doze campos de cabeçalho antes das amostras:

| Campo | Conteúdo |
|---|---|
| 0 | código de estado |
| 1 | início (epoch) |
| 2 | amostras previstas |
| 3 | amostras medidas |
| 4 | atraso em minutos |
| 5 | intervalo em segundos |
| 6 | **mínima registrada pela etiqueta** |
| 7 | **máxima registrada pela etiqueta** |
| 8 / 9 | limites mínimo e máximo da faixa gravada |
| 10 / 11 | **pontos abaixo / acima contados pela etiqueta** |
| 12+ | amostras, `temperatura` ou `temperatura:indicador` |

Os campos 6, 7, 10 e 11 são calculados **pela própria etiqueta**, não pela nossa
decodificação da série. São, portanto, uma testemunha independente: se a série decodificada
discorda do cabeçalho, um dos dois está errado — e isso se detecta sem termômetro aferido,
sem o app do fabricante e sem sair do lugar.

Esses quatro campos já eram decodificados e exibidos na tela de Diagnóstico
("Mínima registrada", "Máxima registrada", "Pontos abaixo / acima"), mas **nunca eram
confrontados** com a série que vira gráfico e laudo. Era a informação certa no lugar errado.

## Experimento imediato, sem build

Encostar a mesma etiqueta na tela **Diagnóstico** e ler "Mínima registrada" e "Máxima
registrada". Três desfechos, todos informativos:

1. A etiqueta declara algo como 24 a 31 °C → a série está sendo mal decodificada, e o
   problema é de interpretação dos bytes das amostras.
2. A etiqueta declara ela mesma −29,8 °C → a decodificação da série está certa e o problema
   é anterior: sensor, gravação na etiqueta ou o próprio SDK ao escrever.
3. O cabeçalho vem ilegível ou zerado → problema de leitura do bloco de cabeçalho.

Nenhum dos três permite corrigir fórmula ainda; todos reduzem o espaço de busca.

## Implementado em 12/09/2026

`Reconciliacao`, em `nfc/SessionDecoder.kt`, calculada em toda decodificação:

1. Mínima e máxima da série confrontadas com os campos 6 e 7, com tolerância de 0,15 °C
   para o arredondamento de uma casa decimal que a etiqueta publica.
2. Contagem de pontos fora da faixa confrontada com os campos 10 e 11.
3. `naoReconciliado` e um texto curto que nomeia as duas versões, sem escolher entre elas.

A tela de leitura mostra o texto **apenas quando há divergência**, junto de um aviso de que
o laudo não deve ser tratado como conferido até a diferença ser explicada. Um aviso que
nunca cala é um aviso que ninguém lê.

Deliberadamente fora do escopo: corrigir valor. As regras do cofre proíbem inventar
explicação para diferença não reconciliada; aqui a diferença é medida, nomeada e carregada
adiante. Quem decide o que ela significa é a comparação com o app do fabricante e com
termômetro aferido.

Não houve migração de banco: a reconciliação é derivada de `LeituraEntity.respostaBruta`,
que é imutável, então pode ser recalculada para qualquer leitura passada sem alterar
evidência.

Testes: `ReconciliacaoTest`, cinco casos — série coerente não levanta alarme; extremo
absurdo é denunciado pelo cabeçalho; contagem divergente é detectada mesmo com extremos
coerentes; arredondamento de uma casa não vira divergência; e o texto cita as duas versões.
Pendente de execução da suíte.

## Próximos passos

1. Rodar a tela Diagnóstico na etiqueta 1 e anotar aqui os campos 6, 7, 10 e 11.
2. Refazer uma coleta com o build novo e ver se o aviso de não reconciliado aparece.
3. Só então decidir onde investigar: interpretação das amostras, SDK do fabricante ou
   gravação na etiqueta.
4. Comparar com o app do fabricante na mesma etiqueta, e o termômetro ao vivo do
   Diagnóstico contra um termômetro aferido.


## Correção de interpretação e implementação — 0.8.3

Cabeçalho e série não são uma referência independente calibrada: ambos passam pelo SDK do fabricante. Concordância não prova exatidão e não elimina erro comum; divergência também não identifica sozinha qual lado está correto. Os desfechos do experimento acima são hipóteses para orientar investigação.

`tt-reconciliacao-1.1` agora distingue coerente, divergente e não avaliável; é comum à tela, ao histórico e ao Excel. A exportação anterior conferia apenas extremos, lacuna corrigida nesta entrega. As temperaturas preservadas não mudam; conferência é derivada e versionada separadamente do decodificador original. Evidência original reconstruível na nova aba do Excel.

117 testes passaram (incluindo 12 de reconciliação e 3 de exportação). A mínima −29,8 °C continua sem causa confirmada. [[ThermoTrace - Histórico e auditoria Android 0.8.3]].
