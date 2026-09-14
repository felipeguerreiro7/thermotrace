---
tipo: projeto
projeto: ThermoTrace
contexto: pessoal-independente
status: em-implementacao
atualizado: 2026-09-13
---

# Plano de ação e backlog

Estimativa de planejamento, não compromisso: 8–12 semanas para piloto Android, com uma pessoa de desenvolvimento dedicada, apoio de produto/qualidade e hardware disponível. Reestimar após bancada. iOS e homologações externas fora desse intervalo. Papéis abaixo são responsáveis propostos, pessoas a designar.

| Etapa | Janela indicativa | Responsável | Dependência | Saída verificável |
|---|---|---|---|---|
| 0 — Recuperar base | semana 1 | Desenvolvimento | Código e etiquetas | Versão compilada, inventário e comparação com chinês |
| 1 — Identidade e banco | semanas 2–3 | Backend + produto | Etapa 0 | Duas contas isoladas, cargas/documentos e migração testada |
| 2 — Campo e sincronização | semanas 3–5 | Android + backend | Contrato e identidade | Início/checkpoints/final entre dois celulares, offline e reenvio |
| 3 — Gráfico e relatório | semanas 5–7 | Desenvolvimento + qualidade | Leituras persistidas | Gráfico parcial/final, exportações e reconstrução |
| 4 — Portais | semanas 6–8 | Desenvolvimento | API autorizada | Cliente e equipe com escopos distintos |
| 5 — Segurança e piloto | semanas 8–12 | Desenvolvimento + qualidade + produto | Fluxo completo | Restauração, testes A/B, distribuição e aceite de campo |

## Backlog executável
- [x] TT-001 — Inventariar base e divergências documentais. Evidência: [[ThermoTrace - Diagnóstico da base]].
- [x] TT-002 — Organizar requisitos e plano no Obsidian.
- [x] TT-003 — Preparar componente isolado de consolidação e testes; integração pendente.
- [x] TT-010 P0 — FEITO em 12/09/2026: repositório git inicializado em `C:\Users\lfgue\ThermoTrace`, branch `main`, commit inicial `867a107` com 221 arquivos e 1,9 MB. Fora do versionamento por decisão: `backend/.env`, `local.properties`, caches do Gradle, saídas de build e APKs — conferido arquivo a arquivo antes do commit. `core.autocrlf=false` para não reescrever as quebras de linha CRLF do projeto. Falta o aceite "clone limpo compila" e um remoto privado.
- [ ] TT-011 P0 — Matriz completa do chinês. Aceite: cada função tem comparação, resultado e evidência em etiqueta real.
- [ ] TT-012 P0 — Separar fechamento lógico e STOP físico nas telas/repositório. Aceite: checkpoint nunca encerra; STOP falho não aparece confirmado.
- [ ] TT-020 P0 — Modelar proprietário, membros e compartilhamentos. Aceite: A/B isolados e transportador vê só carga concedida.
- [x] TT-021 P0 — Código e migrações de documentos/vínculos validados em banco descartável, inclusive legado sintético preservado. Duas notas numa carga e uma nota em entregas parciais testadas. Aplicação ao banco existente ainda pendente.
- [ ] TT-022 P0 — Login, convite, recuperação, MFA administrativo e revogação. Aceite: testes negativos de token/papel.
- [ ] TT-030 P0 — Contrato de ingestão e catálogo. Aceite: recibo persistido, payload validado e autorização por sessão.
- [ ] TT-031 P0 — Fila offline transacional e reenvio. Aceite: app fechado durante envio não perde nem duplica leitura.
- [ ] TT-032 P0 — Concorrência e dados conflitantes. Aceite: dois celulares, mesmo histórico e divergência detectada.
- [ ] TT-040 P1 — Integrar série consolidada e gráfico no app. Aceite: pontos iguais às evidências, lacunas visíveis.
- [ ] TT-041 P1 — Gerar PNG/SVG, CSV/XLSX e PDF versionados. Aceite: download autorizado e mesmo conjunto de dados.
- [ ] TT-050 P1 — Portal cliente: cargas, filtros, detalhe, usuários e exportação. Aceite: jornada sem acesso a outro cliente.
- [ ] TT-051 P1 — Portal equipe: provisionar cliente, status de sincronização, diagnóstico, concessão de suporte e trilha.
- [ ] TT-060 P0 — Proteção de evidências e manifestos externos. Aceite: alteração detectável e papel da API sem exclusão.
- [ ] TT-061 P0 — Backup/restauração e monitoramento. Aceite: recuperar carga em ambiente isolado dentro da meta acordada.
- [ ] TT-062 P0 — Pipeline, homologação, assinatura e distribuição. Aceite: instalar versão assinada e atualizar sem perder dados.
- [ ] TT-063 P0 — Piloto real. Aceite: cargas do piloto conferidas com fornecedor e responsável de qualidade.

