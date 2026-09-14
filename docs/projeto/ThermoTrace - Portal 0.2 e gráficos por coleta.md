---
tipo: entrega
projeto: ThermoTrace
contexto: pessoal-independente
atualizado: 2026-09-14
---

# Portal 0.2 · API 0.5 · Android 0.8.7

Entrega de 14/09/2026, projeto pessoal ThermoTrace.

## O que está implementado

O portal consulta o histórico térmico de uma coleta recebida pela API, a partir de carga → volume → sessões → comprovantes → Gráfico e relatório. Exibe curva, faixa configurada, mínima/máxima, quantidade de medições, quantidade fora da faixa e controle para explorar cada ponto. Cada gráfico pertence a um bipe; snapshots ainda não são consolidados entre coletas.

Downloads autenticados: CSV com todas as medições, SVG do gráfico e relatório HTML que pode ser impresso em PDF no navegador. Não há geração de PDF nativo nem laudo de liberação automática do produto.

A API 0.5 reconstrói horários nominais pelo início + atraso + índice × intervalo preservados. Não corrige horários pelo servidor, não interpola nem descarta silenciosamente pontos inválidos. Reconcilia extremos/contagens com o cabeçalho; divergências permanecem visíveis. Ativação, formato desconhecido ou histórico estruturalmente inválido não recebem gráfico inventado. Cadeia divergente bloqueia a série.

Cada consulta e exportação gera auditoria. A projeção tem versão e hash determinístico; o arquivo exportado tem SHA-256 registrado. Permissões por empresa permanecem aplicadas a todos os formatos. Staff não recebe acesso irrestrito às cargas; o vínculo autorizado cliente/transportadora continua pendente.

Corrigido limite indevido de 64 itens no bruto: cabem agora 12 campos de cabeçalho + até 65.535 medições, mantendo os limites de 256 KiB para o bruto e 1 MiB por requisição. O teste com 648 medições comprova que não há corte no transporte do histórico. O aplicativo 0.8.7 incorpora a mesma correção; Room continua na versão 7, sem nova migração.

## Evidências e limites

- 158 testes API/PostgreSQL aprovados; nenhuma migração pendente detectada.
- 150 testes JVM aprovados e APK 0.8.7-debug gerado.
- 13 testes de navegador sem interface aprovados, incluindo download autenticado e tratamento da sessão.
- Interface revisada no navegador com API de demonstração local e dados explicitamente fictícios: login, carga, comprovantes, gráfico, teclado até a última medição e acionamento de relatório; sem erros no console. Largura de 390 px conferida e corrigido transbordamento dos cartões; tabelas/gráfico possuem rolagem interna.
- Testes da API exercitaram exportações reais sobre PostgreSQL descartável, isolamento entre empresas, versões não suportadas, dados inválidos, divergência -29,8 e cadeia não íntegra. A demonstração visual não equivale a ensaio Android → Render.
- Sem teste físico novo. TT-005, checkpoint/final/STOP no A57 e validação contra instrumento de referência seguem abertos.

## Publicação e custo

Código segue pelo GitHub/main, no mesmo serviço Docker do Render. Nenhuma alteração de infraestrutura, serviço adicional ou compra nesta entrega. Domínio confirmado: thermotrace.com.br. URL pública da API, HTTPS/DNS, versão realmente implantada e percurso integrado permanecem sem confirmação. O print anterior comprova somente o status daquele momento.

A prévia local em http://127.0.0.1:8765/portal usa dados fictícios e não acessa clientes reais. Fora deste computador, esse endereço não representa o site publicado.

## Próximos passos e critérios de aceite

1. WEB-10: vínculo explícito carga/dono/transportadora, com escopo, aceite/revogação e auditoria. Testar três empresas, carga não compartilhada e revogação imediata antes de habilitar uso compartilhado.
2. WEB-03/04: confirmar Render/domínio e ensaiar Android → API → portal com contas de homologação e etiqueta física. Conferir contagem e recibos, sem prometer STOP pelo servidor.
3. WEB-11: telas de cadastro/convite e recuperação segura do acesso; controlar permissões de gestor e suporte.
4. WEB-12 restante: consolidação versionada entre snapshots, excursões/duração e relatório da viagem; não somar históricos repetidos. Validar regra de tempo e dados divergentes antes de análise avançada.
5. Operação: verificar backup/restauração e acesso restrito do banco antes de dados reais. Detalhar processos de contratação, preparação da carga, transporte, recebimento, triagem de desvio e suporte.


## Publicação no GitHub — Portal 0.2, 14/09/2026

Código enviado: [4ba684a](https://github.com/felipeguerreiro7/thermotrace/commit/4ba684ad6d48e981405f6e07e9471f0137d5ce7b). Push em main concluído e SHA remoto conferido igual ao local. Mensagem explica gráficos/exportações auditadas por coleta, correção do limite de histórico, testes e limites. 158 testes API, 150 JVM e 13 web aprovados; APK 0.8.7-debug gerado.

37 notas do projeto sincronizadas. O envio ao GitHub não comprova deploy bem-sucedido no Render; domínio/HTTPS e ensaio integrado continuam pendentes. Nenhuma contratação nova. Detalhes: [[ThermoTrace - Portal 0.2 e gráficos por coleta]].
