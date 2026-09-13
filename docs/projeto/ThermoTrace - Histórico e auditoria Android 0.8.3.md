---
tipo: entrega
projeto: ThermoTrace
contexto: pessoal-independente
status: implementado-reteste-pendente
atualizado: 2026-09-12
---

# ThermoTrace — Android 0.8.3

12/09/2026 · Projeto pessoal independente · Versão 15 · APK de teste.

## Entrega

**Histórico de coletas e conferência de evidências**, sem alterar fórmulas de temperatura ou registros existentes. Mantidas as correções de checkpoint, confirmação da leitura final e nota manual da 0.8.2.

### Histórico por volume

“Ver todas as coletas” aparece no cartão do volume e no relatório. Lista as coletas persistidas da sessão carregada, da mais recente para a mais antiga. Cada uma mostra tipo, horário com fuso, temperatura instantânea do bipe (ou indisponibilidade), contagem de registros baixados, situação da série perante a faixa da sessão e conferência do cabeçalho.

“Detalhes da evidência” mostra ID, hash do bruto, SDK e decodificador usados na coleta, versão da conferência recalculada, envio ao servidor e informações de horário. A diferença coleta–último ponto não é apresentada como deriva medida do sensor. Leituras sucessivas podem conter os mesmos pontos: o histórico avisa que suas contagens não devem ser somadas.

### Uma conferência para tela e Excel

`tt-reconciliacao-1.1` compara os extremos e as contagens abaixo/acima da faixa. O mesmo componente é usado pelo decodificador, histórico e exportação. Antes, o Excel comparava apenas extremos e podia dizer “Sim” quando a tela apontava divergência de contagens.

Três resultados explícitos: **cabeçalho e série coerentes**, **divergência encontrada**, **não avaliável**. Cabeçalho incompleto, extremos ilegíveis/não finitos, ausência de amostras, limites inválidos e contagens incompatíveis deixam de parecer conferência bem-sucedida. A tolerância de comparação dos extremos continua em 0,15 °C, um critério de engenharia que ainda requer validação contra o fornecedor.

Quando há pendência em alguma coleta, o cartão sinaliza “A conferir” e o resultado geral do relatório/Excel vira **Pendente de conferência**. O cálculo térmico permanece identificado separadamente e as temperaturas são preservadas. Coerência interna não comprova calibração nem autoriza liberação da carga; cabeçalho e série passam pelo mesmo SDK e podem compartilhar um erro.

### Evidência bruta reconstruível

Nova aba **Evidência bruta**: ID da coleta/sessão, índice original de cada campo, ordem das partes e conteúdo codificado como string JSON. Campos longos são fragmentados em até 30.000 caracteres sem cortar pares de caracteres Unicode. Para reconstruir: agrupar por coleta/índice, ordenar partes, concatenar e decodificar a string JSON; então ordenar os campos pelo índice original.

Isso evita concentrar uma resposta longa numa célula. O Excel suporta até 32.767 caracteres por célula, conforme [Microsoft](https://support.microsoft.com/en-us/excel/excel-specifications-and-limits). A coluna anterior fica como resumo legível; a reconstrução exata deve usar a nova aba. Campos com delimitador, aspas, quebra de linha, texto longo e emoji foram reconstruídos em teste a partir do XML efetivamente gerado.

## Validação

**117 testes JVM passaram, sem falhas ou ignorados.** Dez testes novos/ampliados de conferência e exportação cobrem dados ausentes, cabeçalho inválido, contagens, série truncada, preservação dos extremos, equivalência de critérios e reconstrução do bruto. O teste da exportação gera o arquivo completo e confere as sete abas no XML, inclusive o resultado pendente e a versão da conferência.

APK compilado; análise estática: **0 erros e 22 avisos**. Atualização instalada no emulador existente e abertura verificada. Não houve teste físico de NFC nesta versão nem validação visual do histórico preenchido no A57. O arquivo exportado foi conferido estruturalmente por teste; isso não representa homologação visual no aplicativo Microsoft Excel.

## Como conferir no próximo ensaio

1. Atualizar sem desinstalar nem limpar dados. Abrir uma remessa com coletas já registradas e tocar em “Ver todas as coletas”.
2. Conferir horário, temperatura no bipe e contagem; expandir detalhes de uma coleta.
3. Gerar o Excel e comparar a conferência da aba Auditoria com a da tela. Localizar a mesma coleta pelo ID na aba Evidência bruta.
4. Repetir checkpoint e leitura final conforme o roteiro da 0.8.2; a gravação continua automática somente no checkpoint. Nesta versão não foi alterada a sequência NFC.
5. Para TT-005, preservar uma nova leitura registrada e a exportação da mesma etiqueta no aplicativo do fabricante. Não apagar valores estranhos nem mudar fórmula para fazer o gráfico parecer correto.

## Pendências

TT-005 ainda não tem causa confirmada para −29,8 °C. Histórico da sessão atual não equivale à consolidação de múltiplos aparelhos ou de todas as sessões antigas. Esta entrega não modifica o esquema do banco, a conta, a fila autenticada ou a API.

Reteste de checkpoint/final/STOP, resultado durável próprio do STOP, isolamento local por cliente e integração da fila à API 0.4 continuam pendentes. D07/localização via Google Play Services permanece aprovada e ainda não implementada. Gráfico interativo, portais, hospedagem e distribuição comercial seguem no backlog.