## Primeiro ciclo de trabalho
Ciclo iniciado: correções Android e acesso/perfis do servidor entregues para validação automatizada. Próximo: cargas/documentos, produto/perfil e ingestão. A bancada será realizada depois, conforme disponibilidade informada pelo usuário.

## Gestão
Revisão semanal de entregas demonstráveis, impedimentos, riscos e gasto de infraestrutura. Limite inicial: uma entrega principal em desenvolvimento. Cada cartão recebe responsável nominal, critério, evidência de teste e data; “pronto” exige execução, não apenas código presente.

## Repriorização após resposta do usuário
- [ ] TT-004 P0 — Reproduzir leitura intermitente: registrar aparelho/SO, etiqueta/UID, configuração, ação, resultado, duração e log sem dados pessoais. Comparar com app chinês, mantendo operação por aproximação e controle do estado.
- [ ] TT-005 P0 — Reproduzir gráfico incorreto: guardar payload bruto e exportação do fornecedor; comparar contagem, índices, temperatura, unidade, início, delay, intervalo e timezone antes da renderização. Corrigir causa e adicionar regressão com fixture real anonimizada.
- [ ] TT-006 P0 — Ensaiar pelo menos 30 operações por combinação de aparelho/etiqueta selecionada, registrando falhas e recuperação; número é amostra inicial de engenharia, não certificação. Gate: nenhuma falha silenciosa, dado perdido ou gráfico divergente; meta de sucesso e ampliação do ensaio acordadas com qualidade.
- [ ] TT-007 P0 — Parceiro de saúde define carga piloto, perfil, referência térmica/calibração, critérios de excursão e quem decide liberação.

Gráfico correto permanece P0. Por orientação do usuário, TT-004/006 e a validação com hardware ficam para depois, sem bloquear banco, sincronização, portais e relatórios. O cronograma segue preliminar. Ensaios físicos e validação do responsável de qualidade continuam obrigatórios para considerar o piloto pronto.

## Perfis térmicos pré-configurados

Implementar TT-070 a TT-076 em [[ThermoTrace - Catálogo de perfis térmicos]]. Fluxo recorrente: código da carga → perfil preenchido → bip. Fontes e limites em [[ThermoTrace - Regulamentação e faixas térmicas]].

## Entregas verificadas em 11/09/2026

- [x] Corrigir distorção temporal e validar estrutura do histórico; seis regressões reproduziram falha anterior.
- [x] Corrigir eixo temporal, amostra única e lacunas no gráfico; APK 0.7.3-debug gerado.
- [x] Distinguir estado NFC desconhecido, recuperar identificação e serializar operações entre telas; validação física pendente.
- [x] Implementar login, refresh com detecção de reuso, logout e desativação de operador.
- [x] Implementar provisionamento de clientes e usuários por papel, com testes de acesso cruzado.
- [x] Implementar rascunho, evidência, aprovação interna, versões e retirada de perfil; RLS dos perfis testada.
- [x] Corrigir proteção de excursões contra alteração junto de substituição; validar migrações em banco descartável.

TT-022 permanece parcial: convite, troca/recuperação de senha e MFA ainda faltam. TT-020 permanece parcial: compartilhamento de cargas e RLS operacional ainda faltam. TT-040 permanece parcial: a consolidação entre aparelhos ainda não está integrada. TT-060 permanece parcial: falta manifesto externo independente. Nenhum desses itens amplos foi marcado como concluído.

Sequência de execução sem hardware: TT-021 → vínculo produto/perfil → TT-030/031/032 → integração Android → TT-041/050/051 → TT-061/062. Detalhes em [[ThermoTrace - Próximo ciclo sem hardware]].

