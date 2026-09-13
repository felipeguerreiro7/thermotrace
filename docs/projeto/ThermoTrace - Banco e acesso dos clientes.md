---
tipo: projeto
projeto: ThermoTrace
status: em-planejamento
atualizado: 2026-09-11
---

# Banco e acesso dos clientes

## Proposta de arquitetura
Preservar Android/Kotlin + Room e backend Python/FastAPI/PostgreSQL. Acrescentar autenticação, armazenamento privado de evidências/relatórios e portais de cliente/equipe. Escolha de provedor e frontend ainda pendente; não há contratação nem publicação nesta etapa.

“Banco para cada cliente” pode significar dados privados por empresa ou bancos fisicamente separados. Proposta inicial: PostgreSQL compartilhado com isolamento por cliente no servidor e no banco. Caso o contrato exija banco dedicado, usar provisionamento por cliente e migrações coordenadas; confirmar antes de fechar infraestrutura.

## Modelo alvo e evolução da base
| Entidade | Conteúdo / regra |
|---|---|
| Cliente | Unidade contratante/dona dos dados; não confundir com transportador |
| Empresa participante | Embarcador, transportadora, destinatário |
| Associação de usuário | Usuário + cliente + papel + vigência; revisar relação atual de um usuário/empresa |
| Carga/remessa | UUID imutável, cliente proprietário, código único dentro do cliente, estado |
| Documento | Tipo, emissor, identificação, evidência, situação da validação |
| Carga-documento | Relação muitos-para-muitos: carga pode ter várias notas e nota pode aparecer em entregas parciais |
| Volume | Parte física da carga, identidade própria |
| Etiqueta e vínculo | UID + volume + vigência; troca gera novo vínculo, não substitui passado |
| Sessão | Ciclo de registro, configuração congelada, relógio, perfil e versões |
| Coleta | Cada bip: operador, dispositivo, tempos, finalidade, resposta bruta, recibo |
| Amostra | Sessão + índice original, temperatura, base temporal e proveniência |
| Evento de auditoria | Ação, objeto, ator, cliente, resultado, motivo e tempos |
| Relatório | Versão, corte de dados, parâmetros, arquivo, hash e vínculo ao anterior |
| Compartilhamento | Carga + participante + permissão + prazo + revogação |

## Código da carga: sim, vincular desde o início
Manter UUID interno e código legível. Nota fiscal é referência pesquisável; não deve ser a única identidade. Revisar `Remessa.identidade_documento` atualmente globalmente UNIQUE e identidade de volume derivada do documento. Migração deve preservar IDs antigos como aliases, detectar colisões e nunca juntar cargas automaticamente.

Transportadora/destinatário só enxergam cargas explicitamente compartilhadas. Mesmo documento em dois clientes não autoriza cruzamento. Identificação do cliente vem da autenticação e associação validada, não de um campo livre confiado ao app.

## Implementação do isolamento
- Adicionar proprietário às tabelas de negócio e integridade composta para impedir referência entre clientes; backfill verificado antes de NOT NULL.
- Autorizar cada rota e aplicar RLS nas tabelas pertinentes. Conexão de execução sem superusuário, BYPASSRLS, posse das tabelas ou TRUNCATE.
- Contexto de cliente por transação definido somente após validar identidade; conexões reutilizadas não podem carregar contexto anterior.
- Aplicar a mesma regra a objetos, filas, caches, exportações, busca e links de download.
- Testar A/B em leitura, inserção, atualização, referência cruzada e exportação, inclusive troca de conexão.

A documentação do [PostgreSQL](https://www.postgresql.org/docs/current/ddl-rowsecurity.html) explica que proprietário e papéis especiais podem contornar RLS; a configuração real precisa ser testada.
