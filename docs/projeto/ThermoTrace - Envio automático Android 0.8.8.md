---
tipo: entrega
projeto: ThermoTrace
contexto: pessoal-independente
atualizado: 2026-11-04
---

# Android 0.8.8 — envio automático das coletas

Entrega de 04/11/2026, semana 1 do [[ThermoTrace - Plano até 30-11-2026]]. Android 0.8.8-debug, código 20; Room continua na versão 7, sem migração. Primeira compilação Android neste computador: JDK 25 (exigido pelo daemon do Gradle 9.7.1), JDK 21 e Android SDK 37 instalados, com a licença da Google aceita por autorização do usuário.

## O que muda para o operador

Depois de vincular uma sessão a carga e volume (uma vez, como na 0.8.6), as coletas seguintes **sobem sozinhas**: logo após cada registro, quando a internet volta e a cada 15 minutos. O botão "Enviar coletas" continua funcionando como antes.

## Como foi feito

- **Mesmo caminho do botão:** um trabalho do WorkManager chama o `EnvioColetas.enviar()` de sempre, com os mesmos pedidos preservados e as mesmas chaves de idempotência. O recibo é conferido e gravado antes de marcar a coleta, e o envio para na primeira falha de cada sessão.
- **O que entra na fila:** só sessões já vinculadas, porque a fila não escolhe carga sozinha. Também não envia por outra conta: o escopo é o da conta conectada.
- **Login expirado:** a fila termina sem insistir e volta a rodar ao entrar de novo na conta. Falha de rede ou do servidor tenta de novo com espera crescente (até 8 vezes; depois disso, o ciclo de 15 minutos continua).
- **Sem envio duplicado:** a trava de envio passou a valer para o processo inteiro, por conta. Tela e fila nunca transmitem o mesmo pedido ao mesmo tempo.
- **Tela atualizada:** o banco usa invalidação entre instâncias, então a tela vê os recibos gravados pela fila.
- **Agendamento:** abertura do app, troca de conta, cada coleta registrada (gancho `aoRegistrar` no `LeituraViewModel`, depois do commit local) e o ciclo periódico.

## Evidências

- 150 testes JVM aprovados, APK 0.8.8-debug gerado e lint sem erros.
- APK de teste instrumentado compilado. O caso novo dispara duas filas ao mesmo tempo na mesma conta e confere que cada pedido é transmitido uma única vez. **Ainda não executado:** precisa de emulador ou do A57 no cabo.
- APK: `ThermoTrace-v0.8.8-envio-automatico-debug.apk` em "01 - Aplicativo e APKs", SHA-256 `cf7f3f6069b96afd08a282eed27c2c0ef0bc70524e5dbf1960675be6953cacab`.
- Sem ensaio físico. O teste em modo avião e o tempo até o comprovante aparecer no portal estão no [[ThermoTrace - Roteiro do ensaio físico de novembro]].

## Limites

- O vínculo carga/volume continua manual, uma vez por sessão.
- A fila envia início, checkpoints e final. Localização, confirmação de STOP, ocorrências e documentos continuam locais, como na 0.8.6.