## Atualização de execução — servidor 0.3

- [x] Produto/configuração versionado com preenchimento do perfil e intervalo na criação da carga.
- [x] Idempotência de produtos, criação de cargas, documentos adicionais e cancelamento; repetição simultânea verificada.
- [x] RLS nas cargas/filhos, produtos e recibos; vínculo estrangeiro entre clientes recusado.
- [x] Critério de carga, documento, identidade de volume e recibo protegidos contra alteração.
- [x] Contrato OpenAPI das novas entradas/saídas exportado.
- [x] Implementar login e consulta de cargas no Android 0.8.0 — contrato e falhas de sessão cobertos por 23 testes novos; ensaio em aparelho pendente.

TT-020 permanece parcial: compartilhamento entre empresas ainda não existe. TT-030/031/032 continuam abertos para ingestão, fila e consolidação de leituras reais; idempotência de cadastros não conclui ingestão. Evidências em [[ThermoTrace - Entrega servidor 0.3]].


## Android 0.8.0 — 12/09/2026

- [x] Tela Conta e cargas, pesquisa por documento/código e detalhe com faixa preservada.
- [x] Sessão cifrada, renovação serializada e tratamento de resultado incerto; implementação de Keystore aguarda ensaio no Android.
- [x] Fixture capturada da API 0.3 com empresa sintética; contrato interpretado pelo Android em teste JVM.
- [ ] Separar banco/fila local por empresa e usuário; nenhuma evidência do protótipo pode ser atribuída automaticamente ao cliente conectado.
- [ ] Associar carga e volume online à sessão NFC antes de permitir envio.

90 testes JVM passaram; APK debug e análise estática concluídos. TT-022 permanece parcial (convite/recuperação/MFA); TT-030/031/032 seguem abertos. Evidência: [[ThermoTrace - Entrega Android 0.8.0]].


## Servidor 0.4 — 12/09/2026

- [x] Receber declaração de ativação/checkpoint/final ligada a carga, volume, etiqueta, sessão, operador e aparelho.
- [x] Recibo atômico, idempotência HTTP e por evento; conflitos e duas conexões concorrentes testados.
- [x] Preservar checkpoint atrasado anterior ao fechamento; fechamento lógico não confirma STOP físico.
- [x] RLS operacional ampliada, autoria corrente na inserção e identidades/evidências protegidas no banco.
- [x] Cadeia de integridade local e verificador com teste de adulteração; consultas autenticadas auditadas.
- [x] Migração de clone 0.3 com registros sintéticos legados preservados; 126 testes passaram.
- [x] OpenAPI e exemplo HTTP sintético executado exportados.

TT-030 tem recepção bruta e autorização implementadas; catálogo via portal e validação/decodificação efetiva do SDK permanecem pendentes. TT-031 continua aberto para fila Android. TT-032 tem concorrência de recepção testada, mas falta comparar/consolidar amostras conflitantes entre aparelhos. TT-060 mantém pendente a âncora externa e proteção operacional de backups. TT-012 mantém pendente confirmação física de STOP e reuso seguro de etiqueta. Esses cartões amplos continuam abertos.

Evidência e limites: [[ThermoTrace - Entrega servidor 0.4]]. Projeto exclusivamente pessoal ThermoTrace.

## Android — correções de campo 12/09/2026 (tarde)

Origem: primeiro ensaio em aparelho físico. Evidência e detalhes em
[[ThermoTrace - Ensaio em aparelho 2026-09-12]].

