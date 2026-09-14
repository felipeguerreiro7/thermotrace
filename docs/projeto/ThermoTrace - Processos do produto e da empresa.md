---
tipo: desenho-de-processos
projeto: ThermoTrace
contexto: pessoal-independente
atualizado: 2026-09-14
status: estruturacao-inicial
---

# Processos do produto e da empresa

Pedido do usuário em 14/09/2026: continuar a estabilização do app e, depois, desenhar os processos do aplicativo, do site e da própria empresa. Esta nota organiza a próxima etapa; não declara processos já implementados ou aprovados.

## Ordem de trabalho

1. Estabilizar checkpoint, final/STOP, exportação e atualização de banco com evidência de testes.
2. Conectar coleta local à carga online com isolamento, autoria, fila idempotente e recibo persistido.
3. Detalhar os processos abaixo com responsável, entrada, saída, prazo, falha e registro de auditoria. As telas devem seguir esses processos.
4. Ensaiar com transportadora e dono da carga fictícios; revisar dúvidas e passos demorados antes do piloto.

## Quem participa

- Cliente: dono da carga; informa identificação e requisitos do produto e acompanha a própria carga.
- Contratante: transportadora; organiza a execução do transporte e os operadores autorizados.
- Operador: pessoa identificada que realiza ativação, checkpoints, recebimento e tratamento operacional.
- Staff ThermoTrace: cadastro, suporte, faturamento e operação da plataforma, conforme a função. Suporte a uma carga exige acesso autorizado e auditado.
- Responsável de qualidade: a designar; define a conduta para desvio, documentação insuficiente e liberação. O aplicativo não transforma uma excursão em autorização automática.

## Aplicativo — percurso principal

Entrar na conta → selecionar/localizar carga por código ou documento → confirmar produto/perfil/volume → identificar etiqueta → confirmar ativação → checkpoints ao longo do trajeto → leitura final → confirmar gravação → solicitar STOP → registrar sua resposta → sincronizar e guardar recibos → disponibilizar relatório reproduzível.

Cada etapa precisa mostrar o estado real: aguardando bipe, lendo, gravando, salvo localmente, pendente de envio, recebido pelo servidor ou exigindo conferência. Copiar um arquivo, receber uma coleta e confirmar STOP são fatos diferentes.

Exceções a desenhar: nota ilegível (digitação manual), etiqueta errada, retirada precoce, falta de rede/GPS, armazenamento insuficiente, token expirado, conta trocada, coleta divergente, STOP recusado, relatório incompleto e aparelho perdido.

## Site — percurso principal

Cadastro autorizado das empresas → usuários e permissões → dono cadastra carga e critério → vínculo com transportadora por carga → aceite → acompanhamento das evidências recebidas → conferência das pendências → relatório/exportação autorizados → consulta histórica.

Cliente e transportadora podem participar da mesma remessa sem compartilhar todas as suas cargas. Devem ficar claros o dono do dado, quem pode operar, quem pode apenas consultar, quem pode exportar e quem pode revogar o vínculo.

Exceções: convite vencido, recuperação de acesso, operador desativado, transportadora substituída, entrega parcial, nota usada em mais de uma carga, vínculo revogado, evidência atrasada e correção após relatório emitido. Correções preservam o original e criam um novo registro identificável.

## Empresa ThermoTrace — processos a desenhar

| Processo | Resultado esperado | Registro mínimo |
| --- | --- | --- |
| Comercial e diagnóstico | Escopo do cliente e fluxo de carga compreendidos | Necessidade, produto/apresentação, volume, participantes e critério de sucesso |
| Proposta e contratação | Serviço e responsabilidades definidos | Escopo, preço, responsáveis, prazo e limites acordados |
| Implantação | Empresa e equipe conseguem executar o fluxo | Cadastro, permissões, configuração revisada e treinamento |
| Etiquetas e aparelhos | Equipamentos identificados e aptos ao ensaio | Fornecedor, identificação, versão, testes e condição de uso |
| Suporte e incidentes | Falha identificada, contida e acompanhada | Remessa/evento, versão, impacto, responsável, evidência e solução |
| Qualidade e desvios | Pendências recebem uma decisão responsável | Desvio, justificativa, decisão, autor e data |
| Cobrança e custos | Receita e despesas conciliadas | Contrato, consumo, cobrança, pagamento e custo por operação |
| Segurança e continuidade | Acesso revogável e recuperação demonstrável | Revisão de acessos, backup, restauração, incidente e ação |
| Saída do cliente | Encerramento com destino dos registros definido | Revogação, exportação autorizada e retenção acordada |

## Medidas para avaliar o piloto

Tempo para ativar e concluir uma carga; bipes concluídos sem repetição; coletas perdidas/duplicadas; atraso da fila; finais sem STOP confirmado; relatórios reproduzíveis; tempo para resolver incidentes. Os alvos serão definidos depois do ensaio, sem prometer prazos ou níveis de serviço que ainda não foram medidos.

Próxima sessão de desenho: acompanhar uma carga fictícia do cadastro ao relatório e registrar cada passagem entre cliente, transportadora, operador e staff. Usar esse percurso para priorizar as telas e as regras do site.

## Processo implementado para primeiro envio — 14/09/2026

Na 0.8.6, o vínculo é por sessão: operador entra antes da nova ativação, coleta, abre a remessa, escolhe carga/volume online, confirma destino e toca em Enviar. Sem recibo válido salvo, o estado continua pendente. No erro, repetir o mesmo envio; em conflito, preservar e revisar, sem gerar uma identidade nova para contornar a recusa.

Definir na próxima rodada o responsável por criar carga/catálogo, corrigir vínculo incorreto, atender conflito, receber evidência de outro aparelho e decidir sobre legado sem START original. Ainda não há procedimento operacional homologado. [[ThermoTrace - Envio de coletas Android 0.8.6]].
