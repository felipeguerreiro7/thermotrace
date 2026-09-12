# ThermoTrace — Android 0.8.2

12/09/2026 · Projeto pessoal independente · APK de teste, versão 14.

## Resultado desta revisão

Revisadas as notas de ensaio, decisões D07/D08, triagem da semana e backlog atualizados pelo usuário. Mantidas as alterações de registro automático do checkpoint, linha do tempo, última coleta, reconciliação e exportação bruta. O relato mais recente de checkpoint instável e leitura final sem funcionar mantém o reteste físico aberto.

### Checkpoint

- Entra preparado para ler; ao terminar o download, registra automaticamente e mantém o resultado na tela, conforme D08.
- Uma nova coleta limpa o sucesso anterior, a prévia e a reconciliação anterior. Um download com falha não pode aparecer como coleta registrada.
- Enquanto grava, mostra “Registrando a coleta” e protege a saída. Falha de gravação mostra erro e permite repetir o registro da prévia, sem exigir outro download.
- Histórico, fila, ocorrências e fechamento feitos por `FluxoRapido.persistir` passam pela mesma transação Room. Não modifica o esquema nem os registros antigos.
- A contagem de checkpoints é atualizada após salvar; a remessa recarrega ao retornar.

### Leitura final

Abrir a tela e aproximar a etiqueta baixa o histórico. O botão **Encerrar monitoramento** salva o histórico e o fechamento lógico; somente depois do sucesso da gravação solicita o STOP físico. A tela permanece aberta para mostrar o resultado. Se necessário, aproximar novamente para a parada.

Esta sequência substitui o ajuste descrito no build 14:32: armar `Encerrar` automaticamente ao abrir a tela enviava STOP antes da confirmação e antes da persistência. D08 mantém a confirmação explícita do encerramento.

O rodapé distingue histórico salvo, aguardando etiqueta, STOP aceito e parada não confirmada; permite tentar somente a parada sem duplicar a leitura final. Dois toques de confirmação não repetem uma gravação já concluída. A resposta de sucesso do SDK é apresentada como “STOP aceito”, sem afirmar calibração ou homologação de reuso.

### Nota fiscal

Mantidas a leitura por câmera e a opção **Digitar nota**, na tela inicial e na remessa sem documento. Aceita número simples ou chave completa, verifica o dígito da chave e preserva conteúdo/origem manual; isso não consulta nem valida situação fiscal na SEFAZ. A câmera mantém captura NFC passiva para a etiqueta não abrir o visualizador do sistema durante a leitura da DANFE. A qualidade de leitura da nota impressa ainda exige reteste físico.

## Validação executada

**107 testes JVM passaram, sem falhas ou ignorados.** Incluem os cinco testes de reconciliação adicionados anteriormente e cinco novos testes da ViewModel: dois checkpoints, falha e repetição de registro, final com confirmação antes do STOP, falha ao salvar sem STOP e nova tentativa de parada sem duplicação.

Compilação do APK e análise estática concluídas: **0 erros, 22 avisos**. Atualização instalada com sucesso e abertura verificada no emulador Android existente. Isso verifica instalação/inicialização; o emulador não comprova leitura NFC nem o comportamento do chip. Os testes da ViewModel simulam repositório/SDK, não constituem ensaio de queda de energia no banco real.

## Reteste no aparelho

1. Atualizar o APK sem desinstalar nem limpar os dados.
2. Abrir uma remessa ativa, tocar em **Checkpoint**, afastar/reaproximar a etiqueta e aguardar **Coleta registrada**. Voltar e conferir a linha do tempo. Repetir o checkpoint e conferir as duas coletas.
3. Abrir **Leitura final**, aproximar e conferir o resultado. Tocar em **Encerrar monitoramento**, manter/reaproximar a etiqueta quando solicitado e conferir separadamente o histórico salvo e o resultado do STOP.
4. Se a parada não for confirmada, usar **Tentar parar a etiqueta** nessa tela. Se já tiver saído, a tela inicial permite identificar a etiqueta e usar **Parar registro sem baixar**; não reativar para tentar recuperar dados.
5. Vincular uma nota pela câmera e, em outra remessa de ensaio, testar a entrada manual. Exportar o Excel da remessa e preservar a evidência bruta para TT-005.

## Pendências preservadas

- TT-005 continua aberto: a mínima de −29,8 °C não foi explicada. Não foi alterada nenhuma fórmula de temperatura. Cabeçalho e série passam pelo mesmo SDK; concordância entre ambos não comprova exatidão do sensor nem elimina erro comum de interpretação.
- A diferença entre instante da coleta e último ponto não mede, sozinha, deriva do relógio; depende também do intervalo e do estado de gravação.
- Identificado na revisão: a coluna de reconciliação do Excel compara extremos, enquanto a tela também compara contagens. Unificar antes de considerar a exportação equivalente à conferência da tela.
- Falta registro durável próprio do resultado de cada tentativa de STOP e comprovação física de reuso. Fechamento lógico não é evidência de parada.
- A câmera real, duas coletas e final/STOP desta versão ainda precisam de reteste no A57. As confirmações registradas no Obsidian sobre builds anteriores não homologam este APK.
- D07 (localização via Play Services) mantida, ainda não implementada. Sem servidor publicado, isolamento local por cliente e fila autenticada continuam pendentes. Não há liberação para operação comercial.

Fontes técnicas de suporte aos testes: [Mockito](https://github.com/mockito/mockito/releases/tag/v5.23.0) e [coroutines-test](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/). Referência do controle NFC: [Android NfcAdapter](https://developer.android.com/reference/android/nfc/NfcAdapter).
