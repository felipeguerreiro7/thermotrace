---
tipo: projeto
projeto: ThermoTrace
status: em-planejamento
atualizado: 2026-09-11
---

# Publicação e operação

## Ambientes e componentes
Desenvolvimento local, homologação com dados fictícios e produção separados. Backend FastAPI em serviço de contêineres, PostgreSQL gerenciado, armazenamento privado, identidade, tarefa de geração de relatórios e monitoramento. Portais consomem API autenticada; Android usa a mesma autorização.

Escolher provedor após levantar região dos clientes, contratos, orçamento, quantidade de etiquetas/cargas, frequência de amostras, retenção e volume de downloads. Não há estimativa financeira confiável sem essas entradas.

## Caminho até disponibilizar
1. Repositório privado com pipeline de testes, análise de dependências e revisão.
2. Criar homologação e segredos próprios; aplicar migrações com backup e validação.
3. Provisionar duas empresas fictícias e executar caminho completo e testes de isolamento.
4. Configurar domínio, HTTPS, backups, alertas de erros/fila e procedimento de incidente.
5. Gerar Android release assinado; proteger chave de assinatura e recuperação. Distribuir primeiro em trilha de teste apropriada, depois ampliar ao piloto.
6. Preparar informações da loja, política de privacidade, declarações de dados/permissões e conta de revisão quando exigida. Conferir regras vigentes no [Play Console](https://support.google.com/googleplay/android-developer/answer/9859152).
7. Promover versão homologada e acompanhar falhas; rollback da aplicação precisa ser compatível com migrações aditivas. Não reverter banco apagando evidência.

## Operação que precisa existir
Responsável de incidentes, alertas de indisponibilidade, fila atrasada, falhas de autenticação e geração de relatórios; teste de restauração recorrente, atualização de dependências, rotação de segredos e revogação de aparelhos perdidos. Logs não carregam tokens nem payload bruto de cliente.

Metas provisórias para discutir no piloto: RPO de até 24 horas e RTO de até 8 horas; só virar compromisso após definir backup e medir restauração. Para exigência menor, reavaliar recuperação ponto a ponto e custo. Monitorar taxa de bipes bem-sucedidos, atraso de sincronização, cobertura de medição e falhas de exportação.

## Critérios de liberação
Nenhum vazamento entre contas em testes; ciclo físico homologado; leitura sem internet preservada; relatórios reconstruíveis; recuperação comprovada; aceite do cliente piloto e responsável de qualidade. Contas, domínio, custos e dados de produção ainda dependem de definição concreta.


## Atualização — 13/09/2026

Domínio e provedor deixaram de ser escolhas em aberto: usuário comprou thermotrace.com.br, criou o GitHub felipeguerreiro7/thermotrace e enviou print do serviço Render thermotrace-api publicado. Detalhes verificados, pendências de DNS e sequência de publicação em [[ThermoTrace - Infraestrutura confirmada]]. Site iniciado em /portal, no mesmo serviço. Deploy do novo commit, contas fictícias, isolamento remoto, backups e homologação física continuam exigindo evidência própria.
