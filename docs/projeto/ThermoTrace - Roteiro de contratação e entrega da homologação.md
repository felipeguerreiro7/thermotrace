---
tipo: procedimento
projeto: ThermoTrace
contexto: pessoal-independente
status: aguardando-execucao-do-usuario
atualizado: 2026-09-12
---

# Roteiro — contratar a homologação e entregar para a IA continuar

Divide o trabalho em três blocos: o que **só o usuário** pode fazer, o que ele **passa**
adiante, e o que a IA (Claude ou Codex) faz depois. Contexto e preços em
[[ThermoTrace - Hospedagem e custos]]; o caminho geral está em
[[ThermoTrace - Publicação e operação]].

## Regra de segurança que vale acima de tudo

**Nenhuma senha, token, chave de API ou URL de banco com credencial embutida deve ser
colada em conversa com IA.** Não é formalidade: é a mesma exigência que
[[ThermoTrace - Segurança e auditoria]] faz ao app, que nunca guarda senha em preferências.

O padrão correto é: a IA escreve o código e a configuração que **leem** o segredo do
ambiente; o usuário **define o valor** no painel do provedor. O `backend/.env.example` já
está desenhado assim, e o `.env` real nunca vai para o Git.

## Bloco 1 — só o usuário pode fazer

1. **Criar as contas** no provedor de contêiner e no de banco, com método de pagamento e
   identidade. IA não cria conta nem insere dado de pagamento.
2. **Aceitar os termos de serviço** dos provedores.
3. **Comprar o domínio**, em nome dele, e manter a titularidade. Registrar onde está
   registrado e com que e-mail — perder titularidade de domínio é o tipo de problema que
   aparece no pior momento.
4. **Escolher a região.** Para homologação com dado fictício, qualquer uma serve. Para
   produção, é o TT-067 e não é decisão técnica.
5. **Aprovar o gasto.** Faixa pesquisada de US$ 10 a 25/mês para a homologação.
6. **Definir os valores dos segredos** no painel de variáveis de ambiente do provedor:
   - `SECRET_KEY` — gerar com `python -c "import secrets; print(secrets.token_urlsafe(48))"`
     e **não reutilizar** a de nenhum outro ambiente.
   - `DATABASE_URL` — a string que o provedor de banco fornece, colada direto no painel.
   - `RESEND_API_KEY` e `EMAIL_REMETENTE` — só quando o alerta por e-mail entrar em cena.
   - `AMBIENTE=homologacao`, `DEBUG=false`, `NIVEL_LOG=INFO`, `LOG_JSON=true`.
   A referência completa está em `backend/.env.example`.
7. **Criar o primeiro usuário administrativo** pelos mecanismos do servidor, e guardar a
   senha no gerenciador dele — não em nota, não em conversa.

## Bloco 2 — o que passar para a IA

Tudo aqui é **não sensível** e basta para continuar o trabalho:

1. Qual provedor de contêiner e qual de banco foram contratados, e em que região.
2. O **hostname público** (ex.: `homologacao.seudominio.com.br`) — endereço não é segredo.
3. Confirmação de que as variáveis de ambiente foram definidas, **sem os valores**.
4. A versão do Postgres que o provedor entregou.
5. Se o plano contratado tem PITR ou apenas backup diário, e qual a retenção.
6. Qualquer mensagem de erro do deploy — com credenciais mascaradas.

## Bloco 3 — o que a IA faz depois

1. Ajustar a configuração de deploy a partir do `Dockerfile` que já existe, incluindo
   `healthcheck`, porta e variáveis esperadas.
2. Escrever o procedimento de migração com backup antes e validação depois, na ordem certa,
   e aplicar os `scripts/runtime-*-permissoes.sql` — o papel de permissões mínimas.
3. Provisionar **duas empresas fictícias** e executar o teste de isolamento entre elas
   (TT-065), que é critério de liberação.
4. Rodar o caminho completo contra o endereço real e conferir o contrato da API.
5. Montar e **medir** a restauração de backup (TT-066), antes de qualquer meta de RPO/RTO
   virar compromisso.
6. Registrar tudo no cofre, com data e evidência.

O lado Android não precisa de nada: a URL do servidor é **digitada pelo operador** na tela
Conta e cargas e guardada cifrada, não compilada no APK. Então nenhum build novo é
necessário para apontar para a homologação.

## Ordem sugerida

