# ThermoTrace API 0.2 — acesso e perfis

Projeto pessoal independente. Implementado em 11/09/2026. Código e migrações testados num PostgreSQL 17 descartável; banco existente e ambiente público não foram migrados nesta entrega.

## O que está funcional

- Login por e-mail/senha, Argon2id, access token de 15 minutos com assinatura, emissor, audiência e datas obrigatórias.
- Refresh opaco, hash no banco, rotação com trava transacional entre processos. Reuso revoga toda a família e essa revogação persiste mesmo quando a resposta é 401. Expiração de 30 dias é absoluta, não cresce a cada renovação.
- Logout e desativação de operador invalidam acesso no servidor. Empresa/usuário inativos são recusados. O app deverá serializar refresh: dois pedidos simultâneos com o mesmo token levam à revogação e novo login.
- Limite de login compartilhado pelo PostgreSQL: 10 tentativas por conta e 50 por IP em janela fixa de 5 minutos, incluindo sucessos. Não confiar em cabeçalhos de proxy enviados pelo cliente. Planejar exceções de rede compartilhada após observar o piloto.
- Equipe da plataforma cadastra clientes e gestor inicial; gestores criam operadores/gestores da própria empresa. Cada usuário pertence a uma empresa nesta fase.
- Perfis: rascunho, fonte e escopo obrigatório, aprovação interna identificada, retirada de uso e versões independentes. Nova versão não desativa automaticamente a antiga; a troca operacional será feita por vínculo de produto.
- Lista de 14 modelos de referência autenticada, sem aprovação automática e sem criar registros operacionais.
- Auditoria de alterações de acesso/perfil; consulta restrita à empresa do gestor. Senhas/tokens não aparecem nos eventos nem nas respostas de usuários.
- CNPJ numérico e alfanumérico, normalizado com validação dos dígitos. Validar formato não comprova identidade empresarial nem regularidade cadastral.

## API

Todas as rotas abaixo usam `/api/v1`.

| Rota | Acesso | Resultado |
|---|---|---|
| POST /auth/login | Credenciais | Par access/refresh |
| POST /auth/refresh | Refresh | Novo par; anterior revogado |
| POST /auth/logout | Autenticado | Revoga família |
| GET /auth/me | Autenticado | Identidade e empresa |
| GET/POST /plataforma/clientes | ADMIN de empresa PLATAFORMA | Cadastro administrativo de clientes |
| GET/POST /usuarios | GESTOR | Usuários da própria empresa |
| POST /usuarios/{id}/desativar | GESTOR | Desativa operador próprio |
| GET /perfis/modelos | Autenticado | Referências não operacionais |
| GET /perfis | Autenticado | Perfis da própria empresa; ativos por padrão |
| POST /perfis | GESTOR | Nova versão em rascunho |
| GET /perfis/{id} | Autenticado | Detalhe próprio |
| POST /perfis/{id}/aprovar | GESTOR | Aprovação interna com declaração |
| POST /perfis/{id}/retirar | GESTOR | Retira da seleção, preserva histórico |
| GET /auditoria | GESTOR | Eventos próprios com cursor depois_id |

As listas têm limites de paginação. Consultas por ID estrangeiro devolvem 404. Empresa vem da sessão validada, nunca do corpo enviado. Administrador ThermoTrace não recebe acesso implícito aos perfis de clientes. Endpoints com mudanças de cadastro ainda não oferecem Idempotency-Key: não aplicar reenvio automático a esses POSTs.

## Banco e isolamento

Separação lógica por empresa no mesmo banco. Não foi criado um banco físico por cliente. Perfis também têm RLS forçada com contexto local à transação; teste com papel sem BYPASSRLS verifica consulta sem WHERE e tentativa de escrita estrangeira. Usuários, empresas e auditoria usam autorização na API nesta fase. Expandir RLS às tabelas operacionais antes do piloto completo.

