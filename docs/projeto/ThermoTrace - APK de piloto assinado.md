---
tipo: entrega
projeto: ThermoTrace
contexto: pessoal-independente
atualizado: 2026-11-04
---

# APK de piloto assinado — Android 0.8.8

Semana 4 do [[ThermoTrace - Plano até 30-11-2026]], adiantada. Atende a primeira parte do TT-062: instalar uma versão assinada. A atualização sem perder dados será provada quando sair a próxima versão.

## O que foi gerado

- `ThermoTrace-v0.8.8-piloto-release.apk`, na pasta "01 - Aplicativo e APKs", com 15,7 MB e SHA-256 `e3fcadfa5dfe3651358fa2fdfa94864d6de3cf9dedf0cd6c91d4c834ea0d78b2`.
- Pacote `com.thermotrace.app` (sem o sufixo `.debug`). Assinatura v2 verificada pelo `apksigner`. Certificado "CN=ThermoTrace Piloto, O=ThermoTrace, C=BR", com impressão digital SHA-256 `AC:DE:50:DD:4F:73:3A:29:36:7D:B9:53:79:8D:6D:5A:EB:54:BC:06:B9:DD:67:82:22:E9:48:D1:E6:64:A0:69`.
- Só aceita servidor HTTPS; o endereço é digitado na tela Conta.

## Chave de assinatura — atenção

- **Onde está:** chave RSA 4096 (PKCS12, validade de 30 anos) em `C:\Users\lfgue\Projetos\_local-thermotrace\assinatura\`, com a senha em `senha.secret` na mesma pasta. **Fora do git e fora do OneDrive.**
- **Se perder a chave:** os celulares do piloto não aceitam atualização assinada com outra. Seria preciso desinstalar, e isso apaga as coletas locais que ainda não subiram.
- **Cópia de segurança (decisão do usuário, 04/11):** a chave foi copiada para `OneDrive\Documentos\ThermoTrace - Chave de assinatura (privado)\`, com um LEIA-ME; a cópia foi conferida por SHA-256. **A senha não foi junto, de propósito.** Pendente do usuário: guardar a senha (`senha.secret`) num gerenciador de senhas. Se ativar o Cofre Pessoal do OneDrive, mover a pasta para lá.
- **Como o build usa a chave:** lê o caminho de `local.properties` e a senha da variável `TT_ASSINATURA_SENHA`. Sem as duas, o release sai sem assinatura, e quem clona o repositório continua compilando.

## Diferença em relação ao APK de teste

A versão debug (`com.thermotrace.app.debug`) e a de piloto são **apps diferentes no celular**, cada um com seus dados. Antes de trocar, enviar ao servidor as coletas da debug (o envio automático faz isso quando há vínculo). As sessões sem vínculo continuam só no app debug.

## Distribuição

Para o piloto, a instalação é feita por cabo (`adb install`) ou pelo arquivo, com "instalar de fontes desconhecidas". Publicar na Google Play (trilha de teste interno) exige conta de desenvolvedor, que é uma contratação a avaliar em [[ThermoTrace - Contratações e custos a acompanhar]]. Nada foi contratado.
