---
tipo: plano
projeto: ThermoTrace
contexto: pessoal-independente
atualizado: 2026-11-04
---

# Plano até 30/11/2026 — piloto pronto

Pedido do usuário em 04/11/2026: concluir o projeto até o fim do mês. "Concluir" segue o critério de piloto pronto da [[ThermoTrace - Central do projeto]]: dois clientes isolados, operadores identificados, leitura real validada contra o fornecedor, sincronização sem perda/duplicação, fechamento explícito, gráfico e relatório reproduzíveis, restauração de backup comprovada e acesso ThermoTrace auditado.

## Situação em 04/11

| Critério | Situação | Responsável |
|---|---|---|
| Clientes isolados, operadores, acesso auditado | Código pronto (WEB-10, WEB-11) | — |
| Gráfico e relatório reproduzíveis | Por coleta pronto; falta a viagem consolidada (WEB-12) | Desenvolvimento |
| Sincronização sem perda | Envio manual pronto; falta fila automática no Android | Desenvolvimento (ambiente Android a instalar) |
| Fechamento explícito | Código pronto; falta ensaio físico | Usuário + desenvolvimento |
| Leitura real validada (TT-005, A57) | Aberto; maior risco | Usuário, com etiqueta e celular |
| Publicação Render/domínio/HTTPS | Sem conferência; URL pública não registrada | Usuário (painel) + desenvolvimento |
| Backup e restauração | Não feito; pode exigir plano pago | Usuário decide custo; desenvolvimento faz roteiro |

## Semanas

1. **Até 09/11:** MFA da equipe ThermoTrace; ambiente Android neste computador e suíte JVM rodando. Usuário: URL do Render e status dos deploys.
2. **Até 16/11:** fila automática de envio no Android; visão da viagem consolidada no portal (WEB-12). Usuário: ensaio físico com roteiro fornecido (TT-005, checkpoint/final/STOP).
3. **Até 23/11:** correções do ensaio; ensaio ponta a ponta no ambiente publicado (WEB-04); backup e restauração.
4. **Até 30/11:** APK de piloto, revisão final, notas e lista do que fica para depois.

Risco principal: o ensaio físico. Se ele atrasar, o prazo atrasa junto; o restante não depende dele.

## Andamento

- 04/11: MFA da equipe ThermoTrace concluída e publicada no GitHub (semana 1, item 1).
- 04/11: ambiente Android montado e suíte JVM rodando (150 testes). Fila automática de envio adiantada da semana 2: Android 0.8.8, ver [[ThermoTrace - Envio automático Android 0.8.8]]. Roteiro do ensaio físico pronto: [[ThermoTrace - Roteiro do ensaio físico de novembro]].
- Pendente do usuário: URL do Render; ensaio físico; A57 no cabo para os testes instrumentados.
- 04/11: viagem consolidada no portal (WEB-12) adiantada da semana 2: [[ThermoTrace - Viagem consolidada WEB-12]]. Do software previsto para as semanas 1 e 2, falta só executar os testes instrumentados no aparelho.
- 04/11: backup e restauração comprovados localmente (semana 3 adiantada): [[ThermoTrace - Backup e restauração]]. Falta repetir com o banco do Render, o que depende do painel e da decisão de plano.
- 04/11: APK de piloto assinado gerado (semana 4 adiantada): [[ThermoTrace - APK de piloto assinado]]. Pendente do usuário: guardar cópia da chave de assinatura.
- 04/11 (tarde): cópia da chave feita no OneDrive, sem a senha; falta guardar a senha num gerenciador.
- 04/11: testes automáticos no GitHub Actions a cada push (API, portal e Android): `.github/workflows/testes.yml`. Comprovam o "clone limpo compila" do TT-010 e são a base do TT-062. Primeira execução verde na 3ª tentativa (ajustes: pacote `platforms;android-37.0` e padrão de arquivos do `node --test`).
- 04/11: acompanhamento da operação no portal (API 0.10): etiquetas sem leitura final com fim previsto da memória e atraso de envio dos celulares. [[ThermoTrace - Acompanhamento da operação]].
- 04/11: **URL do Render encontrada:** `https://thermotrace-api.onrender.com` responde `/api/v1/saude` com versão 0.10.0 e ambiente homologação; o deploy automático pelo GitHub está funcionando. Portal em `/portal`, rotas protegidas (401 sem login). Faltava HSTS (corrigido no código). O domínio `thermotrace.com.br` ainda não tem DNS apontado: pendente do usuário no registro do domínio + Custom Domain no Render. Isso destrava o WEB-04 (ensaio ponta a ponta no ambiente publicado) sem esperar o domínio.
- **Atenção, 04/11:** o repositório no GitHub está **público** (abre sem login, inclusive o `nfcinstruct`, código do SDK do fabricante sem licença conhecida). A decisão registrada em [[ThermoTrace - Decisões e pendências]] era mantê-lo privado. Mudar a visibilidade é ação do usuário: GitHub → Settings → General → Danger Zone → Change visibility.
