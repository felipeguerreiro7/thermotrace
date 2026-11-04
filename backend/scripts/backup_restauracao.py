"""Backup, restauração e conferência do banco ThermoTrace (WEB-05 / TT-061).

Uso (PowerShell, dentro de backend/, com o venv):
  python scripts/backup_restauracao.py backup --saida C:\\backups\\tt.dump
  python scripts/backup_restauracao.py restaurar --arquivo C:\\backups\\tt.dump
  python scripts/backup_restauracao.py conferir

- Origem em DATABASE_URL e destino em RESTAURO_DATABASE_URL (variáveis de ambiente); nada de senha em argumento
  de linha de comando ou em log. As senhas vão para o pg_dump/pg_restore por PGPASSWORD.
- O destino da restauração precisa ser um banco VAZIO e diferente da origem.
- "conferir" compara origem e destino tabela a tabela (quantidade e resumo SHA-256
  das linhas) e recalcula a viagem consolidada de cada sessão nos dois bancos:
  restauração só conta como comprovada se o relatório sai idêntico.
- Exige pg_dump/pg_restore da mesma versão maior do servidor (17). Caminho em PG_BIN.
"""
import argparse
import hashlib
import os
import shutil
import subprocess
import sys
from pathlib import Path
from urllib.parse import urlparse, unquote

RAIZ = Path(__file__).resolve().parents[1]


def libpq(url):
    """Separa a senha da URL: o processo filho recebe a URL sem senha e PGPASSWORD no ambiente."""
    u = urlparse(url.replace('+psycopg', ''))
    if u.scheme not in ('postgresql', 'postgres') or not u.path.strip('/'):
        raise SystemExit('URL de banco inválida.')
    sem_senha = u._replace(netloc=f"{unquote(u.username or '')}@{u.hostname}{':' + str(u.port) if u.port else ''}").geturl()
    env = dict(os.environ, PGPASSWORD=unquote(u.password or ''))
    return sem_senha, env, u.path.strip('/'), (u.hostname, u.port or 5432, u.path.strip('/'))


def binario(nome):
    pasta = os.environ.get('PG_BIN')
    caminho = Path(pasta) / (nome + ('.exe' if os.name == 'nt' else '')) if pasta else shutil.which(nome)
    if not caminho or not Path(caminho).exists():
        raise SystemExit(f'{nome} não encontrado. Defina PG_BIN com a pasta bin do PostgreSQL 17.')
    return str(caminho)


def sha256(arquivo):
    h = hashlib.sha256()
    with open(arquivo, 'rb') as f:
        for bloco in iter(lambda: f.read(1 << 20), b''):
            h.update(bloco)
    return h.hexdigest()


def backup(args):
    url, env, _, _ = libpq(os.environ['DATABASE_URL'])
    saida = Path(args.saida)
    if saida.exists():
        raise SystemExit('O arquivo de saída já existe; não sobrescrevo backup.')
    subprocess.run([binario('pg_dump'), '--format=custom', '--no-owner', '--no-privileges', '--file', str(saida), url],
                   env=env, check=True)
    digest = sha256(saida)
    Path(str(saida) + '.sha256').write_text(f'{digest}  {saida.name}\n', encoding='ascii')
    print(f'Backup gravado: {saida} ({saida.stat().st_size} bytes)\nSHA-256: {digest}')


def restaurar(args):
    destino, env, nome, alvo = libpq(os.environ['RESTAURO_DATABASE_URL'])
    if 'DATABASE_URL' in os.environ and libpq(os.environ['DATABASE_URL'])[3] == alvo:
        raise SystemExit('Destino igual à origem. Restaure sempre em um banco separado e vazio.')
    esperado = Path(args.arquivo + '.sha256')
    if esperado.exists() and esperado.read_text(encoding='ascii').split()[0] != sha256(args.arquivo):
        raise SystemExit('SHA-256 do backup não confere com o registrado. Arquivo alterado ou corrompido.')
    import psycopg
    with psycopg.connect(os.environ['RESTAURO_DATABASE_URL'].replace('+psycopg', '')) as c:
        if c.execute("SELECT count(*) FROM pg_tables WHERE schemaname='public'").fetchone()[0]:
            raise SystemExit('O banco de destino não está vazio. Use um banco novo.')
    # --exit-on-error: restauração parcial não pode passar por completa.
    subprocess.run([binario('pg_restore'), '--no-owner', '--no-privileges', '--exit-on-error', '--dbname', destino, args.arquivo],
                   env=env, check=True)
    print(f'Restaurado em {nome}. Reaplique as permissões do papel da API (scripts/runtime-permissoes.sql) antes de usar.')


