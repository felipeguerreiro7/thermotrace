# ThermoTrace Android 0.8.5 — revisão integrada

Entrega de 14/09/2026. Projeto pessoal independente. Versão 0.8.5-debug, código 17, banco Room 6.

## O que foi revisado e preservado

Foram lidos o código e os registros do Obsidian após as alterações feitas com Opus. Preservados: isolamento local por empresa/operador/servidor, gráfico com escala e seleção de ponto, localização da coleta e histórico, indicação de reconciliação por coleta e registro de STOP. Os commits 1b76ec9, 65431d9 e ca28bde estavam locais e foram enviados ao GitHub, mantendo autoria e histórico.

## Correções e motivo

- O atalho Finalizar da Home passa pelo fluxo formal de leitura final. Antes ele podia solicitar encerramento da etiqueta antes de persistir a coleta; agora usa a sequência de baixar, confirmar, gravar e então solicitar STOP.
- A confirmação de STOP é gravada com atualização atômica, preservando a primeira confirmação. Falha ao gravar a confirmação fica visível; não vira sucesso nem encerra a tela silenciosamente. O histórico já salvo permanece.
- Gerar um arquivo no cache ou abrir/cancelar o compartilhamento não comprova cópia salva. A exportação principal agora pede um destino, fecha a escrita e relê os bytes, conferindo tamanho e SHA-256 antes de marcar as leituras incluídas. Cancelamento ou erro não confirma cópia.
- A migração 5→6 acrescenta copiaConfirmadaEmMillis. exportadaEmMillis antigo permanece como histórico, sem atribuir confirmação retroativa a exportações anteriores. Salvar no próprio celular não equivale a backup remoto.
- O pedido de localização anterior é cancelado ao iniciar outro e congelado ao receber a coleta, evitando que uma resposta atrasada altere a localização antes de confirmar.
- Schemas Room 5 e 6 versionados e ensaio de migrações adicionado para reduzir o risco de atualização impedir acesso ao histórico.

## Evidências de validação

- 135 testes JVM: zero falhas, erros ou ignorados.
- APK debug e APK de instrumentação compilados; lint sem erros (23 avisos).
- Emulador Android: isolamento por empresa, operador e servidor; fila e ajustes preservados ao reabrir; migrações 2/3/4/5→6 com todas as colunas antigas preservadas; primeiro STOP não sobrescrito.
- Fixtures só contêm dados sintéticos. V5 deriva do schema exportado; V2–V4 são reconstruídas a partir das diferenças de migração. Isso não equivale a atualizar um banco real antigo; caminho 1→2 continua pendente.
- APK instalado e aplicativo aberto no emulador, versão 0.8.5-debug/código 17 conferida.

## Reteste necessário

1. No celular, repetir ativação, checkpoint, final com confirmação e STOP, inclusive afastando a etiqueta e tentando novamente.
2. Exportar escolhendo um destino; cancelar e provocar uma falha para conferir que não aparece confirmação indevida. Conferir também um provedor em nuvem real.
3. Verificar gráfico, localização e permissão negada; comparar etiqueta e aplicativo do fornecedor. A divergência de −29,8 °C (TT-005) continua aberta.
4. Atualizar um aparelho com histórico real e conferir leituras/fila, sem desinstalar ou apagar dados antes.

## Próxima entrega

Integrar a fila Android à API 0.4 com autenticação, associação de carga/volume, idempotência e recibo persistido. Depois, consolidar temperaturas e gráficos no portal, com acesso autorizado entre dono da carga e transportadora. O envio automático das coletas ainda não está disponível.

Nenhuma mudança de infraestrutura ou nova contratação nesta entrega. GitHub e instalação local não comprovam publicação no Render. Domínio e URL pública ainda exigem conferência. Custos e desenho inicial de processos foram registrados no Obsidian; processos operacionais ainda precisam de definição e validação com o piloto.

## Publicação conferida

Código: [43563f6](https://github.com/felipeguerreiro7/thermotrace/commit/43563f6f2ac0f0df718fff8c1b867adf38f2ce55). Push em main confirmado por comparação com a referência remota em 14/09/2026. Inclui os três commits locais do Opus. APK SHA-256: `94579e6e59a1bc3af3c5fc58ff619324b5fd3a1a9fe5462ad7d19eadd8ea0e85`. Documentação acompanha em commit próprio; nenhum deploy Render foi declarado.
