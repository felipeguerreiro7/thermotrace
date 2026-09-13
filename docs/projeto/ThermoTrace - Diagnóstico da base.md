---
tipo: projeto
projeto: ThermoTrace
status: em-planejamento
atualizado: 2026-09-11
---

# Diagnóstico da base existente

Fonte primária: inspeção dos arquivos locais em 2026-09-11, em `C:/Users/lfgue/ThermoTrace`. Leitura estática, sem executar o app ou o banco original.

| Área | Evidência encontrada | Próxima ação |
|---|---|---|
| Android | Kotlin, Compose, Room, NFC; versionName 0.7.2 | Compilar e validar versão em bancada |
| NFC | NfcOperator com identificar, ativar, download, diagnóstico, STOP e caminho legado Encerrar | Mapear chamadas das telas; remover ambiguidade leitura final versus STOP |
| Cargas | Remessas, volumes, etiquetas, documento fiscal e scanner | Rever identidade fiscal única e múltiplas cargas por documento |
| Relatórios | ExportadorLaudo e EscritorXlsx, tela RelatorioScreen | Conferir conteúdo real; adicionar gráfico exportável e PDF canônico |
| Sincronização | Repositorio monta OutboxPayload com dados reais | Confirmar consumidor, autenticação, ACK e compatibilidade do contrato |
| Servidor | FastAPI, SQLAlchemy, modelos e migrações Alembic | Implementar autenticação e rotas de negócio |
| Rotas | api/v1 registra somente saude | Login, ingestão, carga, consulta e relatórios ainda pendentes |
| Auditoria | Migração bloqueia UPDATE/DELETE de evidências | Testar com papel real; rever TRUNCATE, privilégios e exportação externa |
| Clientes | Empresa, Usuario, Dispositivo; embarcador/transportadora na remessa | Implementar autorização; modelos existentes não comprovam isolamento |
| Versionamento | Pasta consultada não reconhecida como repositório Git | Localizar repositório canônico ou criar um com revisão dos arquivos e segredos |

## Documentação que não deve orientar implementação sem revisão
- README raiz descreve baixar e desligar na mesma aproximação; histórico da tarefa “Corrigir app com código original” descreve operações separadas na 0.7.0/0.7.1. Código atual ainda contém caminho Encerrar: validar o fluxo acessível.
- README do backend diz que a fila envia apenas UUID; Repositorio.kt atualmente monta payload real. Tratar correção como existente, integração como pendente.
- README declara compatibilidade de iPhone; não há teste físico de iPhone realizado nesta retomada. Não usar essa declaração como homologação.
- Existem SQL antigos em inglês e modelos atuais em português. Proposta: evoluir Alembic/backend como fonte do esquema; comparar diferenças antes de aposentar SQL legado.
- Testes antigos relatados na conversa não equivalem a testes executados agora.

## Fontes técnicas consultadas
- [NFC Android](https://developer.android.com/develop/connectivity/nfc/advanced-nfc): base para integração nativa e tecnologias de etiqueta.
- [PostgreSQL RLS](https://www.postgresql.org/docs/current/ddl-rowsecurity.html): políticas por linha, incluindo exceções de proprietário e BYPASSRLS.
- [Publicação Android](https://support.google.com/googleplay/android-developer/answer/9859152): configuração do aplicativo e preparação no Play Console.

## Evidência do usuário, 2026-09-11
“O aplicativo fica cheio de erros, bipa quando quer, não parece tão confiável, não gera os gráficos de maneira correta.” Primeiros clientes serão parceiros da área de saúde. Isso invalida qualquer interpretação de “código presente” como “funcionalidade pronta”. Causa raiz ainda não identificada. Coletar logs e amostras reproduzíveis antes de atribuir o problema ao NFC, firmware ou gráfico.