def resumo_tabelas(url):
    import psycopg
    with psycopg.connect(url.replace('+psycopg', '')) as c:
        c.execute('SET row_security = off')  # Conferência com o dono do banco: todas as linhas, sem filtro de empresa.
        tabelas = [r[0] for r in c.execute("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY 1")]
        # Proteções também precisam voltar: políticas RLS, RLS forçado e gatilhos de imutabilidade.
        protecoes = c.execute("""SELECT
            (SELECT string_agg(x, ',' ORDER BY x) FROM (SELECT tablename||'.'||policyname||'.'||cmd||'.'||coalesce(qual,'')||'.'||coalesce(with_check,'') x
                FROM pg_policies WHERE schemaname='public') p),
            (SELECT string_agg(relname::text, ',' ORDER BY relname::text) FROM pg_class WHERE relforcerowsecurity AND relnamespace='public'::regnamespace),
            (SELECT string_agg(x, ',' ORDER BY x) FROM (SELECT tgrelid::regclass::text||'.'||tgname x FROM pg_trigger WHERE NOT tgisinternal) t),
            (SELECT count(*) FROM pg_policies WHERE schemaname='public'),
            (SELECT count(*) FROM pg_class WHERE relforcerowsecurity AND relnamespace='public'::regnamespace),
            (SELECT count(*) FROM pg_trigger WHERE NOT tgisinternal)""").fetchone()
        out = {nome: (protecoes[i + 3], hashlib.sha256((protecoes[i] or '').encode()).hexdigest())
               for i, nome in enumerate(['(políticas RLS)', '(RLS forçado)', '(gatilhos)'])}
        for t in tabelas:
            linhas = c.execute(f'SELECT md5(t::text) FROM public."{t}" t ORDER BY 1').fetchall()
            out[t] = (len(linhas), hashlib.sha256(''.join(r[0] for r in linhas).encode()).hexdigest())
        return out


def viagens(url):
    """Relatório de cada sessão recalculado pelo mesmo código do portal."""
    from sqlalchemy import create_engine, select, text
    from sqlalchemy.orm import Session
    sys.path.insert(0, str(RAIZ))
    from app.models import LeituraEtiqueta, Remessa, SessaoMonitoramento
    from app.services import ingestao, viagem
    engine = create_engine(url if '+psycopg' in url else url.replace('postgresql://', 'postgresql+psycopg://'))
    out = {}
    with Session(engine) as db:
        db.execute(text('SET row_security = off'))
        for s in db.scalars(select(SessaoMonitoramento).where(SessaoMonitoramento.contrato_ingestao == '1')):
            r = db.get(Remessa, s.remessa_id)
            ls = list(db.scalars(select(LeituraEtiqueta).where(LeituraEtiqueta.sessao_id == s.id)
                                 .order_by(LeituraEtiqueta.ordem_recebimento)))
            integra = ingestao.verificar_integridade(db, r.empresa_embarcador_id, s.id)['integra']
            out[str(s.id)] = (integra, viagem.consolidar(s, ls, r, integra)['hash_projecao'])
    engine.dispose()
    return out


def conferir(args):
    origem = os.environ['DATABASE_URL']
    a, b = resumo_tabelas(origem), resumo_tabelas(os.environ['RESTAURO_DATABASE_URL'])
    problemas = [t for t in sorted(set(a) | set(b)) if a.get(t) != b.get(t)]
    for t in sorted(a):
        print(f"{'OK ' if t not in problemas else 'DIF'} {t}: {a[t][0]} linhas")
    for t in problemas:
        print(f'DIFERENTE: {t} origem={a.get(t)} destino={b.get(t)}')
    va, vb = viagens(origem), viagens(os.environ['RESTAURO_DATABASE_URL'])
    viagens_dif = [s for s in va if va[s] != vb.get(s)] + [s for s in vb if s not in va]
    print(f'Viagens recalculadas: {len(va)} na origem, {len(vb)} no destino, {len(viagens_dif)} diferentes.')
    if problemas or viagens_dif:
        raise SystemExit('Restauração NÃO comprovada.')
    print('Restauração comprovada: mesmas linhas em todas as tabelas e mesmos relatórios de viagem.')


def main():
    p = argparse.ArgumentParser(description='Backup, restauração e conferência do banco ThermoTrace.')
    sub = p.add_subparsers(dest='cmd', required=True)
    b = sub.add_parser('backup'); b.add_argument('--saida', required=True)
    r = sub.add_parser('restaurar'); r.add_argument('--arquivo', required=True)
    sub.add_parser('conferir')
    args = p.parse_args()
    {'backup': backup, 'restaurar': restaurar, 'conferir': conferir}[args.cmd](args)


if __name__ == '__main__':
    main()