- [x] Reivindicar a etiqueta na tela da remessa e na activity da câmera (reader mode passivo). Elimina a tela "Nova marca digitalizada" do sistema a partir da segunda coleta. Confirmado no aparelho.
- [x] Checkpoint e leitura final entram armados ao abrir a tela. Confirmado no aparelho: coleta de 6 registros sem tocar em botão.
- [x] Recarregar a remessa a cada retomada da tela, para a coleta confirmada aparecer ao voltar.
- [x] Digitação manual da nota fiscal, marcada como entrada manual e sem validação fiscal. Pendente de teste no aparelho.
- [x] Guarda de pré-condição da coleta automática: só arma com sessão ativa, etiqueta vinculada e monitoramento aberto.
- [x] Status da última coleta no cartão do volume — tipo, data/hora, temperatura instantânea e contagem de coletas. Pedido do usuário em 12/09; pendente de validação no aparelho.
- [ ] TT-005 reaberto com evidência de aparelho: coleta devolveu mínima −29,8 °C com a etiqueta sobre a mesa. A decodificação do app não calcula temperatura (`SessionDecoder` só valida o número vindo do SDK do fabricante), então a investigação começa no `nfcinstruct` e na comparação com o app do fabricante, usando `LeituraEntity.respostaBruta`. Não alterar fórmula antes dessa comparação.
- [ ] A confirmar: desvio de 232 s entre a leitura e o último ponto — relógio da etiqueta ou arredondamento do último slot.
- [ ] A confirmar: se o painel deve rearmar sozinho para uma segunda coleta seguida na mesma tela, hoje volta para "Pronto" após concluir.

Testes JVM: 97 passaram no build 11:23. Não reexecutados sobre os builds 11:38 e 12:12.

## Apresentação — gráfico e coleta (aberto em 12/09/2026)

Pedido do usuário: gráfico e tela de coleta mais claros e intuitivos. Proposta detalhada,
problemas com origem no código e a decisão pendente em
[[ThermoTrace - Proposta de gráfico e coleta]].

- [x] TT-042 P1 — FEITO em 14/09 (commit 82354f1): Gráfico: escala ancorada na faixa com marca de recorte, eixo Y rotulado, marcas de tempo intermediárias, excursão sombreada, limites rotulados, pontos fora da faixa em cor distinta, lacuna visível e leitura de topo com o último valor. Aceite: a faixa aprovada permanece legível mesmo com ponto extremo, e nenhum extremo é escondido.
- [ ] TT-043 **P0** — Tela de coleta. ELEVADO A P0 em 12/09/2026: o ensaio mostrou coletas baixadas e nunca registradas, com a etiqueta gravando e o app dizendo SEM LEITURA. É perda de evidência, não conforto. Evidência em [[ThermoTrace - Ensaio em aparelho 2026-09-12]]. Escopo: barra de ação fixa com o registrar sempre visível, estados nomeados no painel (aguardando, lendo, baixado, registrado), estado de sucesso explícito, "Verificar agora" como ação secundária. Aceite: nenhuma coleta bem-sucedida depende de rolagem para ser registrada.
- [x] TT-044 P0 de produto — DECIDIDO em 12/09/2026 pelo usuário: checkpoint registra automaticamente no bipe; ativação e leitura final seguem exigindo ato explícito. Registro em [[ThermoTrace - Decisões e pendências]] (D08). Implementado no build 13:51, pendente de validação no aparelho. Antes do piloto, a decisão ainda precisa do aval do responsável de qualidade.
- [ ] Unificar vocabulário: "check-in" do usuário x "Checkpoint" da tela x "Trajeto" do trilho.

Ordem proposta: TT-005 antes de TT-042. Melhorar a apresentação de um número que pode
estar errado só deixa o erro mais convincente.

## Transparência e localização (aberto em 12/09/2026)

Pedido do usuário e pesquisa técnica em [[ThermoTrace - Transparência da coleta e localização]].

- [ ] TT-045 P1 — Histórico completo de coletas no cartão do volume: tipo, data/hora, temperatura instantânea, registros baixados e veredito por coleta, com desvio de relógio e versão do decodificador num detalhe expansível. Só apresentação; dados já persistidos. Aceite: o laudo pode ser explicado meses depois sem abrir o banco.
- [x] TT-046 P1 — FEITO em 14/09 (commit 82354f1), por código próprio sobre o Canvas, sem biblioteca: Gráfico interativo: linha de leitura por toque/arraste com temperatura, horário e situação do ponto; seleção de janela de tempo. Depende do TT-042. Decisão tomada: código próprio sobre o Canvas atual, sem biblioteca de gráfico.
- [x] TT-047 P1 — FEITO em 14/09 (commit 188b620), pendente de ensaio em aparelho: Localização da coleta: gravar latitude, longitude, precisão, provedor e instante do fix na mesma transação da evidência; endereço apenas como enriquecimento posterior marcado como tal; coleta nunca espera o GPS; "sem localização" explícito. Aceite: nenhuma leitura é perdida ou atrasada por falta de fix, e nenhum ponto é apresentado sem precisão e idade.
- [x] TT-048 P0 de produto — DECIDIDO em 12/09/2026 pelo usuário: adicionar Google Play Services e usar `FusedLocationProviderClient.getCurrentLocation()`. Registro em [[ThermoTrace - Decisões e pendências]] (D07). Aparelhos sem serviços Google ficam sem localização e o app deve operar inteiro nessa condição.
- [ ] TT-049 — Divulgação e privacidade da localização: é dado pessoal do operador. Entra na política de privacidade e nos campos protegidos contra alteração.
- [ ] A confirmar antes de qualquer adoção de biblioteca de gráfico: suporte de Vico a marcador por toque, zoom/pan e API mínima.

