---
tipo: roteiro
projeto: ThermoTrace
contexto: pessoal-independente
atualizado: 2026-11-22
---

# Ensaio WEB-04 no ambiente publicado

Semana 3 do [[ThermoTrace - Plano até 30-11-2026]]. Aceite do WEB-04 ([[ThermoTrace - Portal e próximas entregas]]): duas empresas fictícias e a plataforma; cada uma vê só o que está autorizado; login, expiração, saída e buscas funcionam no site e no Android contra o mesmo servidor. Fecha também o TT-065 e confere no aparelho a correção dos volumes livres ([[ThermoTrace - Operação entre empresas]]).

Ambiente: `https://thermotrace-api.onrender.com`, homologação. Sem credenciais em mensagens, prints ou notas.

## A. O que já foi conferido sem login (22/11/2026)

Feito do computador de desenvolvimento, sem conta nenhuma.

- [x] `/api/v1/saude/completa`: versão **0.17.0** (a mesma da `main`), ambiente homologação, banco ok.
- [x] Página inicial, `/privacidade`, `/termos` e `/portal` abrem por HTTPS. `/site/site.css` é servido; tentar sair da pasta (`/site/../app/main.py`) dá 404.
- [x] Cabeçalhos do portal: HSTS de 1 ano, CSP restrita (`frame-ancestors 'none'`, `object-src 'none'`), `nosniff`, `no-referrer`, `Cache-Control: no-store`, câmera, microfone e localização bloqueados.
- [x] As **29 rotas GET** da API respondem 401 sem login, com quatro exceções esperadas: saúde, saúde completa, situação da demonstração e conferência pública (que exige sessão e hash; sem eles, 422).
- [x] Token inventado: 401. Login com conta inexistente: 401 com a mesma mensagem de senha errada (não revela se o e-mail existe).
- [x] Limite de tentativas de login: 10 tentativas erradas e a 11ª dá 429.
- [x] `/docs` e `/openapi.json` abertos, como previsto fora de produção. Em produção o código desliga os dois.
- [x] Demonstração **desligada**: `/api/v1/demo` responde `habilitada: false`. Ou falta `DEMO_PUBLICA=true` no painel, ou o `demo_cli` não rodou contra esse banco (ou as duas coisas).

As etapas com login ficam com o usuário. A automação de desenvolvimento não digita senha num servidor publicado, nem de conta fictícia.

## B. Preparar as empresas fictícias (usuário, ~10 min)

O `demo_cli` roda no computador e escreve direto no banco do Render. Ele recusa produção e não repete se as empresas já existirem.

1. Render → banco `thermotrace` → **Connect** → copie a **External Database URL**. Não cole em chat nem em nota.
2. No PowerShell, na pasta `backend` (com o ambiente Python do projeto ativo):

```powershell
$env:DATABASE_URL = Read-Host "External Database URL"
```

```powershell
$env:AMBIENTE = "homologacao"; python -m app.demo_cli
```

O `backend/.env` local continua valendo para o resto (`SECRET_KEY`); as duas variáveis acima têm prioridade sobre ele. A senha das contas é pedida no terminal (mínimo de 12 caracteres). Guarde no gerenciador de senhas. Feche o PowerShell depois, para a URL do banco não ficar na sessão.

3. Se der erro de permissão no banco, o papel do Render pode estar restrito (WEB-05). Mande só a mensagem de erro, sem a URL.

Resultado: dono `demo-dono-gestor@example.org` e `demo-dono-operador@example.org`, transportadora `demo-transportadora-gestor@example.org` e `demo-transportadora-operador@example.org`, cargas DEMO-CONFORME, DEMO-EXCURSAO e DEMO-EM-TRANSITO (essa última compartilhada com a transportadora, com permissão de operar e exportar).

Decisão pendente: ligar `DEMO_PUBLICA=true` deixa qualquer pessoa com o endereço entrar nas contas fictícias sem senha ("Conhecer o portal"). Para o ensaio não precisa; use as senhas.

## C. Portal, no computador e no celular (usuário, ~40 min)

Use duas janelas anônimas lado a lado: uma com o dono, outra com a transportadora. Marque o que passou; anote o que falhou com hora e tela.

**Login e sessão**
- [ ] Gestor do dono entra. Senha errada mostra erro sem dizer se o e-mail existe.
- [ ] Recarregar a página (F5) pede login de novo (a sessão fica só na memória, por desenho).
- [ ] Deixar a aba parada por mais de 15 min e clicar numa carga: continua funcionando (renovação) ou volta ao login, sem tela quebrada.
- [ ] **Sair**: volta ao login. O botão Voltar do navegador não mostra dados da carga.

**Isolamento (o centro do WEB-04)**
- [ ] Dono vê as três cargas DEMO.
- [ ] Transportadora vê **só** DEMO-EM-TRANSITO. Buscar `DEMO-CONFORME` e `DEMO-EXCURSAO` não encontra nada.
- [ ] Copiar o endereço de uma carga do dono que não foi compartilhada e abrir na janela da transportadora: "não encontrado", nunca os dados.
- [ ] Busca por código e por NF, paginação e retorno à lista mantêm o contexto, nas duas empresas.
- [ ] Operador do dono não vê equipe nem auditoria da empresa (só gestor vê).

**Compartilhamento e revogação**
- [ ] Na auditoria do dono aparecem as consultas que a transportadora fez à DEMO-EM-TRANSITO.
- [ ] Transportadora exporta CSV/relatório da DEMO-EM-TRANSITO (permitido).
- [ ] Dono compartilha a DEMO-CONFORME com a transportadora (CNPJ `00000000000272`) **sem** exportação; transportadora aceita; vê a carga e não consegue exportar.
- [ ] Dono encerra esse compartilhamento. Na janela da transportadora, a próxima consulta já é recusada. Fica registrado na auditoria.

**Gráfico e relatório**
- [ ] DEMO-EXCURSAO mostra a excursão de ~2 h acima de 8 °C; DEMO-CONFORME, nenhuma. Coletas aparecem marcadas como simulação.
- [ ] O QR do relatório abre a conferência pública no celular sem login.

**Plataforma (equipe ThermoTrace)**
- [ ] Conta staff (com MFA) vê as empresas fictícias, mas **não** o conteúdo das cargas.

**Celular**
- [ ] Repetir login, lista, busca e saída no navegador do celular. Nada fica fora da tela nem exige rolar para os lados.

## D. Android contra o mesmo servidor (usuário + desenvolvimento, ~30 min)

Precisa do A57 com o APK de piloto. Pode ser junto com a parte B do [[ThermoTrace - Roteiro do ensaio físico de novembro]].

- [ ] Ajustes → servidor `https://thermotrace-api.onrender.com`. Entrar com o operador da transportadora.
- [ ] A carga DEMO-EM-TRANSITO aparece. O volume 2 (ainda sem etiqueta) é oferecido para monitorar. É a correção da 0.8.9, nunca conferida em aparelho.
- [ ] Sair e entrar com o operador do dono: nada da sessão anterior aparece (isolamento local por conta).
- [ ] Com etiqueta real: ativar o volume 2, fazer um checkpoint e conferir no portal do dono que a coleta chegou, com autoria da transportadora.
- [ ] Tirar o celular da internet, fazer outro checkpoint, devolver a internet: a coleta sobe sozinha, uma vez só (sem duplicar no portal).

## E. O que devolver

Só as caixas marcadas e, para cada falha: etapa, hora aproximada, o que apareceu e o `request_id` da mensagem de erro, se houver. Sem senha, URL de banco ou print com dados de login.
