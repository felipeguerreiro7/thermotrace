---
tipo: entrega
projeto: ThermoTrace
contexto: pessoal-independente
atualizado: 2026-11-04
---

# Compartilhamento de cargas · WEB-10 · API 0.6

Entrega de 04/11/2026, projeto pessoal ThermoTrace. Retomada depois de 14/09/2026, em computador novo: Git, Python 3.12, Node e PostgreSQL 17 instalados; a base 0.5 foi conferida antes da mudança (158 testes API e 13 web aprovados nesta máquina).

## O que está implementado

O dono da carga (empresa que cadastrou a remessa) compartilha **uma carga específica** com uma transportadora, informando o CNPJ dela. O acesso só começa depois do **aceite do gestor da transportadora**. Qualquer das duas partes encerra o compartilhamento; o encerramento vale na requisição seguinte.

- **Escopo:** sempre consultar (carga, documentos, volumes, sessões, comprovantes, integridade e gráfico). Exportar CSV/SVG/relatório só quando o dono marca a permissão. A evidência bruta continua só com o dono.
- **Fora do escopo desta versão:** a transportadora **não opera** a carga do dono: não inclui documento, não cancela e não inicia sessão. Isso fica para a etapa de operação entre empresas.
- **Prazo:** opcional. Sem prazo, vale até ser encerrado. Depois do prazo o acesso para sozinho, e o dono pode convidar de novo.
- **Quem decide:** gestor do dono convida e encerra; gestor da transportadora aceita, recusa ou sai. Operadores da transportadora consultam apenas cargas já aceitas.
- **Nada é inferido:** nome de destinatário, NF ou o campo antigo `empresa_transportadora_id` não dão acesso. Para CNPJ desconhecido, inativo ou de empresa que não é transportadora, a resposta é a mesma, para o convite não servir de consulta de cadastro.

## Proteções

- Barreira dupla: filtro explícito em cada rota **e** RLS no PostgreSQL. A transportadora ganhou políticas só de leitura (`FOR SELECT`) nas tabelas da carga concedida.
- As políticas de escrita de documento, volume, leitura, série e excursão passaram a citar o dono explicitamente. Antes elas herdavam o escopo pela visibilidade da remessa; abrir a leitura sem essa correção abriria também a escrita.
- O registro do compartilhamento não pode ser apagado. A identidade dele (carga, partes, escopo, prazo) é imutável, e cada decisão (aceite, recusa ou revogação) só pode ser gravada uma vez e pela parte certa. Tudo isso é garantido por gatilho no banco.
- Auditoria: convite, aceite, recusa e revogação aparecem nas duas empresas. Cada consulta ou exportação da transportadora aparece também na auditoria do dono, com a empresa que acessou e o compartilhamento usado.

## Portal

- Dono (gestor): quadro "Compartilhamento com transportadora" na página da carga, com CNPJ, permissão de exportar, prazo opcional, histórico e "Encerrar acesso".
- Transportadora: menu "Compartilhadas comigo", com convites para o gestor aceitar ou recusar e as cargas ativas para consultar. Na carga, um aviso mostra de quem ela é e o que é permitido.

## Evidências e limites

- 166 testes API/PostgreSQL aprovados (158 anteriores + 8 novos), `alembic check` sem divergência. Testes novos cobrem o critério de aceite com três empresas (A, B, T) e cargas X/Y: X visível só após aceite, Y e a carga de B invisíveis, exportação negada sem escopo, revogação imediata pelo dono e pela parceira, recusa, prazo vencido, decisões pela parte errada, RLS por SQL direto sem `WHERE` e fluxo HTTP com o papel mínimo de `runtime-permissoes.sql`.
- 15 testes web aprovados (13 anteriores + 2 da chave de idempotência nas ações do portal).
- Ensaio no navegador do app, em servidor local com banco de demonstração e dados explicitamente fictícios: convite pelo dono, aceite pela transportadora, consulta até o gráfico, CSV bloqueado com mensagem clara, auditoria do dono mostrando as consultas e encerramento do acesso. Duas correções saíram desse ensaio: formulário oculto aparecendo e botão "Sair" redundante num convite pendente. Largura de celular não foi conferida nesta rodada.
- Tentativas negadas (por exemplo, exportação sem permissão) não geram linha de auditoria, como nas versões anteriores.
- Sem teste físico novo. TT-005 e checkpoint/final/STOP no A57 seguem abertos.

## Publicação

Migração nova `b10411040001`, aplicada pelo `preDeployCommand` do Render (`alembic upgrade head`). O serviço do Render usa a mesma `DATABASE_URL` para migrar e rodar, então não precisa de permissão extra. Instalação com o papel restrito `thermotrace_runtime` precisa aplicar `backend/scripts/runtime-compartilhamento-permissoes.sql` depois da migração; sem isso até a leitura de remessas falha, porque as políticas consultam a tabela nova. Nenhuma mudança em `render.yaml`, serviço novo ou custo.

## Próximos passos

1. WEB-03/04: confirmar Render/domínio e ensaiar com contas de homologação, agora incluindo um compartilhamento real entre duas empresas de teste.
2. Operação entre empresas (próxima etapa do WEB-10): permitir que a transportadora bipe na carga do dono, com etiqueta e aparelho dela e a autoria preservada. Exige decidir de quem é a etiqueta e quem fecha a sessão.
3. WEB-11: cadastro de empresas/usuários e convites pelo portal, já que hoje a transportadora precisa estar cadastrada pela equipe ThermoTrace.
4. WEB-12 restante: consolidação da viagem entre coletas.

## Publicação no GitHub — 04/11/2026

Código enviado em aa6786a e notas em e18eed2; SHA remoto conferido igual ao local. O envio não comprova deploy bem-sucedido no Render: conferir no painel se a migração b10411040001 rodou e se /api/v1/saude/completa responde.

## Atualização — 10/11/2026

O item 2 dos próximos passos (operação entre empresas) foi entregue na API 0.12: [[ThermoTrace - Operação entre empresas]]. O trecho "Fora do escopo desta versão" acima descreve a API 0.6.
