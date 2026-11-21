---
tipo: entrega
projeto: ThermoTrace
contexto: pessoal-independente
atualizado: 2026-11-21
---

# IP do cliente atrás do proxy do Render (API 0.16.1)

Achado em 21/11 ao revisar o link de conferência que o app 0.9.0 passa a compartilhar ([[ThermoTrace - Viagem do servidor no app 0.9.0]]). Branch `fix/ip-proxy-render`.

## O problema

A API limita tentativas por IP: login (50 a cada 5 min por IP, 10 por e-mail), ativação de acesso, conferência pública (30) e entrada na demonstração. No Render ela usava o endereço da conexão, e a conexão nunca vem do cliente: vem de um proxy interno.

**Confirmado no Render em 21/11:** 32 conferências seguidas deste computador, todas aceitas, quando a 31ª deveria dar 429 (o teste local dá 429). Ou seja, o IP visto pela API muda de uma requisição para outra: são vários proxies, cada um misturando gente diferente. Efeitos:

- o limite não segura uma pessoa insistindo, porque ela se espalha entre proxies;
- quem divide o proxy divide o limite: tentativas de senha de outra pessoa podem bloquear o seu login por 5 minutos;
- o link e o QR de conferência no relatório saíam `http://`, porque o endereço da requisição atrás do proxy é http.

## A correção

- A borda do Render é a **Cloudflare** (respostas trazem `Server: cloudflare` e `CF-RAY`). A Cloudflare grava o IP real em `CF-Connecting-IP` e sobrescreve o que o cliente mandar.
- O **X-Forwarded-For não serve**: o proxy do Render acrescenta ao que o cliente envia em vez de limpar ([relato na comunidade do Render](https://feedback.render.com/features/p/send-the-correct-xforwardedfor)). Por isso a solução comum `--forwarded-allow-ips='*'` deixaria qualquer um escolher o próprio IP e escapar do limite.
- Função única `app.core.rede.ip_do_cliente`, usada nos quatro pontos. Lê `CF-Connecting-IP` só quando `IP_CLIENTE_CABECALHO=CF-Connecting-IP`; valor inválido ou ausente cai no endereço da conexão, nunca em texto livre.
- `URL_PUBLICA=https://thermotrace-api.onrender.com` para o link do relatório, em vez de deduzir do proxy. Só aceita `https://domínio`.
- As duas variáveis estão no `render.yaml`. Local e CI continuam sem elas.

## Evidências

- 229 testes de API em PostgreSQL 17 descartável (10 novos em `tests/test_rede.py`): cada cliente com o próprio limite; X-Forwarded-For forjado não troca o IP; sem a configuração, `CF-Connecting-IP` enviado pelo cliente é ignorado; relatório imprime o endereço público https.

## O que falta

- [x] Merge em 21/11 (PR #5) e deploy: `/saude` responde 0.16.1. `CF-Connecting-IP` forjado pelo cliente é recusado pela própria Cloudflare (403), o que confirma que o cabeçalho escolhido não é forjável de fora.
- [ ] **Pendente do usuário:** a 31ª conferência seguida ainda deu 200, porque o serviço não lê o `render.yaml` (foi criado à mão). Cadastrar no painel do Render, em thermotrace-api → Environment: `IP_CLIENTE_CABECALHO` = `CF-Connecting-IP` e `URL_PUBLICA` = `https://thermotrace-api.onrender.com`, e salvar com novo deploy. Até lá a correção está no código, mas sem efeito na homologação.
- [ ] Depois do cadastro, repetir: 31 conferências seguidas deste computador devem dar 429 na 31ª.
- [ ] Quando o domínio `thermotrace.com.br` entrar, trocar `URL_PUBLICA` e conferir que continua passando pela Cloudflare.
- [ ] Não liberar acesso direto ao serviço sem a Cloudflare enquanto `IP_CLIENTE_CABECALHO` estiver ligado.
