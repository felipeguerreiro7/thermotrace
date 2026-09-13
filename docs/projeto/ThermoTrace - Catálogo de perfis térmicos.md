---
tipo: pesquisa-e-especificacao
projeto: ThermoTrace
status: em-validacao
atualizado: 2026-09-11
---

# Catálogo de perfis — estrutura para implementação

Arquivo entregue: `ThermoTrace - Catalogo inicial de perfis.json`. Modelos em rascunho; nenhum perfil habilitado em produção. Fontes, data da pesquisa e condições viajam com o cadastro. Valores null significam “não definido”, nunca zero ou ausência de restrição.

Campos essenciais: cliente, produto/GTIN/registro/apresentação, condição e finalidade, perfil/versão, limites/unidade, tempo máximo de transporte, início do prazo e histórico cumulativo, restrições, embalagem/rota qualificadas, intervalo de amostragem, calibração/hardware, evidência documental e aprovação do responsável.

O catálogo distingue norma de transporte, orientação técnica, conservação de bula e modelo sem especificação. Perfis base de medicamento requerem documento e aprovação de transporte antes de ativar. Aprovação não é solicitada a cada bip; é etapa única de preparação do catálogo, repetida apenas em mudança relevante.

## Integração proposta
1. Cadastrar rascunho e evidência; verificar produto e fonte.
2. Completar tempo, embalagem, hardware e regra de decisão.
3. Responsável aprova uma versão para um cliente e escopo de produto.
4. Associar produto/condição ao perfil e distribuir catálogo assinado/versionado ao app.
5. Ao iniciar sessão, persistir snapshot do perfil; relatório referencia a mesma versão.

Alteração de faixa exige nova versão. Vencimento da fonte aciona revisão, sem invalidar automaticamente a história. Fonte externa pode mudar no mesmo URL: salvar versão/data e cópia documental autorizada com hash no sistema de evidências.

## Backlog complementar
- [ ] TT-070 P0 — Cadastro versionado de produto + condição + perfil + aprovação.
- [ ] TT-071 P0 — Importar catálogo do primeiro parceiro e verificar bula/POP de cada SKU.
- [ ] TT-072 P0 — Validar hardware para cada faixa aprovada.
- [ ] TT-073 P1 — Leitura de carga preenche perfil e favoritos por empresa.
- [ ] TT-074 P0 — Regressão: limites, unidades, condição aberta/fechada e conflitos de carga mista.
- [ ] TT-075 P0 — Excursão, segregação/avaliação e liberação fundamentada como estados separados.
- [ ] TT-076 P1 — Revisão periódica das fontes com responsável e data, sem trocar perfis silenciosamente.

Confiabilidade do NFC e correção dos gráficos continuam sendo bloqueadores do piloto de saúde.

## Entrada no mercado sem lista prévia de produtos
Confirmado pelo usuário em 2026-09-11: produtos serão descobertos com os primeiros clientes. TT-071 será realizado durante a implantação de cada cliente; não bloqueia a construção de TT-070 e do fluxo rápido. Biblioteca inicial apresenta modelos 2–8 °C, 15–30 °C e modelos específicos de hemoterapia, além de perfil personalizado, sempre com escopo explícito. Nenhum modelo fica automaticamente autorizado para qualquer produto. Cadastro de produto desconhecido pode ser salvo como rascunho; a ativação operacional depende da especificação e validação anteriores.