## TT-005 — em andamento desde 12/09/2026

Detalhes, formato do cabeçalho da etiqueta e o experimento do Diagnóstico em
[[ThermoTrace - TT-005 reconciliação cabeçalho x série]].

- [x] Confrontar a série decodificada com os campos que a própria etiqueta calcula (mínima/máxima registradas e contagem de pontos fora da faixa). `Reconciliacao` em `SessionDecoder`, exibida na tela de leitura só quando há divergência. Sem migração de banco: é derivada da evidência bruta imutável. Testes em `ReconciliacaoTest` (5 casos), pendentes de execução.
- [ ] Ler na tela Diagnóstico, na etiqueta 1, os campos "Mínima registrada", "Máxima registrada" e "Pontos abaixo / acima", e anotar na nota do TT-005. É o que separa erro de decodificação da série de problema anterior a ela.
- [ ] Refazer uma coleta com o build novo e verificar se o aviso de não reconciliado aparece.
- [ ] Comparar a mesma etiqueta no app do fabricante e o termômetro ao vivo contra termômetro aferido.
- [ ] Só depois disso, decidir qualquer alteração de fórmula ou de decodificação.

## Linha do tempo e coletas perdidas (12/09/2026, 13:30)

- [x] TT-050b — Linha do tempo da remessa passa a mostrar custódia E coletas no mesmo eixo cronológico, com tipo, volume, registros baixados e temperatura por coleta; cor distingue custódia, conforme e excursão; seção explica o vazio em vez de desaparecer. Build 13:38.
- [x] TT-043 P0 — RESOLVIDO no build 13:43: barra de ação fixa no rodapé com o registrar sempre visível e aviso em cor de erro; guarda de saída com confirmação explícita (registrar ou descartar) no botão de voltar e no gesto do sistema; estados do painel nomeados, fim do "Pronto" ambíguo. Não decide o TT-044 — o registro segue sendo ato do operador. Pendente de validação no aparelho.
- [ ] Verificar se as coletas de 12/09 têm `respostaBruta` guardada. Se não tiverem, o TT-005 precisa de uma coleta nova registrada até o fim para ter payload a analisar.

## Hospedagem (aberto em 12/09/2026)

Pesquisa de preços, plano em duas fases e a decisão de residência de dados em
[[ThermoTrace - Hospedagem e custos]].

- [ ] TT-064 P0 — Subir homologação com dado fictício: contêiner da API, Postgres gerenciado, domínio e HTTPS. Faixa pesquisada de US$ 10 a 25/mês. Aceite: o app entra em Conta e cargas contra endereço real e consulta uma carga preparada pela API.
- [ ] TT-065 P0 — Duas empresas fictícias em homologação e teste de isolamento entre elas antes de qualquer dado real.
- [ ] TT-066 P0 — Backup com restauração medida em homologação, antes de virar compromisso de RPO/RTO.
- [ ] TT-067 P0 de produto — Decidir se os dados de produção precisam ficar em região no Brasil. Decisão de contrato e exposição regulatória, não técnica.
- [ ] A confirmar no provedor: região brasileira em Fly.io, Supabase e Neon. AWS, Google Cloud e Azure têm região no Brasil.
- [ ] Produção fica para depois de existir cliente piloto. Contratar antes é pagar ociosidade e escolher região e retenção no escuro.

