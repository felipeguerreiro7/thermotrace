---
tipo: entrega
projeto: ThermoTrace
contexto: pessoal-independente
status: implementado-em-validacao
atualizado: 2026-09-11
---

# ThermoTrace Android 0.7.3 — entrega para teste

11/09/2026 · Projeto pessoal independente · versionCode 11 · pacote com.thermotrace.app.debug · Android 8 ou posterior.

## Correções implementadas

- Decodificação mantém os horários nominais da etiqueta. Ler dias depois de encerrar não estica mais o intervalo nem inverte a série quando o relógio do celular está atrasado.
- Histórico truncado, valores não finitos e campos obrigatórios inválidos são recusados antes do gráfico. Dados recebidos completos permanecem com a versão do decodificador fm13dt160-decoder-1.1.
- Gráfico usa tempo real no eixo horizontal, mostra amostra única, interrompe trechos com lacunas detectadas e mantém casas decimais dos limites. Início/fim exibem horário e fuso.
- Estado NFC desconhecido deixa de ser convertido em etiqueta parada. Falha na identificação pode ser recuperada em nova aproximação e aparece na tela inicial.
- Operações NFC das telas compartilham uma fila para o SDK único. Comandos guardam a identidade esperada da etiqueta; callbacks antigos não consomem comandos novos. Fechar uma tela antiga não desliga o leitor da nova.
- O texto “deriva do relógio” foi substituído pela diferença entre leitura e último ponto: esse número não mede sozinho a deriva física do sensor.

## Evidência

Seis testes de regressão falharam com o decodificador anterior e passaram após a correção. Conjunto final: 67 testes automatizados, sem falhas ou testes ignorados. Build do APK e análise estática concluídos: 0 erros e 18 avisos. Testes cobrem cenários sintéticos; não substituem a comparação com a etiqueta e o aplicativo do fornecedor.

## Limites desta entrega

APK de desenvolvimento, sem homologação física e sem assinatura comercial. Não instalado no celular nesta sessão. A confiabilidade real do NFC, a correspondência dos dados com o sensor e a paridade completa com o aplicativo chinês continuam pendentes de bancada. Não considerar os testes como validação para liberar cargas de saúde.

Registros antigos não foram reprocessados; uma série gravada antes da correção pode conter horários já alterados. Migração/reprocessamento exige preservar a evidência original e indicar a nova interpretação. Armazenamento de leituras rejeitadas como evidência, integração com o servidor e análise de colisões entre aparelhos continuam no backlog.

O arquivo de alterações Android contém apenas arquivos modificados/novos e um diff; deve ser aplicado sobre a base ThermoTrace existente. A fonte original foi preservada em backup local antes da edição. Não é um pacote completo para compilar sem essa base.
