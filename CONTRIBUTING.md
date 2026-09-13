# Como continuar a ThermoTrace

ThermoTrace é um projeto pessoal independente. Não incluir documentos, contas ou clientes do empregador.

## Rotina de entrega acordada em 13/09/2026

1. Ler a Central, as Decisões e o Plano de ação no Obsidian antes de alterar o produto. O histórico anterior permanece como histórico; a atualização mais recente indica o estado atual.
2. Consultar o estado do Git e preservar mudanças existentes. Trabalhar no repositório canônico, sem substituir arquivos por cópias antigas.
3. Implementar uma entrega delimitada e executar os testes relevantes. Distinguir testes automatizados, ensaio físico, publicação no GitHub e publicação no Render.
4. Atualizar as notas da ThermoTrace no Obsidian com o que foi feito, motivo, evidências e o que falta. Copiar esse conjunto para `docs/projeto` com `tools/sincronizar_obsidian.py --vault-project "CAMINHO_DA_PASTA_THERMOTRACE"`.
5. Revisar o diff e os arquivos incluídos. Não versionar `.env`, senhas, tokens, URLs com credenciais, chaves de assinatura, dados de clientes, bancos locais, caches ou builds.
6. Criar commits que expliquem **o que mudou e por quê**, com a validação e limites no corpo. Usar arquivo UTF-8 para mensagens multilinha. Exemplo: `feat(portal): consultar cargas por empresa`, seguido do motivo e dos testes executados.
7. Enviar ao GitHub e conferir que o commit remoto corresponde ao local. A autorização do usuário inclui esse envio em cada entrega; só relatar publicação quando confirmada.
8. Conferir separadamente o deploy no Render. O serviço acompanha `main`; um push pode disparar publicação automática. Alterações em `render.yaml` podem mudar infraestrutura e custo: explicar o impacto antes de realizá-las. Não criar outro serviço para o portal atual.

Repositório: https://github.com/felipeguerreiro7/thermotrace (manter privado enquanto a licença do SDK do fabricante não estiver esclarecida).

## Validação do portal

Sem dependências JavaScript adicionais:

```text
node --check backend/app/portal/assets/app.mjs
node --test backend/tests_web/*.test.mjs
```

Backend: seguir `backend/scripts/testar.ps1`, usando PostgreSQL descartável cujo nome termine em `_test`. Nunca executar a suíte contra o banco do Render. O script aplica e confere migrações antes dos testes. Não há migração nova na entrega Portal 0.1.

O Android tem validação própria; mudanças apenas no portal não equivalem a um novo APK ou a ensaio NFC.
