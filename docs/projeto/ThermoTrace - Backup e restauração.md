---
tipo: procedimento
projeto: ThermoTrace
contexto: pessoal-independente
atualizado: 2026-11-04
---

# Backup e restauração (WEB-05 / TT-061)

Semana 3 do [[ThermoTrace - Plano até 30-11-2026]], adiantada. Critério do piloto: "restauração de backup comprovada".

## Ferramenta

`backend/scripts/backup_restauracao.py`, com três comandos:

- **`backup --saida arquivo.dump`:** `pg_dump` em formato custom e um `.sha256` ao lado. Nunca sobrescreve um arquivo existente.
- **`restaurar --arquivo arquivo.dump`:** confere o SHA-256 e só restaura em banco **vazio e diferente da origem**. Para no primeiro erro; restauração pela metade não passa por completa.
- **`conferir`:** compara origem e destino tabela a tabela (quantidade de linhas e resumo SHA-256 das linhas). Compara também as **proteções** (políticas RLS, RLS forçado e gatilhos de imutabilidade) e **recalcula a viagem consolidada** de cada sessão nos dois bancos. Só diz "comprovada" se tudo for idêntico.

As URLs com senha ficam em variáveis de ambiente (`DATABASE_URL` para a origem, `RESTAURO_DATABASE_URL` para o destino), nunca em argumento. `PG_BIN` aponta para o `bin` do PostgreSQL 17 instalado neste PC.

## Prova executada neste PC, 04/11/2026

- **Origem:** banco de demonstração com dados fictícios (cargas, sessão, leituras, compartilhamentos, códigos de acesso, MFA). O backup ficou com 172 KB.
- **Restauração:** em banco novo, sem erros.
- **Conferência:** todas as tabelas idênticas, 28 políticas RLS, 14 tabelas com RLS forçado e 17 gatilhos iguais, e a viagem recalculada com o mesmo hash.
- **Recusas:** um arquivo com um único bit alterado foi recusado pelo SHA-256, e a restauração sobre banco não vazio também foi recusada.

## Roteiro para o Render (usuário + desenvolvimento)

1. No painel do Render, abrir o banco `thermotrace-homologacao-db` e conferir se o plano atual tem backup automático, de que tipo e por quanto tempo. **Não contratar nada sem registrar o custo** em [[ThermoTrace - Contratações e custos a acompanhar]].
2. Para backup manual: copiar a **External Database URL** do painel e colar **só no seu terminal**, em `$env:DATABASE_URL`. Nunca em chat, nota ou print.
3. No `backend/`, com o venv: `python scripts/backup_restauracao.py backup --saida <pasta segura>\tt-AAAAMMDD.dump`.
4. Provar a restauração num banco local vazio: criar o banco, definir `$env:RESTAURO_DATABASE_URL`, rodar `restaurar` e depois `conferir`.
5. Guardar o `.dump` e o `.sha256` fora do computador (por exemplo, em pasta privada do OneDrive). O dump contém dados de clientes e hashes de senha: tratar como confidencial.

## Limites

- Restaurar exige um papel com permissão de ignorar RLS (superusuário ou BYPASSRLS), porque as tabelas têm RLS forçado. No Render, o usuário do banco gerenciado deve servir; confirmar na primeira restauração.
- Depois de restaurar, reaplicar `scripts/runtime-permissoes.sql` se a API usar o papel restrito.
- Ainda não existe rotina automática de backup nem alerta de falha. Definir a frequência e o RPO/RTO junto com a decisão de plano (meta provisória em [[ThermoTrace - Publicação e operação]]: RPO de 24 h, RTO de 8 h).