## Triagem para rodar na semana (12/09/2026)

Triagem completa, com o que impede liberação e o que foi feito, em
[[ThermoTrace - Triagem para rodar na semana]].

- [x] TT-051b — Evidência bruta e confronto na exportação: a aba Auditoria passou a levar mínima/máxima declaradas pela etiqueta, contagens de pontos fora da faixa, coluna "Reconciliado" e a resposta bruta inteira. Antes só os hashes iam, o que provava integridade mas não permitia redecodificar. Torna o TT-005 investigável a partir do Excel.
- [x] TT-052b — Removido cast morto `context as ComponentActivity` na tela de leitura: podia lançar ClassCastException sem entregar nada.
- [x] TT-053b P0 — RESOLVIDO no build 14:32. Defeito encontrado na revisão: a tela formal de leitura final usava `Download`, que só baixa, em vez de `Encerrar`, que baixa e depois envia o STOP — a remessa era encerrada e a etiqueta continuava gravando, sem aviso. Agora usa `Encerrar`, distingue os três estados do STOP na tela e oferece "Parar registro" quando ele não se confirma. Atende ao aceite do TT-012 na tela de leitura. Pendente de ensaio contra etiqueta real.
- [x] TT-054b P1 — FEITO em 14/09 (commit 6ee0cab): Aviso de evidência não exportada: com um só aparelho e `allowBackup=false`, o app deve dizer quando há coleta que nunca saiu do celular.
- [ ] Rodar `:app:testDebugUnitTest`. 97 passaram no build 11:23; desde então houve cinco casos novos e várias mudanças sem teste executado. Menor esforço, maior retorno em confiança.


## Revisão integrada — Android 0.8.2 (12/09/2026)

Evidências e roteiro: [[ThermoTrace - Revisão de campo Android 0.8.2]].

- [x] Reexecutar a suíte sobre o código atualizado: 107 testes JVM, sem falhas/ignorados; APK 0.8.2-debug, versionCode 14, 0 erros de análise estática. Instalação e abertura no emulador verificadas.
- [x] Limpar sucesso anterior ao iniciar outra coleta; tratar erro de persistência com opção de repetir; proteger saída durante gravação; transação compartilhada de histórico/fila/ocorrências no FluxoRapido.
- [x] Corrigir ordem da leitura final: download ao abrir; confirmação explícita salva antes de solicitar STOP; resultado da parada visível no rodapé e repetição sem nova leitura final.
- [ ] Reteste de checkpoint e final/STOP no A57: usuário relatou persistência dos problemas nos builds anteriores. Não marcar TT-043/TT-012 homologados apenas por testes JVM.
- [ ] Unificar reconciliação da tela e Excel (extremos e contagens); tratar ausência de cabeçalho como não avaliável.
- [ ] Persistir evento próprio de tentativa/resultado de STOP e validar reuso seguro em bancada.

Prioridade atual: checkpoint e leitura final no aparelho, junto da coleta de evidência para TT-005. D07/localização permanece aprovada e pendente. TT-020/031/032, hospedagem e portais continuam abertos. A revisão não mudou fórmula de temperatura nem declarou os −29,8 °C corrigidos.


## Execução — histórico e auditoria 0.8.3

- [x] TT-045 — Implementar lista das coletas da sessão no volume/relatório, com data/hora, temperatura do bipe, registros baixados, faixa e detalhes de evidência. Aceite visual no aparelho ainda pendente.
- [x] Unificar reconciliação de tela e Excel, incluindo extremos e contagens; dados ausentes/inválidos ficam não avaliáveis. Resultado geral pendente quando qualquer coleta exige conferência.
- [x] Exportar campos originais ordenados e reconstruíveis, com fragmentação de campos longos. Teste gera o XLSX completo e reconstrói o bruto.
- [x] 117 testes JVM sem falhas/ignorados; APK 0.8.3-debug, código 15; instalação/abertura no emulador verificadas.
- [ ] Validar o histórico preenchido e a exportação no A57; repetir checkpoint e final/STOP.

[[ThermoTrace - Histórico e auditoria Android 0.8.3]]. TT-005 continua aberto para explicar a leitura −29,8 °C; nenhuma fórmula foi alterada. TT-041 permanece parcial: isso não entrega PDF/PNG, download central autorizado ou consolidação entre aparelhos. TT-047/D07, banco local por cliente e fila API 0.4 continuam pendentes.


