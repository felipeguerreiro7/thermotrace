---
tipo: entrega
projeto: ThermoTrace
contexto: pessoal-independente
atualizado: 2026-11-23
---

# Monitoramento do serviço publicado (TT-066)

Entrega da branch `feat/monitoramento-saude`. Fecha a parte "alerta de falha" do TT-061/TT-066 no [[ThermoTrace - Plano de ação e backlog]]. Backup automático no Render continua pendente (depende do plano).

## Por quê

Se o Render caísse, o banco parasse de responder ou um deploy travasse, ninguém ficaria sabendo até um cliente reclamar. O deploy travado é o caso mais traiçoeiro: quando a migração falha no `preDeployCommand`, o Render mantém a versão antiga no ar e o site continua "funcionando", só que sem a correção que acabou de entrar.

## Como funciona

- Workflow `.github/workflows/monitoramento.yml`, a cada 30 minutos (aos 7 e 37 de cada hora) e sob demanda ("Run workflow").
- Script `.github/scripts/conferir-saude.sh`, que também roda num terminal comum:
  1. `/api/v1/saude/completa` responde 200 com banco ok (três tentativas, 20 s entre elas, para um pico de lentidão não virar alarme);
  2. a versão publicada é a da `main`; a diferença só conta depois de 45 minutos do último commit que mudou a versão, para não acusar um deploy em andamento;
  3. a página inicial e o portal abrem.
- Falhou: abre a issue **"Serviço com problema (monitoramento automático)"** com a lista do que falhou, ou comenta na que já estiver aberta. O GitHub avisa o dono do repositório por e-mail e no app do celular.
- Voltou ao normal: comenta e fecha a issue sozinho.
- Sem custo e sem segredo: só endereços públicos e o token do próprio workflow.

## Evidências

Script testado localmente contra o Render em três situações:
- servidor real saudável (0.19.1): passa;
- versão da `main` diferente da publicada, sem tolerância: acusa "Deploy travado";
- endereço fora do ar: acusa saúde e páginas "sem resposta".

YAML validado. A primeira execução no GitHub só acontece depois do merge, porque agendamento e "Run workflow" valem a partir da `main`.

## O que falta (usuário)

- [ ] Depois do merge, rodar uma vez em Actions → monitoramento → **Run workflow** e conferir que fica verde.
- [ ] Conferir em GitHub → Settings → Notifications que avisos de issues do próprio repositório chegam por e-mail ou celular.
- [ ] Quando o domínio tiver DNS, criar a variável do repositório `URL_MONITORADA` com `https://thermotrace.com.br`.
- [ ] O GitHub pausa agendamentos de repositório parado há 60 dias; um commit ou execução manual reativa.
- [ ] Backup automático no Render (TT-061): continua decisão de plano e custo.
