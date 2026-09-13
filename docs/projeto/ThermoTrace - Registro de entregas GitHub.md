---
tipo: registro-de-entregas
projeto: ThermoTrace
contexto: pessoal-independente
atualizado: 2026-09-13
---

# Registro de entregas GitHub

Repositório: https://github.com/felipeguerreiro7/thermotrace — branch main.

## Histórico confirmado antes desta entrega

- `867a107` — Estado inicial sob versionamento.
- `3dfcf39` — Preparar backend para deploy no Render.
- `7b9cfac` — Reaproveitar banco existente no Render. Esse era o commit remoto confirmado ao iniciar a entrega Portal 0.1.

## 13/09/2026 — organização e Portal 0.1

Motivo: o usuário definiu transportadora como contratante e dono da carga como cliente, comprou domínio e conectou o GitHub ao Render. A primeira área web precisa usar as contas e evidências reais sem ampliar o acesso entre empresas.

Frente de organização: inventário confirmado, plano com dependências/aceites, rotina de commits e sincronização das notas pessoais para `docs/projeto`.

Frente de código: portal no mesmo serviço da API, login e renovação em memória, áreas por empresa/papel, cargas/documentos/volumes, comprovantes e integridade, equipe/auditoria e lista administrativa de empresas. Sem alteração de migração, assinatura Android ou recursos do Render.

Validação concluída: suíte completa com 140 testes backend/PostgreSQL passou (zero falhas/erros/ignorados), migrações sem divergência. Após acrescentar redirecionamento da raiz HTML preservando a resposta JSON, os 15 testes específicos do portal passaram; 8 testes JavaScript passaram novamente após o limite de espera de conexão. Cobertura total: 141 casos backend distintos e 8 JavaScript; não houve repetição integral desnecessária após o ajuste restrito à raiz. HTTP local do portal e assets retornou 200; prévia local aberta. Não houve ensaio visual automatizado nem teste NFC nesta entrega.

Código enviado e confirmado em origin/main em 13/09/2026: [b20678f](https://github.com/felipeguerreiro7/thermotrace/commit/b20678ff7bae0947a69e048f017c801c5921d17d). Título: “feat(portal): iniciar acesso de clientes, transportadoras e equipe ThermoTrace”. Mensagem explica escopo, motivo, testes e limitações. Push concluído e SHA remoto igual ao local no momento da verificação.

A documentação é enviada em commit separado, com a rotina de trabalho e as 31 notas do projeto. Seu histórico verificável está em [commits da main](https://github.com/felipeguerreiro7/thermotrace/commits/main/). O commit documental usa [skip render] para evitar republicação sem mudança de aplicativo; o commit do portal foi enviado primeiro, separadamente.

Estado Render da entrega: ainda não verificado. O print do usuário é anterior ao envio deste código. Confirmar o commit b20678f na página de deploys e abrir /portal na URL pública real. Domínio/HTTPS continuam pendentes. Nenhuma configuração de infraestrutura foi alterada.

Pendências de produto: [[ThermoTrace - Portal e próximas entregas]]. Publicação/domínio: [[ThermoTrace - Infraestrutura confirmada]].
