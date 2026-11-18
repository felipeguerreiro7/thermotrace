---
tipo: entrega
projeto: ThermoTrace
contexto: pessoal-independente
atualizado: 2026-11-18
---

# Demonstração do portal (API 0.14)

Entrega de 18/11/2026, branch `feat/portal-demo` (commits `70d44e5`, `11d816a`, `dfe07b6`). PR para `main` ainda não aberto quando esta nota foi escrita.

## O que mudou e por quê

Para apresentar o portal sem distribuir senha, a tela de login ganhou **Conhecer o portal**: um clique entra como dono da carga ou como transportadora das empresas fictícias criadas por `python -m app.demo_cli`.

- **API:** `GET /api/v1/demo` diz se a área aparece; `POST /api/v1/demo/entrar` recebe só o perfil (`dono` ou `transportadora`), nunca e-mail. O servidor escolhe a conta gestora pelo e-mail **e** pelo CNPJ fictícios e recusa conta inativa ou com MFA. Sessão de até 2 horas, limite de tentativas do login e registro `login_demonstracao` na auditoria.
- **Configuração:** `DEMO_PUBLICA`, desligada por padrão. A configuração recusa `DEMO_PUBLICA=true` com `AMBIENTE=producao`; o processo não sobe assim.
- **Portal:** a área só aparece quando o servidor a habilita. Durante a sessão, faixa amarela avisa que empresa, cargas e evidências são fictícias. Atalho "Só quer conhecer?" leva à área em tela baixa/celular. A transportadora fictícia começa em "Compartilhadas comigo", onde está a carga dela.
- **Contrato:** versão 0.14.0 e `backend/docs/openapi-0.14.json` (diff contra 0.13: só `/demo`, versão e descrição).

## Evidências

- `scripts/testar.ps1` em PostgreSQL 17 descartável: `alembic check` sem diferença, **206 testes de API** (201 + 5 em `tests/test_demo.py`).
- **17 testes web** e `node --check` do portal.
- Android sem mudança: `testDebugUnitTest` **152 testes** e `assembleDebug` aprovados na mesma máquina.
- Navegador, servidor local: entrada como dono (desktop) e como transportadora (375 px, sem rolagem horizontal).

## Ambiente local preparado

- Banco `thermotrace` e banco descartável `thermotrace_test` no PostgreSQL 17 local; `backend/.env` local (fora do Git) com `DEMO_PUBLICA=true`.
- Dados de demonstração carregados no banco local.

## O que falta

- [ ] Abrir o PR e fazer o merge em `main` (o Render acompanha `main`).
- [ ] No Render, a demonstração só liga com `DEMO_PUBLICA=true` no painel e `demo_cli` rodado contra aquele banco. Decidir se a homologação publicada deve ter acesso sem senha.
- [ ] Android não testado em aparelho nesta entrega; o app exige servidor `https://`, então não fala com a API local.
