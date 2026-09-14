# ThermoTrace Android 0.8.6 — envio de coletas com recibos

Entrega de 14/09/2026. Projeto pessoal independente. Android 0.8.6-debug, código 18; Room 7. API continua 0.4, portal continua 0.1.

## Resultado

O operador da conta original pode vincular uma sessão local a uma carga e volume existentes no servidor e enviar início, checkpoints e final. Cada pedido é preservado antes da tentativa. A coleta só é marcada como sincronizada quando o recibo correspondente é validado e gravado no banco local. Esta entrega oferece envio manual por sessão; conectar à internet sozinho não dispara envio.

## Como testar

1. Instalar como atualização, preservando os dados. Não desinstalar nem apagar armazenamento para testar migração.
2. Entrar na conta da empresa antes de iniciar uma nova operação. A API deve ter carga com critério preservado, volume monitorado e etiqueta previamente cadastrada pelo UID.
3. Fazer uma nova ativação com esta versão. As respostas originais do START agora acompanham a configuração na gravação local. Concluir checkpoints e final conforme o fluxo já existente; envio não exige parar uma sessão ainda em andamento.
4. Abrir a remessa e tocar em **Enviar coletas do volume**. Buscar código ou documento da carga online, selecionar carga e volume e conferir o vínculo. Faixa e intervalo devem coincidir; divergências não são corrigidas por suposição.
5. Tocar em **Enviar coletas / tentar novamente**. Conferir os comprovantes recebidos. Novas coletas da sessão são incluídas ao tocar novamente em Enviar.

## Decisões de confiabilidade

- Servidor, empresa e operador permanecem fixos pelo escopo da coleta. Outra conta não assume autoria; renovação de token repete o corpo e a chave originais.
- Vínculo de carga/volume fica registrado antes do primeiro envio, com autor e instante. Não é inferido por número de NF. A tela não permite trocar um vínculo já confirmado.
- A instalação é cadastrada com pedido persistido e metadados iniciais estáveis, inclusive após troca de operador ou atualização do APK. É identidade declarada pelo app, sem atestação física.
- Resposta completa do callback de START preservada nas novas ativações. Sessão e evidência de início são gravadas em transação nos dois fluxos de ativação; nenhum comando NFC adicional foi acrescentado.
- Cada envio guarda rota, corpo exato, chave, hash local, tentativas, erro e recibo. Recebidos são mantidos para consulta. Persistência de recibo, atualização de sincronização e retirada do item antigo correspondente são atômicas.
- HTTP 409, falha de rede, recibo incorreto ou falha SQLite mantêm a coleta pendente e interrompem a sequência. Repetir não troca conteúdo, identidade ou chave para esconder conflito.
- O JSON original da fila conserva a separação das strings brutas, incluindo espaços, Unicode, quebras de linha e o separador usado pelo conversor antigo do Room. Não há truncamento silencioso para caber no contrato.
- Exclusão de teste por toque longo fica desabilitada na conta vinculada; chaves estrangeiras impedem apagar sessões com vínculo de envio.
- Migração 6→7 somente acrescenta tabelas de vínculo, pedido e aparelho. Não atribui registros legados a uma conta nem fabrica recibos.

## Validação executada

- **149 testes JVM**, sem falhas, incluindo contrato, limites, recibos trocados, autoria e reenvio após renovação do token.
- Compilação dos APKs e lint sem erros; 24 avisos de lint.
- Emulador Android/Room real: pedido recebido com resposta perdida, HTTP 409, recibo de outro volume, erro SQLite ao confirmar, rollback local, reabertura e repetição dos mesmos bytes. Coletas e fila preservadas; recibos gravados uma vez, alertas não apagados e outra conta impedida de enviar.
- Migrações sintéticas 2/3/4/5/6→7 e isolamento entre servidor/empresa/operador passaram. V2–V4 reconstruídas; V5/V6 derivadas dos schemas exportados. Migração 1→2 e atualização de banco físico real ainda pendentes.
- **142 testes da API**, sem falhas ou ignorados, em PostgreSQL 17 descartável. O teste novo recebe os pedidos da fixture conferida pelo construtor Kotlin, substitui somente IDs do catálogo sintético, executa início/checkpoint/final, repete cada pedido e confere autoria, bruto e cadeia de integridade. TestClient HTTP em processo; não é tráfego do celular até o Render.
- APK final instalado e aplicativo aberto no emulador. Não houve ensaio com etiqueta física nem homologação do seletor/carga online no A57.

## Limites e próximos passos

- Ativações anteriores sem resposta original de START continuam locais. Não recriar evidência, usar checkpoint como START ou reativar a etiqueta para forçar envio. Preservar o laudo e definir depois um fluxo auditado de importação de legado, se necessário.
- Histórico do modo local não é associado automaticamente à conta. Coletas de um operador não são reenviadas por outro; continuidade da mesma sessão entre aparelhos ainda requer implementação.
- Localização, confirmação de STOP, ocorrências, documentos e laudo completo continuam locais neste envio. A API recebe a evidência bruta prevista no contrato 0.4; recebimento não significa temperatura validada, STOP confirmado ou produto liberado.
- Ainda faltam envio em segundo plano, tratamento operacional de vínculo incorreto, importação/continuidade entre aparelhos, cadastro de inventário pelo portal e consolidação das temperaturas/gráficos centrais.
- Reteste físico checkpoint/final/STOP e TT-005 permanecem abertos. Confirmar URL pública do Render, DNS/HTTPS e catálogo de homologação antes do teste integrado real.

Nenhuma nova contratação ou mudança de infraestrutura. O banco de testes é local e descartável; nenhum dado real foi enviado. GitHub é a publicação do código, não uma confirmação de deploy Render ou de prontidão operacional.

## Reproduzir

Android: `gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`.
Instrumentação: `adb shell am instrument -w com.thermotrace.app.debug.test/com.thermotrace.app.EscopoInstrumentation`.
Fixture compartilhada: `backend/docs/contrato-android-0.8.6.json`; testes normais comparam com o contrato produzido pelo Kotlin. Para alteração intencional, regenerar usando `'-Ptt.gerarContrato=true'` e revisar o diff.
API: `backend/scripts/testar.ps1` com URL explícita de banco descartável terminado em `_test`, conforme CONTRIBUTING.md. Não usar o banco do Render.

## Código publicado

[6699e60](https://github.com/felipeguerreiro7/thermotrace/commit/6699e600f2b21c518aaf104b3c46877ee578ec78), main local e remoto conferidos em 14/09/2026. APK SHA-256: `6617fb5b6b65e100193200c05fea73866fa23bfce23968259fb1a53ab82c2ac1`. Atualização documental acompanha em commit separado.
