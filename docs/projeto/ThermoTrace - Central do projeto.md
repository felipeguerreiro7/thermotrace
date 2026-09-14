---
tipo: projeto
projeto: ThermoTrace
contexto: pessoal-independente
status: em-implementacao
atualizado: 2026-09-13
---

# ThermoTrace — retomada do projeto

Atualização: 2026-09-12. Responsável pelo produto: usuário / ThermoTrace. Responsáveis técnicos e de qualidade: a designar.

## Resultado desejado
Cliente entra na própria conta, identifica uma carga, aproxima o celular da etiqueta para iniciar e acompanhar o registro, consulta o histórico e, ao terminar, baixa gráfico e relatório. Equipe ThermoTrace administra clientes, dispositivos, suporte e auditoria com permissões específicas.

## Navegação
- [[ThermoTrace - Diagnóstico da base]]
- [[ThermoTrace - Produto e fluxo de campo]]
- [[ThermoTrace - Banco e acesso dos clientes]]
- [[ThermoTrace - Segurança e auditoria]]
- [[ThermoTrace - Gráficos e relatórios]]
- [[ThermoTrace - Plano de ação e backlog]]
- [[ThermoTrace - Publicação e operação]]
- [[ThermoTrace - Hospedagem e custos]]
- [[ThermoTrace - Roteiro de contratação e entrega da homologação]]
- [[ThermoTrace - Triagem para rodar na semana]]
- [[ThermoTrace - Decisões e pendências]]
- [[ThermoTrace - Kit inicial de implementação]]
- [[ThermoTrace - Ensaio em aparelho 2026-09-12]]
- [[ThermoTrace - Proposta de gráfico e coleta]]
- [[ThermoTrace - Transparência da coleta e localização]]
- [[ThermoTrace - Fechamento do ciclo e STOP]]
- [[ThermoTrace - TT-005 reconciliação cabeçalho x série]]

## Prioridade imediata

1. Separar histórico/fila local por empresa e operador; associar cargas/volumes e integrar os recibos da API 0.4.
2. Implementar decodificação/consolidação no servidor, gráficos/exportações e portais sobre evidências preservadas.
3. Executar bancada comparativa quando o hardware estiver disponível, antes de liberar uso operacional.

## Status real

Android 0.8.0-debug disponível: conta online, consulta de cargas e critério preservado; 90 testes JVM passaram. Servidor 0.4 recebe evidências de início/checkpoint/final por carga e volume, com autoria, idempotência e cadeia local de integridade; 126 testes passaram. Migração de legado sintético conferida, sem alterar os valores antigos.

RLS cobre também aparelhos, etiquetas, vínculos, sessões, leituras, séries e excursões. Consultas de evidência e verificações são auditadas. O encerramento lógico não confirma STOP nem libera a etiqueta. Simulações estão identificadas. O banco existente não foi migrado e não há publicação ou homologação física.

A ligação com o banco e a fila local do Android permanece pendente. O servidor ainda recebe o bruto sem decodificar/consolidar temperaturas; não gera laudo central nem libera cargas.

- [[ThermoTrace - Entrega Android 0.8.0]]
- [[ThermoTrace - Entrega servidor 0.4]]
- [[ThermoTrace - Entrega Android 0.7.3]] — anterior
- [[ThermoTrace - Entrega servidor 0.2]] — anterior
- [[ThermoTrace - Entrega servidor 0.3]] — anterior
- [[ThermoTrace - Próximo ciclo sem hardware]]

## Critério para chamar de piloto pronto
Dois clientes isolados, operadores identificados, leitura real validada contra o fornecedor, sincronização sem perda/duplicação, fechamento explícito, gráfico e relatório reproduzíveis, restauração de backup comprovada e acesso da ThermoTrace auditado.

## Atualização confirmada pelo usuário
O aplicativo apresenta muitos erros, leitura NFC intermitente e gráficos incorretos. Primeiros clientes: parceiros da área de saúde. Prioridade P0: confiabilidade de leitura e fidelidade dos gráficos, antes de disponibilizar o produto para uso operacional.

## Regulamentação e operação rápida

- [[ThermoTrace - Regulamentação e faixas térmicas]]
- [[ThermoTrace - Operação rápida com perfis]]
- [[ThermoTrace - Catálogo de perfis térmicos]]

## Natureza do projeto

