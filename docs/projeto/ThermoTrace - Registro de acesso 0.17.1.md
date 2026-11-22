---
tipo: entrega
projeto: ThermoTrace
contexto: pessoal-independente
atualizado: 2026-11-22
---

# Registro de acesso (API 0.17.1)

Entrega de 22/11/2026, branch `feat/retencao-ip`.

## Por quê

A [[ThermoTrace - Site público e privacidade 0.17|política de privacidade]] promete guardar IP, data e hora de acesso por pelo menos 6 meses (Marco Civil, art. 15). Ao conferir, o banco não guardava IP nenhum. As colunas `sessao_auth.ip` e `log_auditoria.ip` existiam desde o começo, mas nenhum código gravava nelas. O IP só passava pelo limite de tentativas, que guarda um hash por 5 minutos. A única fonte era o log do Render, cujo prazo de retenção depende do plano e é curto.

## O que mudou

- Cada requisição guarda o IP do cliente (o mesmo `ip_do_cliente` dos limites, que lê o `CF-Connecting-IP` e nunca o `X-Forwarded-For`).
- A sessão de login grava o IP no login, na renovação do token, na entrada da demonstração e na ativação por código.
- Todo evento da auditoria grava o IP, inclusive login recusado.
- Endereço inválido (sem cabeçalho e sem conexão identificável) fica vazio, nunca vira texto na coluna `INET`.
- Nada apaga essas linhas, então "pelo menos 6 meses" está garantido pelo banco. Um prazo **máximo** (apagar ou anonimizar depois de X meses) é decisão de produto e jurídica; não foi implementado. A auditoria é a trilha das cargas e não deve perder linhas.
- User-agent não é gravado: a política fala de IP, data e hora, e o mínimo basta.

## Evidências

- 4 testes novos em `test_rede.py`: login grava IP na sessão e na auditoria; renovação grava o IP de quem renovou; login recusado também; endereço inválido fica vazio.
- Suíte da API: 239 testes verdes em PostgreSQL local; `alembic check` sem diferença (não há migração, as colunas já existiam).
- Achado de ambiente, corrigido nos testes: os testes de concorrência gravam empresas de verdade (precisam de commit), e o banco local de testes passou de 100 empresas. Dois testes que liam só a primeira página da lista de clientes falhavam localmente (no CI o banco é novo a cada execução). Agora eles percorrem todas as páginas.

## O que falta

- [ ] Depois do deploy e de um login no portal publicado, conferir no banco do Render (consulta só de leitura: `select em, acao, ip from log_auditoria order by id desc limit 5`) que o IP aparece. O portal não mostra o IP de propósito: o gestor não precisa ver o endereço de cada pessoa da equipe, e o registro existe para atender requisição judicial.
- [ ] Decidir se haverá prazo máximo de guarda do IP (usuário + revisão jurídica).