Gatilho impede alterar o critério de perfil aprovado ou apagá-lo. Retirada muda só disponibilidade. Corrigido gatilho de excursões: não é possível adulterar campos junto de substituida_por nem trocar novamente a substituição. Leitura, série, laudo e log continuam protegidos pelos gatilhos existentes. Administradores do banco ainda têm poder de alterar estruturas; backup externo e manifesto independente continuam necessários para detectar intervenção privilegiada. Não há certificação de autenticidade do sensor.

Migrações novas: 7c430f901101 e 80dff1100901. A primeira normaliza e-mail e pode falhar se o legado tiver duplicatas equivalentes; a transação evita alteração parcial. Também retira perfis legados da seleção para novas operações por não terem aprovação comprovada; preserva suas faixas e referências históricas. Revisar impacto e backup antes de aplicar no banco existente. Não há downgrade automático de segurança.

## Executar e testar

1. Python 3.12 e PostgreSQL 17; instalar requirements.txt no ambiente virtual.
2. Configurar DATABASE_URL e SECRET_KEY por ambiente/arquivo local protegido. Nunca copiar o .env para entregas ou imagem.
3. Com credencial de migração separada, executar `python -m alembic upgrade head` e `python -m alembic check` no banco escolhido.
4. Para o primeiro administrador, executar `python -m app.cli --cnpj CNPJ_REAL --razao-social NOME --nome ADMIN --email EMAIL`. A senha é digitada sem eco e não aparece nos argumentos. O bootstrap recusa plataforma já existente. Não executado com identidade fictícia no banco do projeto.
5. Preparar o papel da API com scripts/runtime-permissoes.sql, definir senha interativamente e usar essa credencial para o processo web. Não usar o dono das migrações. Em AMBIENTE=producao o processo recusa superusuário, BYPASSRLS e propriedade de tabelas.
6. Desenvolvimento: `python -m uvicorn app.main:app --host 127.0.0.1 --port 8000`. Documentação local em /docs. Produção exige HTTPS/proxy configurado, domínios/origens explícitos e operação supervisionada.
7. Testes: scripts/testar.ps1 recebe -DatabaseUrl apontando para banco descartável terminado em _test. Os testes se recusam a usar silenciosamente o .env. A prova de concorrência deixa registros sintéticos nesse banco descartável. Não apontar testes para dados de clientes.

O Compose existente continua exclusivo de desenvolvimento e usa credenciais de exemplo. Portas locais foram limitadas a 127.0.0.1. Dockerfile copia explicitamente código e configuração de migração; .dockerignore exclui segredos, dados e ambiente virtual. Docker não estava disponível para validar uma imagem nesta máquina.

## Validação e pendências

55 testes passaram em PostgreSQL real, incluindo autenticação, token expirado/adulterado, reuso e concorrência de refresh, isolamento de clientes, papel insuficiente, aprovação/versões, RLS sob papel limitado, imutabilidade e CNPJ alfanumérico. Alembic check não encontrou divergência entre modelos e schema. Um aviso de depreciação do cliente HTTP de testes permanece.

Antes de uso comercial: integração Android, convite/troca/recuperação de senha, MFA da equipe, fluxo de suporte com concessão, vínculo produto/perfil, cargas/documentos, ingestão idempotente, gráficos/relatórios do servidor e portais. Testar TLS, limitação de corpo de requisição, monitoramento, limpeza dos contadores de login antigos, backup/restauração, infraestrutura e revisão de segurança. Aprovação interna de faixa não substitui validação do responsável de qualidade, fonte do produto ou calibração.

## Referências técnicas consultadas

- [SQLAlchemy: transações externas nos testes](https://docs.sqlalchemy.org/en/20/orm/session_transaction.html#joining-a-session-into-an-external-transaction-such-as-for-test-suites).
- [PyJWT: algoritmos de assinatura](https://pyjwt.readthedocs.io/en/stable/algorithms.html).
- [Receita Federal: cálculo do dígito do CNPJ alfanumérico](https://www.gov.br/receitafederal/pt-br/centrais-de-conteudo/publicacoes/documentos-tecnicos/cnpj/manual-dv-cnpj.pdf).
