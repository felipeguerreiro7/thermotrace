---
tipo: projeto
projeto: ThermoTrace
status: em-planejamento
atualizado: 2026-09-11
---

# Produto e fluxo de campo

## Jornada do cliente
1. Entrar com conta individual e selecionar empresa autorizada.
2. Criar ou localizar carga pelo código interno, nota, pedido ou conhecimento de transporte.
3. Cadastrar volumes; vincular etiqueta física e perfil térmico aprovado à sessão.
4. Identificar etiqueta e ativar em operação explícita. Registrar intenção e resultado; confirmação incerta exige verificação, nunca START automático repetido.
5. Em cada checkpoint, baixar histórico sem reiniciar nem parar a etiqueta. Registrar operador, local informado, aparelho, horários e resultado.
6. Mostrar gráfico parcial com data da última coleta, lacunas e pendências de sincronização.
7. No destino, fazer leitura final e salvar evidência antes de qualquer parada. Fechamento do processo e estado físico da etiqueta são campos separados.
8. Se o protocolo exigir, orientar outra aproximação para STOP; falha mantém “parada pendente”. Não prometer um único bip até validar no hardware.
9. Após sincronização e validação do servidor, disponibilizar versão final do gráfico e relatório. Offline: cópia provisória claramente marcada.

## Estados
Carga: preparação → em acompanhamento → fechamento pendente → concluída. Cancelamento guarda motivo e histórico.
Sessão física: ativação pendente → registro confirmado → parada pendente → parada confirmada; estado desconhecido deve ser explícito.
Sincronização: local → aguardando envio → recebido → validado ou rejeitado. “Salvo no celular” não significa “salvo no servidor”.

## Matriz de equivalência com o aplicativo do fornecedor
Tudo abaixo exige comparar mesma configuração, mesma etiqueta e evidências exportadas. “Código presente” não significa aprovado.

| Função | ThermoTrace observado | Aceite |
|---|---|---|
| Identificar UID e estado | Código presente | Mesmo UID/estado sem alterar registro |
| Configurar intervalo, início, delay e faixa | Base de ativação presente | Configuração relida coincide com o que foi enviado |
| START | Código presente | Medições começam no instante esperado |
| Leitura intermediária | Download presente | Histórico cresce e etiqueta continua registrando |
| Leitura final | Código presente | Histórico completo, unidades e horários conferem |
| STOP | Código presente | Parada confirmada em leitura posterior |
| Bateria, memória, falhas | Diagnóstico presente | Mensagens e estado coerentes com o fornecedor |
| Outras funções do chinês | Inventário incompleto | Registrar tela/comando, prioridade e resultado antes de alegar paridade total |

Retirada precoce do celular, perda de internet, bateria baixa, memória cheia, UID incorreto e dois celulares na mesma sessão entram nos testes.

O acompanhamento remoto se atualiza quando alguém lê e sincroniza. A existência de medições contínuas entre bipes depende da memória/autonomia da etiqueta; não haverá inventário de temperaturas por interpolação. Alertas serão de detecção na coleta, salvo hardware futuro com conectividade própria.
