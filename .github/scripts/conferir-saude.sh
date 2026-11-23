#!/usr/bin/env bash
# Confere o serviço publicado (TT-066). Usado pelo workflow monitoramento.yml e roda igual
# num terminal comum:  bash .github/scripts/conferir-saude.sh https://thermotrace-api.onrender.com
#
# Saída: uma linha por problema em $PROBLEMAS (padrão: problemas.txt) e código 1 se houver algum.
# Três conferências:
#   1. /api/v1/saude/completa responde 200 com banco ok (três tentativas: um pico de lentidão
#      não vira alarme).
#   2. a versão publicada é a mesma da main; diferença só conta depois de 45 min do último
#      commit que mudou a versão, para não acusar um deploy em andamento. É o que pega uma
#      migração que falhou no preDeploy: o Render mantém a versão antiga no ar, calado.
#   3. a página inicial e o portal abrem (o site também é produto).
# Não usa segredo nem conta: só endereços públicos.
set -u
BASE="${1:-${URL_MONITORADA:-https://thermotrace-api.onrender.com}}"
BASE="${BASE%/}"
PROBLEMAS="${PROBLEMAS:-problemas.txt}"
TOLERANCIA_MIN="${TOLERANCIA_MIN:-45}"
: > "$PROBLEMAS"
problema() { echo "$1" >> "$PROBLEMAS"; echo "PROBLEMA: $1"; }
http() { [ -z "$1" ] || [ "$1" = "000" ] && echo "sem resposta (fora do ar ou tempo esgotado)" || echo "HTTP $1"; }

corpo=""; codigo=""
for tentativa in 1 2 3; do
  resposta=$(curl -s -m 45 -w '\n%{http_code}' "$BASE/api/v1/saude/completa" || true)
  codigo=$(printf '%s' "$resposta" | tail -n1)
  corpo=$(printf '%s' "$resposta" | sed '$d')
  [ "$codigo" = "200" ] && break
  [ "$tentativa" -lt 3 ] && sleep 20
done
echo "saude/completa: HTTP ${codigo:-sem resposta} ${corpo}"

if [ "$codigo" != "200" ]; then
  problema "Saúde completa: $(http "$codigo") em 3 tentativas${corpo:+: $corpo}"
else
  banco=$(printf '%s' "$corpo" | python3 -c 'import json,sys; print(json.load(sys.stdin)["dependencias"]["banco"])' 2>/dev/null || echo "ilegível")
  [ "$banco" = "ok" ] || problema "Banco de dados: $banco"

  publicada=$(printf '%s' "$corpo" | python3 -c 'import json,sys; print(json.load(sys.stdin)["versao"])' 2>/dev/null || echo "")
  arquivo="backend/app/core/config.py"
  if [ -f "$arquivo" ]; then
    esperada=$(grep -oE 'VERSAO: str = "[^"]+"' "$arquivo" | grep -oE '[0-9][^"]*')
    if [ -n "$publicada" ] && [ -n "$esperada" ] && [ "$publicada" != "$esperada" ]; then
      desde=$(git log -1 --format=%ct -- "$arquivo" 2>/dev/null || echo 0)
      minutos=$(( ( $(date +%s) - desde ) / 60 ))
      echo "versão publicada $publicada, main $esperada, mudança há $minutos min"
      if [ "$desde" -gt 0 ] && [ "$minutos" -ge "$TOLERANCIA_MIN" ]; then
        problema "Deploy travado: publicada $publicada, main pede $esperada há $minutos min. Confira o deploy e o preDeploy (alembic) no painel do Render."
      fi
    fi
  fi
fi

for pagina in "/" "/portal"; do
  c=$(curl -s -m 45 -o /dev/null -w '%{http_code}' -H 'Accept: text/html' "$BASE$pagina" || true)
  echo "página $pagina: HTTP ${c:-sem resposta}"
  [ "$c" = "200" ] || problema "Página $pagina: $(http "$c")"
done

if [ -s "$PROBLEMAS" ]; then exit 1; fi
echo "Tudo certo em $BASE"