Banco primeiro (a `DATABASE_URL` é insumo do resto), depois contêiner, depois domínio e
HTTPS, depois migração, depois as duas empresas fictícias e o teste de isolamento, e só
então o ensaio com o celular. Cada etapa com evidência anotada aqui.

## Configuração do Render, derivada do código (12/09/2026)

Levantado lendo `backend/Dockerfile` e `backend/docker-compose.yml`. Confirmar na interface
do Render, que muda com frequência.

1. **Root Directory: `backend`.** O repositório tem o app Android na raiz; sem isto o Render
   tenta construir a coisa errada.
2. **Runtime Docker**, usando o `Dockerfile` que já existe. Imagem `python:3.12-slim`, roda
   como usuário sem privilégio (uid 10001), o que já está correto.
3. **Porta.** O Dockerfile fixa 8000 (`--port 8000`). O Render injeta a porta na variável
   `PORT`. Sobrescrever o comando de start para
   `uvicorn app.main:app --host 0.0.0.0 --port $PORT`, ou o serviço sobe e nunca recebe
   tráfego.
4. **Health check path: `/api/v1/saude`** — é o caminho que o próprio `HEALTHCHECK` do
   Dockerfile usa.
5. **Migrações.** O compose roda `alembic upgrade head` antes do uvicorn. No Render isso deve
   ser **pre-deploy command**, não parte do start: assim roda uma vez por deploy, e não a cada
   reinício de contêiner.
6. **Variáveis de ambiente**, definidas pelo usuário no painel, nunca em arquivo no
   repositório: `DATABASE_URL`, `SECRET_KEY`, `AMBIENTE=homologacao`, `DEBUG=false`,
   `LOG_JSON=true`, `NIVEL_LOG=INFO`, `ORIGENS_PERMITIDAS=[]`.

### Armadilha a resolver antes de confiar em homologação

`DIRETORIO_ARQUIVOS=./dados/arquivos` grava em disco local. No Render, o disco do contêiner é
**efêmero**: some a cada deploy e a cada reinício. Num produto de evidência isso é grave se
laudos ou anexos forem parar ali. Duas saídas: anexar um disco persistente ao serviço, ou
mudar o armazenamento de arquivos para objeto (S3 ou equivalente). Enquanto não for decidido,
nada que precise sobreviver a um deploy pode ser escrito nesse diretório.

O `docker-compose.yml` já trata isso com volume nomeado (`dados_arquivos`), o que mostra que
a necessidade é conhecida — só não se transporta de graça para o Render.

## Homologação no Render — execução em 12/09/2026

1. Repositório privado `felipeguerreiro7/thermotrace` no GitHub, criado e enviado pelo
   usuário. Autenticação feita por ele, por navegador; nenhuma IA recebeu token.
2. Banco `thermotrace-homologacao-db`, PostgreSQL 17, Oregon (US West), 5 GB.
3. Serviço `thermotrace-api` criado por Blueprint a partir do `render.yaml` do repositório,
   com `DATABASE_URL` preenchida no painel (`sync: false`) apontando para o banco acima.

### Pendência de segurança — trocar a credencial do banco antes de dado real

A Internal Database URL do `thermotrace-homologacao-db` apareceu em texto claro numa captura
de tela enviada em conversa, com usuário e senha visíveis. O valor não está registrado aqui
nem em nenhum outro arquivo, de propósito.

Risco hoje é baixo e vale dimensionar em vez de dramatizar: o banco é de homologação, foi
criado no mesmo dia, está praticamente vazio, e o hostname interno só resolve dentro da rede
do Render. **Mas a mesma senha vale para a External Database URL, que é alcançável pela
internet.**

Ação: **trocar a credencial antes de qualquer dado real entrar**, seja por rotação no painel
ou recriando o banco. Enquanto a troca não acontecer, nada de cliente pode ser gravado ali.

Regra de trabalho que fica: senha, token e URL com credencial não entram em captura de tela
nem em conversa com IA. O Render tem ícone de olho para mascarar o campo; usar antes de
printar. Vale igualmente para o Codex.

### O que o `render.yaml` passou a significar

O Render avisa que toda atualização do arquivo é sincronizada automaticamente e pode alterar
custos. Ou seja, um commit naquele arquivo mexe na infraestrutura sem ninguém clicar em nada.
Tratar `render.yaml` como arquivo sensível: qualquer alteração precisa ser avisada ao usuário
antes, com o que muda de recurso e de custo.
