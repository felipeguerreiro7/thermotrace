---
tipo: proposta
projeto: ThermoTrace
contexto: pessoal-independente
status: rascunho-aguardando-decisao
atualizado: 2026-09-12
---

# Transparência da coleta, localização e gráfico interativo

Origem: pedido do usuário em 12/09/2026 — "mais transparência: mostrar as coletas feitas e
a temperatura, usar a localização para dizer onde, trazer na tela a localização exata da
última coleta, e melhorar o gráfico deixando claro quais foram as temperaturas e
interativo". Complementa [[ThermoTrace - Proposta de gráfico e coleta]]. Rascunho para
decisão; nada implementado além do status simples já entregue em 12/09.

Pesquisa técnica feita em 12/09/2026 nas fontes listadas no fim. Fonte: documentação oficial
do Android, consultada nesta data.

## 1. Histórico de coletas por volume

Hoje o cartão do volume mostra só a última coleta (tipo, data/hora, temperatura
instantânea, contagem). Proposta: lista completa das coletas da sessão, cada linha com
tipo, data/hora no fuso do aparelho, temperatura instantânea, quantidade de registros
baixados naquela aproximação e veredito do trecho. Os dados já existem em
`LeituraEntity` (`lidaEmMillis`, `tipo`, `temperaturaInstantaneaC`, `quantidadeMedida`,
`desvioRelogioMs`) e já são carregados pela `RemessaViewModel` em `sessoes`. É apresentação,
não coleta nova.

Acrescentar por coleta o `desvioRelogioMs` e a versão do decodificador, recolhidos num
detalhe expansível: é o que permite explicar um laudo meses depois sem abrir o banco.

## 2. Localização da coleta

### O que a pesquisa mostrou

1. O caminho recomendado pelo Google é `FusedLocationProviderClient.getCurrentLocation()`,
   descrito na documentação como "the recommended way to get a fresh location" e mais
   seguro que gerenciar `requestLocationUpdates()`. **Exige Google Play Services**: "your
   app's development project must include Google Play services".
2. `LocationManager.getCurrentLocation()` existe desde a API 30 e não exige Play Services.
   O `minSdk` do ThermoTrace é 26, então aparelhos 26–29 precisariam de caminho alternativo.
3. Geocodificação reversa (`Geocoder`) **exige rede**. O `getFromLocation` síncrono foi
   **descontinuado na API 33**, que introduziu a variante assíncrona com `GeocodeListener`.
   Com `minSdk` 26, os dois caminhos seriam necessários.

### Consequência de projeto

Doca, câmara fria e caminhão são justamente onde não há rede e onde o GPS demora ou falha.
Portanto:

1. **A coordenada é evidência; o endereço não é.** Gravar latitude, longitude, precisão em
   metros, provedor e o instante do fix junto da leitura, na mesma transação que insere a
   evidência bruta. O endereço legível é enriquecimento posterior, opcional, marcado como
   tal, e nunca substitui a coordenada.
2. **A coleta nunca espera o GPS.** O bipe é o ato crítico. O fix é pedido em paralelo, com
   prazo curto; se não vier, a leitura é gravada com "sem localização" — explícito, nunca em
   branco e nunca inventado, conforme as regras do cofre.
3. **Rotular o que o dado é de fato.** É onde estava o *celular* no momento do fix, não onde
   estava a carga, e o fix tem idade. A tela e o laudo precisam dizer isso, além da
   precisão. Um ponto com 2 km de erro apresentado como "localização exata" é pior que
   nenhum.
4. **Permissão pedida em contexto.** Localização aproximada pode bastar para dizer a cidade;
   precisa, para dizer a doca. Decidir qual se pede, e o app tem de continuar funcionando
   inteiro com a permissão negada — recusar a coleta por falta de GPS seria perder evidência
   térmica por causa de um metadado.
5. **Isto é dado pessoal do operador.** Entra na divulgação ao usuário e na política de
   privacidade prevista em [[ThermoTrace - Publicação e operação]], e em
   [[ThermoTrace - Segurança e auditoria]] como campo protegido contra alteração.

### Decisão pendente (localização)

Adotar Play Services pelo caminho recomendado, ou ficar sem Play Services usando
`LocationManager` com alternativa para 26–29? Play Services dá o melhor fix e a API
recomendada, ao custo da primeira dependência Google do app e de aparelhos sem serviços
Google. Sem Play Services mantém o app enxuto e independente, ao custo de fix pior e mais
código.

## 3. Gráfico interativo

### Proposta

1. Toque e arraste sobre o gráfico movem uma linha de leitura, com balão mostrando
   temperatura, horário e se aquele ponto está dentro ou fora da faixa.
2. Eixo Y rotulado, limites da faixa anotados no próprio gráfico, marcas de tempo
   intermediárias, trechos de excursão sombreados com duração, pontos fora da faixa em cor
   distinta, lacunas como interrupção da linha. Detalhado em
   [[ThermoTrace - Proposta de gráfico e coleta]] (TT-042).
3. Escala ancorada na faixa aprovada, com recorte marcado para extremos — sem isso, um
   ponto absurdo achata o critério, que foi o que aconteceu no ensaio de 12/09.
4. Seleção de janela de tempo para séries longas (648 registros cabem em 4d 11h).

### Biblioteca ou código próprio

Recomendação: **código próprio**, mantendo o `Canvas` atual e acrescentando `pointerInput`.
Razões:

1. O gráfico carrega semântica de auditoria que biblioteca genérica não modela — faixa
   aprovada preservada na criação da carga, lacunas por `iniciaTrecho`, excursões. Encaixar
   isso no modelo de dados de uma biblioteca é o caminho para a evidência se acomodar ao
   gráfico, e não o contrário.
2. O projeto tem política explícita de dependência mínima; o comentário da fábrica de
   ViewModels justifica não usar nem Hilt.
3. A interação pedida — arrastar para ler valor — é pequena com `pointerInput`. Zoom e pan
   de verdade seriam o motivo para trazer biblioteca; não é o pedido.
4. Vico é a alternativa mais madura se a decisão for inverter (Apache-2.0, Material 3).
   Não consegui confirmar na pesquisa o suporte a marcador por toque e zoom/pan nem o nível
   mínimo de API; a confirmar antes de qualquer adoção.

## Ordem proposta

1. TT-005 primeiro. A mínima de −29,8 °C do ensaio de 12/09 continua sem explicação, e
   apresentação melhor sobre dado não reconciliado só torna o erro mais convincente.
2. Histórico de coletas e gráfico interativo depois, que são apresentação sobre dado já
   persistido.
3. Localização por último entre os três, por ser a única que acrescenta captura nova,
   dependência possível, permissão e dado pessoal.

## Fontes

1. Android Developers — Get the last known / current location: https://developer.android.com/develop/sensors-and-location/location/retrieve-current (consultado 2026-09-12)
2. Android Developers — `Geocoder` reference: https://developer.android.com/reference/android/location/Geocoder (consultado 2026-09-12)
3. Google Play services — `FusedLocationProviderClient` reference: https://developers.google.com/android/reference/com/google/android/gms/location/FusedLocationProviderClient (consultado 2026-09-12)
4. Vico: https://github.com/patrykandpatrick/vico — licença Apache-2.0 confirmada; capacidades de interação não confirmadas (consultado 2026-09-12)


## Implementação parcial — 0.8.3

Histórico das coletas da sessão implementado no cartão do volume e no relatório, com detalhes expansíveis e conferência interna dos dados. [[ThermoTrace - Histórico e auditoria Android 0.8.3]]. Temperatura instantânea e histórico da série ficam identificados separadamente. Decisão de Play Services já aprovada em D07; o trecho “decisão pendente” acima é histórico. Localização e gráfico interativo ainda não implementados.

## Executado em 14/09/2026 — TT-047, commit 188b620

Implementação da localização da coleta, seguindo a decisão D07 (Play Services).

### O que foi construído

1. `data/local/Localizador.kt` — `FusedLocationProviderClient.getCurrentLocation` com prazo de
   8 s, prioridade alta quando há permissão fina e balanceada quando só há aproximada.
   Devolve `FixLocal` com latitude, longitude, precisão em metros, provedor e **instante do
   fix**, mais o cálculo de idade do ponto.
2. Banco na versão 3, com migração escrita à mão. Cinco colunas anuláveis em `leitura`.
   Nada retroativo: leitura antiga não tem localização, e preencher com a posição de hoje
   seria inventar evidência.
3. O fix entra na **mesma transação** da leitura, pelo `FluxoRapido`. Gravar depois abriria
   uma janela em que a evidência existe e a localização não, e evidência com estado
   intermediário não se audita.
4. Permissão pedida em contexto, só depois de a coleta existir, com a explicação de para que
   serve. Apenas primeiro plano. Negar não impede operação nenhuma.
5. Aba Auditoria do laudo ganhou latitude, longitude, precisão, provedor e instante do fix.

### As três regras que guiaram o desenho

1. **O bipe nunca espera o GPS.** O pedido sai em paralelo quando a tela arma; entre armar e
   o operador encostar a etiqueta há segundos de sobra. Não chegou, grava sem — falha de fix
   é resultado normal, não erro.
2. **A coordenada é evidência; o endereço não.** Geocodificação exige rede, e doca, câmara
   fria e caminhão são exatamente onde não há. Endereço fica fora.
3. **Precisão e idade andam sempre junto da coordenada**, e o texto diz que é a posição do
   celular, não da carga. Um ponto com quilômetros de erro anunciado como "local da coleta" é
   pior que nenhum ponto.

### Fora de escopo, de propósito

Endereço legível; exibição na linha do tempo da remessa; e a **divulgação de privacidade** —
localização é dado pessoal do operador e precisa entrar na política prevista em
[[ThermoTrace - Publicação e operação]]. Sem ensaio em aparelho ainda: o fix real nunca foi
obtido em campo.

### Erro de processo corrigido no mesmo dia — commit 09fb961

As edições por script gravaram cinco arquivos inteiros em LF, e o projeto usa CRLF. O git
passou a ver arquivo reescrito por completo: `Repositorio.kt` aparecia com 1402 linhas
alteradas quando a mudança real eram 6.

Não é cosmético. Diff ilegível esconde a mudança de verdade na revisão, e arquivo inteiro
reescrito vira conflito garantido quando outro agente edita o mesmo arquivo em paralelo —
que é o cenário deste projeto. O `.gitattributes` com `* -text` impede o git de converter,
mas não impede um editor de gravar tudo em LF.

Regra para as próximas edições por script, de qualquer IA: preservar o fim de linha original
do arquivo.
