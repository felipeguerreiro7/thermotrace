# Origem das fixtures

Somente estruturas e dados sintéticos. `v5.json` deriva do schema 5 exportado pelo Room a partir do código de `ca28bde`. Versões 2, 3 e 4 são reconstruções: removem somente as colunas introduzidas pelas migrações 2→3, 3→4 e 4→5, conferidas no histórico do código. Não são cópias de bancos de celulares antigos.

O ensaio cria bancos isolados aleatórios, insere uma linha por tabela, abre usando o Room atual e verifica a estrutura e a preservação de todas as colunas antigas. Também verifica que a confirmação de cópia não é inventada e que um segundo STOP não sobrescreve o primeiro. Nenhum dado do usuário é apagado.

Schemas 5 e 6 exportados pelo compilador ficam em `app/schemas`. O caminho 1→2 e atualização de um banco físico real continuam pendentes; não declarar toda a história de migração homologada com estas fixtures.
