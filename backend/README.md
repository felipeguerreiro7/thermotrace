# Atualização de 12/09/2026 — servidor 0.4

Recepção de ativação/checkpoint/final com evidência bruta, vínculo de carga/volume, autoria, recibos e cadeia de integridade implementada. **126 testes passaram** em PostgreSQL descartável. Contrato, exemplos, migrações, permissões e limites em [ENTREGA-0.4.md](ENTREGA-0.4.md). O Android 0.8.0 ainda não envia por essas rotas; não há implantação pública ou validação física.

---

> **Atualização 0.3 — 11/09/2026:** cargas, documentos, produtos configurados e reenvio idempotente implementados. Instruções e limites atuais em [ENTREGA-0.3.md](ENTREGA-0.3.md); contrato em [OpenAPI](docs/openapi-0.3.json). O texto anterior abaixo descreve etapas históricas da base.

# ThermoTrace — Backend

API do sistema de monitoramento térmico de remessas por etiqueta NFC.

**Fase 3 concluída:** servidor, banco, migrações, erros, logs e testes. Os
endpoints de negócio entram nas Fases 4 e 5.

---

## Tecnologias

| | |
|---|---|
| Python | 3.12 |
| FastAPI | 0.141.1 |
| SQLAlchemy | 2.0.52 (estilo 2.0, `Mapped`/`mapped_column`) |
| Alembic | 1.19.1 |
| PostgreSQL | 17 |
| Logs | structlog |
| Testes | pytest |

---

## Estrutura de pastas

```
backend/
├── app/
│   ├── main.py              monta a aplicação, middlewares e rotas
│   ├── core/
│   │   ├── config.py        lê o .env e valida na importação
│   │   ├── logging.py       structlog + request_id em toda linha
│   │   └── errors.py        exceções → resposta HTTP padronizada
│   ├── api/v1/              um arquivo por recurso
│   ├── models/              tabelas SQLAlchemy (25)
│   ├── schemas/             contratos Pydantic
│   ├── services/            regras de negócio — não sabem que HTTP existe
│   ├── db/
│   │   ├── base.py          Base, mixins, convenção de nomes
│   │   ├── session.py       engine, sessão por requisição
│   │   └── migrations/      Alembic
│   └── workers/             e-mail e expurgo (Fase 5)
├── tests/
├── .env.example
├── requirements.txt
├── Dockerfile
└── docker-compose.yml
```

**A regra que mantém isso saudável:** `api/` só traduz HTTP; `services/` tem
toda a regra e não importa nada do FastAPI; `models/` não conhece Pydantic.
Assim o mesmo serviço atende a API, um script de linha de comando e um teste
sem adaptação.

---

## Instalação

### 1. Pré-requisitos

- Python 3.12
- PostgreSQL 17

### 2. Ambiente virtual e dependências

No **PowerShell**, dentro de `backend/`:

```powershell
python -m venv .venv
.venv\Scripts\Activate.ps1
pip install -r requirements.txt
```

> `.venv` isola as bibliotecas deste projeto das do sistema. Sem isso, dois
> projetos com versões diferentes da mesma biblioteca brigam.

### 3. Banco de dados

```powershell
psql -U postgres -c "CREATE ROLE thermotrace LOGIN PASSWORD 'senha_local';"
psql -U postgres -c "CREATE DATABASE thermotrace OWNER thermotrace;"
psql -U postgres -d thermotrace -c "CREATE EXTENSION IF NOT EXISTS pgcrypto; CREATE EXTENSION IF NOT EXISTS citext;"
```

`pgcrypto` gera os UUIDs; `citext` faz o e-mail ser case-insensitive.

### 4. Configuração

```powershell
copy .env.example .env
python -c "import secrets; print(secrets.token_urlsafe(48))"
```

Cole o resultado em `SECRET_KEY` no `.env`. A aplicação **recusa subir** com
a chave de exemplo — melhor falhar no start do que descobrir em produção.

### 5. Migrações

```powershell
alembic upgrade head
```

Cria as 25 tabelas e os gatilhos de imutabilidade.

---

## Execução

```powershell
uvicorn app.main:app --reload
```

| Endereço | O que é |
|---|---|
| http://127.0.0.1:8000/docs | Swagger (interativo) |
| http://127.0.0.1:8000/redoc | ReDoc (leitura) |
| http://127.0.0.1:8000/api/v1/saude | processo vivo? |
| http://127.0.0.1:8000/api/v1/saude/completa | processo **e** banco vivos? |

Em produção `/docs`, `/redoc` e `/openapi.json` são desligados
automaticamente — mapa da API é informação de reconhecimento.

---

## Testes

```powershell
pytest
```

Os testes rodam contra o banco real, cada um numa transação com rollback no
fim. Não sujam dados e não dependem de ordem — dependência de ordem é o jeito
mais rápido de ter uma suíte que passa na sua máquina e falha no servidor.

O arquivo que mais importa é `tests/test_imutabilidade.py`: é a prova, em
código, de que a evidência de auditoria não pode ser alterada. **Se ele
quebrar, o produto perdeu a característica que o justifica.**

---

## Docker

```powershell
docker compose up -d db      # só o banco (API pelo venv)
docker compose up --build    # tudo
docker compose logs -f api   # acompanhar
docker compose down          # parar
docker compose down -v       # parar E APAGAR os dados
```

> `docker-compose.yml` é de **desenvolvimento**. O de produção, com Caddy e
> HTTPS automático, sai na Fase 9.

---

## Decisões que valem conhecer antes de mexer

**O banco é a autoridade da auditoria, não o Python.** Os gatilhos de
imutabilidade, o encadeamento de hash e as restrições `UNIQUE` ficam no
PostgreSQL. Um bug no Python não consegue driblar um gatilho de banco.

**Sincronização assimétrica.** Evidência sobe (append-only, nunca editada);
catálogo desce (servidor é a fonte da verdade). Nenhum registro é escrito nos
dois lados — o que **elimina** o problema de conflito em vez de resolvê-lo.

**Idempotência em todo POST.** O app tem retry exponencial no WorkManager.
`Idempotency-Key` torna o reenvio inofensivo por construção.

**Erro tem um formato só:**

```json
{
  "erro": "etiqueta_ja_vinculada",
  "mensagem": "Etiqueta TT-A7K9P2X4 já está em outra remessa ativa.",
  "detalhes": {},
  "request_id": "5d71b627f53d408f"
}
```

`erro` é o código estável que o `when` do Kotlin usa. `mensagem` é a frase
pronta para a tela — **o app não monta texto de erro**, então melhorar a
redação não exige publicar APK novo.

**Migração escrita, nunca destrutiva.** Sem `drop and recreate`: apagar
evidência de auditoria numa atualização é o mesmo que destruir a prova.

---

## Variáveis de ambiente

Ver `.env.example`. As obrigatórias:

| Variável | O que é |
|---|---|
| `DATABASE_URL` | conexão com o PostgreSQL |
| `SECRET_KEY` | assinatura dos tokens. **Nunca** a de exemplo. |
| `AMBIENTE` | `local`, `homologacao` ou `producao` |

O `.env` está no `.gitignore`. Credencial em Git não volta atrás.

---

## Próximas fases

| Fase | Escopo |
|---|---|
| 4 | Autenticação: usuário + dispositivo, JWT, refresh rotativo |
| 5 | APIs: catálogo de etiquetas, ingestão de leitura, laudo |
| 5.5 | Correção do outbox no app (hoje envia UUID, não dados) |
| 6 | Integração app ↔ backend |
| 7–11 | Testes, segurança, Docker de produção, deploy, documentação |
