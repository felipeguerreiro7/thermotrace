---
tipo: entrega
projeto: ThermoTrace
contexto: pessoal-independente
atualizado: 2026-11-23
---

# Melhorias de uso: portal 0.19.1 e Android 0.9.2

Entrega da branch `feat/melhorias-portal`. Pedido do usuário: melhorias de funcionalidade e design no site e no APK, enquanto o ensaio físico não acontece. Nada aqui muda leitura NFC, decodificação ou regra térmica.

## Portal (API 0.19.1)

Achados de uma passada no portal, no computador e na largura de celular, com as empresas de demonstração.

- **Auditoria legível.** Vinha do mais antigo para o mais novo, com o nome técnico da ação (`perfil_aprovado_internamente`) e o responsável como UUID. Agora:
  - mostra o mais recente primeiro (botão "Mais antigos" para voltar no tempo);
  - traduz as ações ("Consultou a viagem", "Liberou a carga para o suporte ThermoTrace");
  - mostra o nome de quem agiu e, quando é de outra empresa (transportadora, equipe no suporte), o nome da empresa;
  - traz um detalhe curto: carga, formato baixado, motivo, prazo, acesso de outra empresa.
  A API ganhou `?ordem=recentes&antes_id=` e devolve `usuario_nome` e `usuario_empresa`, sem e-mail. Sem os parâmetros novos, a resposta continua na ordem antiga, para não quebrar quem já usa.
- **Menu no celular.** Os oito botões empilhados ocupavam a primeira tela inteira antes de qualquer conteúdo. Agora o menu é uma faixa só, presa no topo, rolando de lado e mantendo a página atual à vista.
- **Origem da viagem (erro de informação).** O painel da viagem dizia "Declaração do aplicativo" numa viagem **simulada**, porque o portal sobrescrevia a origem enviada pelo servidor. Corrigido. Origem ausente aparece como "Origem não informada".
- Faixa configurada com vírgula decimal (antes, "2.00 a 8.00 °C").

Evidências: teste novo de auditoria (ordem, nome, empresa, cursor, sem e-mail); 249 testes da API e 17 web verdes; conferido no navegador em servidor local, no computador e a 375 px de largura.

## Android 0.9.2

Sem celular no cabo e sem emulador instalado nesta máquina; as mudanças foram escolhidas para serem verificáveis por compilação, testes JVM e lint.

- **Tela inicial rola inteira.** Avisos, alerta de NFC, resultado da entrega, cartão da nota fiscal e painel de leitura ficavam fixos em cima e só a lista de remessas rolava. Com dois ou três avisos ligados, a lista sumia da tela. Agora é uma lista só.
- **Textos do envio atualizados.** Desde a 0.8.8 as coletas sobem sozinhas, mas a tela inicial ainda mandava "abrir a remessa e usar Enviar coletas". Agora diz que sobem sozinhas com internet e que a coleta sem carga vinculada espera o vínculo.
- **Envio logo após o vínculo.** Vincular uma coleta a uma carga não disparava o envio; ele esperava a rodada periódica de 15 minutos. Agora o vínculo agenda o envio na hora (mesmo caminho do envio automático), e a mensagem explica que o botão serve para mandar já e ver o recibo.

## O que falta

- [ ] Conferir a tela inicial no A57 (rolagem com avisos ligados) junto com o ensaio físico.
- [ ] Repetir a passada de design no app com o celular no cabo: as demais telas foram só lidas no código.
