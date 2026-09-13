---
tipo: proposta
projeto: ThermoTrace
contexto: pessoal-independente
status: rascunho-aguardando-decisao
atualizado: 2026-09-12
---

# Hospedagem e custos

Origem: pedido do usuário em 12/09/2026 — quer contratar servidor e sair do local o quanto
antes. Complementa [[ThermoTrace - Publicação e operação]], que já define o caminho até
disponibilizar e diz, com razão, que não há estimativa financeira confiável sem as entradas
de volume, retenção e região. Esta nota separa o que dá para fazer **agora** do que depende
dessas entradas.

Pesquisa de preços em 12/09/2026; fontes no fim. Preços de plataforma mudam com frequência
— confirmar no provedor antes de contratar.

## O destravamento: homologação não precisa das decisões difíceis

A tela **Conta e cargas** do Android 0.8.0 é a única parte do app que hoje **não pode ser
testada de forma alguma**, porque exige HTTPS e não existe endereço publicado. É também o
passo 1 do caminho já escrito em [[ThermoTrace - Publicação e operação]].

E homologação, por definição do próprio documento, roda com **dados fictícios**. Ou seja:
região de hospedagem, residência de dados, LGPD de titular real, retenção de evidência e
volume de cargas **não são pré-requisito para subir homologação**. São pré-requisito para
produção. Dá para ter endereço HTTPS funcionando esta semana sem decidir nada disso.

## Fase 1 — homologação, agora

O backend já é conteinerizado (`backend/Dockerfile`, `backend/docker-compose.yml`), o que
elimina o trabalho de empacotamento. O que falta é um lugar para rodar o contêiner, um
Postgres gerenciado e um domínio com certificado.

Ordem de grandeza pesquisada em 12/09/2026, por mês:

1. Contêiner da API: Render a partir de ~US$ 7 no plano Starter; Fly.io a partir de ~US$ 2–7;
   Railway sem plano gratuito, ~US$ 5–15 com o crédito inicial de US$ 5. O gratuito do Render
   hiberna após ~15 min de inatividade e volta com partida a frio — ruim para demonstração,
   aceitável para ensaio.
2. Postgres gerenciado: faixas de entrada entre US$ 5 e US$ 25 — Heroku Essential-0 e
   PlanetScale a US$ 5, AWS RDS `db.t4g.micro` a ~US$ 12, DigitalOcean a US$ 15, Supabase Pro
   a US$ 25. Camadas gratuitas existem com 0,5 GiB (Neon, Supabase) e servem a homologação.
3. Domínio: dezenas de reais por ano. Certificado: gratuito via Let's Encrypt, e as
   plataformas acima emitem sozinhas.

Ou seja, **homologação inteira na faixa de US$ 10 a 25 por mês**, e possivelmente menos
usando camada gratuita de banco com dado fictício. Não é o gasto que precisa de planilha.

Antes de chamar homologação de pronta, valem os itens já escritos em
[[ThermoTrace - Publicação e operação]]: migrações aplicadas com backup, duas empresas
fictícias com teste de isolamento, e HTTPS com validação normal de certificado — o app
recusa HTTP por construção (`usesCleartextTraffic=false`).

## Fase 2 — produção, e o que ela exige antes

Três coisas que não são opcionais e que mudam a escolha de provedor:

1. **Região e residência dos dados.** Clientes de saúde no Brasil, evidência de auditoria,
   LGPD. Se o dado tem de ficar no Brasil, sobram os provedores com região em São Paulo, e
   boa parte das plataformas baratas sai da lista. Verifiquei que AWS, Google Cloud e Azure
   têm região no Brasil; para Fly.io, Supabase e Neon a região brasileira precisa ser
   confirmada no provedor antes de decidir — não confirmei na pesquisa.
2. **Retenção da evidência.** Aqui está a boa notícia do dimensionamento: uma carga de 648
   registros são dezenas de kilobytes, evidência bruta incluída. Mil cargas por mês ficam na
   ordem de algumas dezenas de megabytes por mês, sob 1 GB por ano. **Armazenamento não é o
   custo do ThermoTrace**; o custo é contêiner e banco ligados o tempo todo. Isso torna a
   retenção longa, que a auditoria exige, barata — o que é raro e vale registrar.
3. **Backup com restauração testada.** O documento de operação já fixa metas provisórias de
   RPO 24 h e RTO 8 h, e diz que só viram compromisso depois de medir uma restauração. O
   plano de banco precisa ter PITR na camada contratada, não na de cima.

Pendências financeiras que seguem abertas de [[ThermoTrace - Decisões e pendências]]:
quantidade de clientes, usuários, cargas/mês e etiquetas/carga; orçamento; e titularidade de
domínio e contas. Sem elas não há estimativa de produção, só de homologação.

## Recomendação

Subir homologação agora, com dado fictício, na configuração mais barata que não hiberne, e
tratar a escolha de provedor de produção como decisão separada, tomada depois do primeiro
cliente piloto existir. Contratar produção antes de ter cliente é pagar por capacidade
ociosa e, pior, escolher região e retenção no escuro.

## Decisão pendente

Os dados de produção precisam ficar em região no Brasil? A resposta elimina ou mantém
metade das opções e é a primeira coisa a fixar na Fase 2. Não é decisão técnica: é de
contrato com cliente e de exposição regulatória. Recomendo tratar com o responsável de
qualidade e, se houver cliente de saúde, confirmar o que o contrato dele exige.

## Fontes

1. Render x Railway x Fly.io, preços 2026: https://dev.to/pavel-hostim/render-vs-railway-vs-flyio-pricing-compared-2026-2e5p (consultado 2026-09-12; o próprio autor anota preços de junho/2026)
2. Comparação de Postgres gerenciado, preços 2026: https://www.bytebase.com/blog/postgres-hosting-options-pricing-comparison/ (consultado 2026-09-12)
3. Regras de publicação em loja, Play Console: https://support.google.com/googleplay/android-developer/answer/9859152
