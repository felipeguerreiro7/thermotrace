# Imagem enxuta: slim em vez de alpine porque psycopg com wheel binario nao
# precisa compilar em glibc, e alpine (musl) obrigaria a compilar.
FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

# Dependencias antes do codigo: o Docker reaproveita esta camada em todo
# build em que requirements.txt nao mudou, o que corta minutos do deploy.
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app/ ./app/
COPY alembic.ini ./

# Nao roda como root. Se alguem escapar do processo, escapa para um usuario
# sem privilegio.
RUN useradd --create-home --uid 10001 thermotrace \
 && mkdir -p /app/dados/arquivos \
 && chown -R thermotrace:thermotrace /app
USER thermotrace

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://localhost:8000/api/v1/saude').status==200 else 1)"

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