## Duas frentes — portal e GitHub, 13/09/2026

[[ThermoTrace - Portal e próximas entregas]] detalha WEB-01 a WEB-12, dependências, responsáveis e critérios de aceite. WEB-01/02/06/07/08 implementados; WEB-03/04/05/09/10/11/12 abertos. Isso inicia os portais, sem encerrar TT-020/031/032 ou o teste físico.

TT-010: remoto privado já criado pelo usuário e origin/main conferido. Rotina permanente de commits com motivo em CONTRIBUTING.md; notas do projeto copiadas para docs/projeto. Aceite de clone limpo do Android ainda aberto. TT-064: serviço Render aparece publicado; endereço público, vínculo DNS/HTTPS e fluxo completo de homologação ainda pendentes.

Cliente = dono da carga; contratante = transportadora. Nenhuma autorização entre empresas será inferida por NF, destinatário ou nome da empresa. O detalhamento da entrega e dos testes fica em [[ThermoTrace - Registro de entregas GitHub]].


## Sessão de 14/09/2026 — evidência que sobrevive à saída da tela

Duas mudanças da mesma família: verificação que o app fazia e esquecia passou a
ser registro consultável depois. Detalhe em
[[ThermoTrace - Fechamento do ciclo e STOP]].

- [x] TT-055b P1 — Commit `1b76ec9`. A linha do tempo da remessa mostra **qual**
  coleta não foi conferida e **por quê**. O cartão do volume já contava
  "N coleta(s) precisam de conferência" e mandava consultar o histórico abaixo,
  mas o histórico não dizia nada — quem assina o laudo via o número e não tinha
  como chegar na coleta. A conferência é recalculada de `respostaBruta`, que é
  imutável, então vale também para leitura gravada antes de a conferência existir,
  sem migração e sem reescrever evidência. Mesmo critério do contador (`!conferida`),
  para os dois números nunca se contradizerem.
- [x] TT-055 P0 — Commit `65431d9`. Confirmação do STOP virou coluna da sessão
  (`loggerParadoEmMillis`, migração 4 → 5). Antes, "encerrada" só dizia que o
  celular gravou o histórico; se o STOP falhasse, a etiqueta seguia gravando e
  nada contradizia a palavra ENCERRADO depois que o operador saía da tela de
  coleta. Agora a tela da remessa avisa em vermelho e a aba Auditoria separa
  "Sessão encerrada em" de "STOP confirmado pela etiqueta em". Regra única em
  `FechamentoDoCiclo`, no domínio, com teste. Fecha o item 1 do bloco C da
  triagem e a primeira metade do aceite do TT-012.
- [ ] TT-056 P1 — **Nenhuma migração de banco tem teste.** `exportSchema = false`
  deixa `MigrationTestHelper` sem referência, e 1→2, 2→3, 3→4 e 4→5 foram escritas
  à mão. Como `fallbackToDestructiveMigration` está proibido de propósito, migração
  errada não perde dado: trava o app na abertura, com a evidência presa dentro.
  Ligar `exportSchema`, versionar os JSONs e testar daqui em diante. Ressalva
  honesta: isso só exporta a v5, então 4→5 continua sem teste retroativo — o valor
  é para as próximas.
- [ ] TT-057 P2 — Reuso seguro da etiqueta depois do STOP confirmado (segunda
  metade do aceite do TT-012). Depende de bancada, não de código.
- [x] TT-047b P1 — Commit `ca28bde`. **Onde foi a coleta**, no cartão do volume e na
  linha do tempo. A coordenada já era gravada desde `188b620`, mas só aparecia no
  instante do bipe — mesmo defeito de família das duas linhas acima. Pedido direto do
  usuário em 13/09: "pode trazer na tela a localização exata que foi feita a última
  coleta". Detalhe que importa: a idade do fix é medida contra o instante do **bipe**,
  não contra agora — no histórico, "fix de 4 meses antes" não responderia nada; a
  pergunta é "o fix era do momento da coleta?". `fixDaColeta()` exige coordenada,
  provedor e instante juntos, porque meia evidência vira evidência inventada na tela.
  Sem localização a linha continua aparecendo e diz que não houve.
