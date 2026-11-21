---
tipo: entrega
projeto: ThermoTrace
contexto: pessoal-independente
atualizado: 2026-11-20
---

# Viagem do servidor no app (Android 0.9.0, TT-040)

Entrega de 20/11/2026, PR #4 (`feat/viagem-no-app`). No mesmo dia, as API 0.14 a 0.16 entraram no `main` por fast-forward (PRs #1, #2 e #3) e o Render passou a responder 0.16.0. Partes públicas conferidas lá: conferência responde "não confere" para hash inventado, `/portal/conferir` abre, painel de conformidade e CSV pedem login (401), demonstração desligada.

## Por quê

O celular só mostrava o gráfico da própria coleta. Quando outro aparelho bipava depois, ou quando a transportadora operava a carga, o operador não tinha como ver a viagem inteira nem saber se ela estava conforme. O servidor já consolidava isso desde a WEB-12; faltava chegar ao app.

## O que mudou

- Tela **Enviar coletas**: depois que há recibo, aparece o cartão **Viagem no servidor**. A consulta é carregada sozinha ao abrir a tela e depois de cada envio, e o botão "Atualizar viagem" refaz a consulta.
- Situação com a **mesma regra do painel de conformidade** (conforme, com excursão, coletas divergentes, em andamento, sem dados), sempre escrita, não só pela cor.
- % na faixa, tempo fora, mínima e máxima, gráfico (o mesmo componente do app), trechos de excursão com horário e extremo, quantas coletas foram consideradas e se a leitura final já chegou.
- Destaques: viagem de **simulação**, cadeia de integridade quebrada ("não use em laudo") e coletas que discordam.
- Rodapé: "Sem validação física: esta tela não libera a carga" e o começo do hash da viagem.
- A consulta é separada do envio: se falhar (sem rede, 404 porque a sessão ainda não chegou), as coletas e os recibos não são afetados.
- Tela da **remessa** (mesmo dia, segundo commit): cada volume com coleta já recebida mostra o cartão compacto, com a situação e a % na faixa. "Ver detalhes" abre o resto. Simulação e integridade quebrada aparecem mesmo fechado. A consulta é feita uma vez por abertura da tela, e não a cada volta da leitura, porque cada consulta vira registro na auditoria do servidor. Fora de conta vinculada, a tela segue só com a evidência local.

- **Compartilhar relatório com QR de conferência** (21/11), nas duas telas: baixa do servidor o relatório HTML da viagem e abre o menu de compartilhar do Android (WhatsApp, e-mail etc.). O texto da mensagem leva o endereço de conferência, para quem recebe conferir sem login mesmo sem abrir o anexo. Baixar pelo app registra a exportação na auditoria, e a conferência pública só reconhece relatório exportado; por isso o QR do arquivo compartilhado confere. Sem permissão de exportar a carga compartilhada, aparece a mensagem de permissão (403).

### Defeito corrigido antes do merge (21/11)

O transporte HTTPS do app só manda o token para uma lista fechada de rotas, e a da viagem não estava nela. No aparelho, a consulta seria recusada antes de sair, com "atualize o aplicativo". Os testes do dia 20 conferiam o contrato, não o transporte. A lista virou função testada (`caminhoPermitido`), e a viagem entrou, só em JSON e HTML.

## Contrato

- `ContratoViagem` recusa resposta de outra sessão, resumo que não confere com os pontos, pontos fora de ordem e resposta que se declare validada fisicamente.
- Fixtures tiradas da API real com dados sintéticos, da mesma sessão: `backend/docs/viagem-android-0.16.json` e `.html`. O teste confere também que o HTML traz o endereço de conferência. O teste novo `test_contrato_android_da_viagem` falha se a forma da resposta mudar sem o app mudar junto. Para regenerar: `THERMOTRACE_GRAVAR_FIXTURE=1`.

## Evidências

- 164 testes JVM (12 novos em `ViagemServidorTest`: contrato, mensagens de falha, rotas do transporte e relatório), lint e APK debug sem erro.
- 4 testes de viagem na API, em PostgreSQL 17 descartável.

## O que falta

- [ ] **Servidor, achado em 21/11, não corrigido:** o Render roda o uvicorn sem `--forwarded-allow-ips`. Provavelmente todas as requisições chegam com o IP do proxy do Render, e os limites por IP (login, conferência, demonstração) passam a valer para todos juntos. Também deixa o link impresso no relatório em `http://`. Confirmar no Render antes de mudar, porque confiar no cabeçalho errado deixa o limite ser burlado.
- [ ] Compartilhar o relatório num aparelho e ler o QR com outro celular.

- [ ] Ver o cartão em aparelho, com conta real no Render (não foi visto em tela, nem em emulador).
- [ ] Repetir a conferência e a conformidade no Render com login: precisa de conta de gestor ou de ligar `DEMO_PUBLICA` na homologação (decisão do usuário).
- [ ] TT-040 fecha no código com as duas telas; falta o ensaio em aparelho com dois celulares na mesma carga.
