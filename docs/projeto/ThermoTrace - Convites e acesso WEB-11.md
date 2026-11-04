---
tipo: entrega
projeto: ThermoTrace
contexto: pessoal-independente
atualizado: 2026-11-04
---

# Convites e acesso pelo portal · WEB-11 (parte 1) · API 0.7

Entrega de 04/11/2026, logo depois do WEB-10. Motivo: com o compartilhamento pronto, o gargalo passou a ser cadastrar a transportadora e a equipe dela. Antes, isso só era possível pela API e com a senha definida por quem cadastrava.

## Como funciona

Ninguém define a senha de outra pessoa e nenhum e-mail é enviado, então não houve contratação nova (ver [[ThermoTrace - Contratações e custos a acompanhar]]):

1. Quem cadastra gera um **código de acesso de uso único** (formato `XXXX-XXXX-XXXX`), que aparece **uma vez** na tela.
2. Entrega o código à pessoa por outro canal: pessoalmente ou por telefone, não junto do e-mail.
3. A pessoa abre o portal, escolhe **"Tenho um código de acesso"**, informa e-mail + código e cria a própria senha.

- **Validade:** convite vale 72 horas; redefinição de senha, 24 horas. Gerar outro código invalida o anterior, e o código vale só para o e-mail convidado.
- **Senha esquecida:** o gestor gera um código novo para um operador. Para um gestor, quem gera é a equipe ThermoTrace, e a ação aparece na auditoria do próprio cliente.
- **Efeito da senha nova:** encerra todas as sessões abertas com a senha anterior. Desativar a pessoa invalida os códigos pendentes.
- **Proteção:** o código conta no mesmo limite de tentativas do login (10 por e-mail a cada 5 minutos). O banco guarda só o hash; o código não aparece em URL, log, auditoria nem na tabela de idempotência, e os testes conferem isso.

## Portal

- **Login:** link "Tenho um código de acesso".
- **Equipe da empresa (gestor):** "Convidar pessoa" (nome, e-mail, operador ou gestor). A lista mostra a situação do acesso (Ativo, Aguardando primeiro acesso, Desativado) e as ações "Gerar novo convite", "Novo código (senha esquecida)" e "Desativar".
- **Empresas cadastradas (equipe ThermoTrace):** "Cadastrar empresa" (razão social, CNPJ, atividade, gestor). A lista mostra atividade e gestores, com ação de novo código. A equipe continua sem ver as cargas.

## Evidências e limites

- 174 testes API/PostgreSQL (166 + 8 novos) e `alembic check` sem divergência; 16 testes web.
- Ensaio no navegador com dados fictícios: a equipe ThermoTrace cadastrou uma transportadora, a gestora fez o primeiro acesso com o código (digitado em minúsculas e com espaços) e convidou um operador. O cartão com o código fica na tela depois da atualização da lista.
- Largura de celular (375 px) conferida sem rolagem lateral na equipe, na carga e no quadro de compartilhamento do WEB-10.
- Fluxo antigo com senha definida por quem cadastra continua aceito pela API, por compatibilidade, mas o portal não o usa.
- **Ainda falta no WEB-11:** cadastro de cargas pelo portal, MFA para a equipe ThermoTrace e uma política de troca periódica de senha. A recuperação continua dependendo de alguém da empresa ou do suporte; não há "esqueci minha senha" sozinho, porque isso exigiria envio de e-mail (custo a avaliar).

## Publicação

Migração `c10411040002`, que acrescenta colunas à tabela `token_recuperacao`, criada no início do projeto e sem uso até aqui. Papel restrito já existente: aplicar `backend/scripts/runtime-acesso-permissoes.sql` depois da migração. O Render usa o mesmo usuário para migrar e rodar e não precisa disso.

## Parte 2 — cadastro de perfis, produtos e cargas pelo portal, 04/11/2026

Motivo: o Android 0.8.6 só envia coletas para cargas que já existem no servidor, e até aqui elas só podiam ser criadas pela API.

- **Produtos e perfis (gestor):** cria o rascunho do perfil térmico, opcionalmente a partir de um modelo de referência. O modelo preenche faixa, condição e fonte, mas deixa produto, apresentação, versão da fonte e data de conferência para a pessoa. A aprovação exige declaração de responsabilidade (mínimo de 20 caracteres) e há ação de retirar de uso. Configurar produto liga o código ao perfil aprovado e ao intervalo; reconfigurar o mesmo código cria nova versão.
- **Minhas cargas → Nova carga (operador e gestor):** produto disponível, código opcional, destinatário, descrição, volumes e documento opcional (NF-e, CT-e, pedido…). O critério do produto fica congelado na carga.
- **Mensagens de erro:** quando a API recusa por validação, o portal mostra o motivo da regra (por exemplo, "Informe o código da carga ou pelo menos um documento") ou o campo a revisar, em vez de uma mensagem genérica.
- **Evidências:** API sem mudança (174 testes seguem passando) e 17 testes web. No navegador, com dados fictícios, conferi rascunho a partir do modelo "Vacina refrigerada", aprovação, produto v1 e v2 sem conflito, a lista de produtos disponíveis na nova carga e a mensagem de erro. Criação de carga pelo formulário também conferida: a carga nasceu com 2 volumes, documento de pedido e critério congelado, e o portal abriu a página dela. Corrigido o ponto final duplicado na mensagem de erro.

## Parte 3 — verificação em duas etapas (MFA), API 0.8, 04/11/2026

- **Como funciona:** código de 6 dígitos de app autenticador (TOTP, padrão RFC 6238), implementado com a biblioteca padrão do Python, sem dependência nova.
- **Quem usa:** **obrigatória para a equipe ThermoTrace**, cuja área fica bloqueada até a ativação; opcional para gestores e operadores, em "Segurança da conta".
- **Login:** o portal só pede o código depois da senha correta, então quem não sabe a senha não descobre se a conta tem MFA. O mesmo código não vale duas vezes, e o código do período anterior ou do seguinte é aceito, para tolerar celular com relógio adiantado ou atrasado.
- **Ativação:** encerra as outras sessões abertas da conta. Desativar exige um código atual e não é permitido para a equipe ThermoTrace.
- **Celular perdido:** quem tem acesso ao servidor roda python -m app.mfa_cli --email ... --motivo .... A ação fica na auditoria do cliente.
- **Limite registrado:** o segredo TOTP fica no banco sem cifra adicional. Rever antes de dados reais.
- **Evidências:** 178 testes API (4 novos, incluindo os vetores oficiais da RFC 6238) e 17 web. No navegador, a conta fictícia da equipe caiu direto na ativação, ativou com o código, abriu a área de empresas e no login seguinte teve o código pedido.
- **Banco de teste:** recriado nesta rodada. Os testes de concorrência já existentes deixam empresas sintéticas gravadas, e após muitas execuções as listagens de até 100 itens falhavam. Fica para o plano de acabamento.