- [ ] TT-047c P2 — Endereço legível e aviso de privacidade sobre a localização do
  operador. Continuam fora de escopo: endereço exige rede, e doca e câmara fria são
  justamente onde não há.
- [ ] Ensaiar em etiqueta real tudo que saiu em 14/09: gráfico, localização,
  aviso de exportação, alerta de conferência e confirmação de STOP. **Nada disso
  passou por hardware** — só compilou e instalou no emulador.

## Revisão integrada — Android 0.8.5, 14/09/2026

Este bloco atualiza os estados anteriores sem apagar o histórico do Opus.

- [x] Isolamento local por servidor/empresa/operador implementado e ensaiado no emulador, incluindo reabertura, fila e preferências.
- [x] TT-055: confirmação do primeiro STOP atômica e erro de persistência visível; atalho Home usa fluxo que grava a leitura antes de solicitar STOP. Aceite físico de TT-012 continua pendente.
- [x] Correção do aviso de cópia: destino escolhido, escrita fechada e conteúdo relido/conferido antes de marcar. Nova coluna não herda confirmações inseguras antigas; arquivo no celular não comprova backup remoto.
- [x] TT-056 parcial: exportSchema ligado, schemas 5/6 versionados e migrações 2/3/4/5→6 ensaiadas com fixtures sintéticas; V2–V4 reconstruídas, V5 exportada.
- [ ] TT-056 restante: migração 1→2 e atualização de banco físico real com histórico preservado.
- [ ] Retestar NFC checkpoint/final/STOP, exportação pelo seletor de arquivos e localização no A57. TT-005 e TT-057 continuam abertos.
- [ ] Próxima implementação P0: fila autenticada da API 0.4, vínculo de carga/volume e recibos idempotentes. Depois, temperaturas/gráficos centrais e compartilhamento explícito por contrato.
- [ ] Detalhar processos de app, site e empresa a partir de [[ThermoTrace - Processos do produto e da empresa]]. Avaliar contratações por dependência comprovada em [[ThermoTrace - Contratações e custos a acompanhar]].

Evidência: 135 testes JVM, build/lint sem erros e instrumentação no emulador. Detalhe em [[ThermoTrace - Revisão integrada Android 0.8.5]].

## Execução — envio de coletas 0.8.6, 14/09/2026

- [x] Primeira integração Android/API 0.4: escolha explícita de carga e volume, validação de faixa/intervalo/UID, cadastro da instalação, fila preservada e recibos transacionais.
- [x] Capturar resposta original do START nas novas ativações; proteger o legado sem inventar dados.
- [x] Testar perda de resposta, conflito, recibo errado, falha SQLite, reabertura e troca de conta. Conferir também o contrato Kotlin contra a API/PostgreSQL sintéticos.
- [x] TT-056 parcial ampliado: migrações sintéticas 2/3/4/5/6→7; schema 7 exportado. 1→2 e banco físico real seguem pendentes.
- [ ] Validar fluxo real com conta, carga/volume, etiqueta de catálogo e endereço público HTTPS do Render.
- [ ] Automatizar envio em segundo plano; continuar sessão entre aparelhos; tratar vínculo errado sem sobrescrever evidência; importação auditada de legado se necessária.
- [ ] Consolidar temperaturas/gráficos no servidor, enviar localização/STOP e disponibilizar relatórios centrais.
- [ ] Reteste NFC e TT-005/TT-057 continuam abertos. Nenhum teste automatizado desta etapa equivale a bancada.

Detalhes e roteiro: [[ThermoTrace - Envio de coletas Android 0.8.6]].


## Atualização — Portal 0.2, 14/09/2026

- [x] Gráficos e exportações centrais por coleta, sem consolidar snapshots.
- [x] Corrigir limite do histórico no Android/API e validar 648 medições.
- [ ] WEB-10: compartilhar uma carga por autorização explícita, com escopo e revogação.
- [ ] Conferir domínio/Render e fluxo integrado de homologação.
- [ ] Consolidar viagem e excursões, completar administração e processos de atendimento.

[[ThermoTrace - Portal 0.2 e gráficos por coleta]].