Confirmado pelo usuário: ThermoTrace é um projeto pessoal independente, sem vínculo com os projetos do trabalho. Seus clientes, documentos, contas e decisões pertencem exclusivamente à ThermoTrace. Não classificar como projeto profissional do empregador nem vincular a clientes ou materiais do trabalho.


## Estado mais recente — 0.8.2

[[ThermoTrace - Revisão de campo Android 0.8.2]]: revisadas as mudanças do usuário; checkpoint automático e final com gravação antes do STOP protegidos. 107 testes passaram; instalação/abertura no emulador verificadas. Reteste físico continua aberto após novo relato de falha. Priorizar esses dois fluxos e TT-005; não aguardar localização ou portal para corrigir coleta. Os status históricos acima não representam homologação desta versão.


## Entrega mais recente — Android 0.8.3

[[ThermoTrace - Histórico e auditoria Android 0.8.3]]: histórico das coletas da sessão, conferência única para tela/Excel, três estados de conferência e evidência bruta exportada em campos reconstruíveis. 117 testes passaram; atualização/abertura no emulador verificadas. TT-005 e reteste NFC seguem abertos; temperatura não foi corrigida por suposição. Próxima etapa independente de bancada: isolamento local e fila vinculada à conta/API 0.4. D07 permanece aprovada e pendente.


## Estado vigente — portal e infraestrutura, 13/09/2026

[[ThermoTrace - Portal e próximas entregas]] organiza as duas frentes atuais. Usuário confirmou: contratante é a transportadora; cliente é o dono da carga; staff é a ThermoTrace. Domínio comprado **thermotrace.com.br**, repositório GitHub **felipeguerreiro7/thermotrace**. Print confirma serviço **thermotrace-api** como Deployed no Blueprint **thermotrace-homologacao**, Docker/Oregon/main. Domínio/HTTPS e deploy deste código ainda precisam de conferência.

Portal 0.1 implementado junto da API: login real, cargas/documentos/volumes/comprovantes, equipe/auditoria por permissão e lista de empresas para staff. Não dá acesso automático entre empresas e não apresenta gráfico central sem temperaturas consolidadas. [[ThermoTrace - Infraestrutura confirmada]] e [[ThermoTrace - Registro de entregas GitHub]].

Android permanece 0.8.3, com reteste físico de checkpoint/final e TT-005 abertos. Próximo desenvolvimento independente da bancada: isolamento local e fila integrada; depois, compartilhamento autorizado dono/transportadora e gráficos/exportações. Decisões e status mais antigos acima são registros históricos.

## Estado atual — revisão integrada de 14/09/2026

Android 0.8.5-debug, código 17: melhorias do Opus preservadas e revisão concluída. 135 testes JVM passaram; isolamento local e migrações sintéticas 2/3/4/5→6 passaram no emulador. O isolamento por conta está implementado; a fila autenticada com recibos da API 0.4 é a próxima prioridade. Leitura final/STOP e exportação receberam correções, ainda sujeitos a reteste físico.

- [[ThermoTrace - Revisão integrada Android 0.8.5]] — alterações, evidências e limites.
- [[ThermoTrace - Contratações e custos a acompanhar]] — avisar cada necessidade com custo verificado antes da contratação; nenhuma despesa nova nesta rodada.
- [[ThermoTrace - Processos do produto e da empresa]] — estrutura inicial para detalhar após estabilização e integração; não tratar como procedimento operacional homologado.

Portal 0.1 e API 0.4 permanecem. Cliente é dono da carga; contratante é transportadora. Código no GitHub não equivale a homologação física ou publicação confirmada no Render.

## Estado vigente — envio com recibos, Android 0.8.6, 14/09/2026

[[ThermoTrace - Envio de coletas Android 0.8.6]]: envio manual por sessão para a API 0.4, vínculo explícito de carga/volume e recibos persistidos. Novas ativações preservam a resposta original de START. Ativações antigas sem essa evidência continuam locais; nenhuma atribuição retroativa de autoria. 149 testes JVM, ensaios no emulador e 142 testes API passaram. Reteste físico e Render continuam pendentes.

Próximas prioridades: homologar cadastro/catálogo e percurso celular→servidor; consolidar temperaturas/gráficos centrais; automatizar fila com contexto preservado; permitir continuidade entre aparelhos e acesso explícito entre dono da carga e transportadora. Processos e custos permanecem nas notas próprias, sem contratação adicional nesta entrega.
