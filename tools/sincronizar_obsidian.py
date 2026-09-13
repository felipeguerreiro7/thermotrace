"""Copia apenas notas do projeto pessoal para versionamento; não copia o cofre."""
import argparse
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--vault-project', type=Path, required=True)
    args = parser.parse_args()
    origem = args.vault_project.resolve(strict=True)
    if not origem.is_dir() or origem.name != 'ThermoTrace':
        parser.error('Informe somente a pasta ThermoTrace do Obsidian, não a raiz do cofre.')
    destino = Path(__file__).resolve().parents[1] / 'docs' / 'projeto'
    destino.mkdir(parents=True, exist_ok=True)
    notas = [p for p in origem.glob('ThermoTrace - *.md') if p.is_file() and not p.is_symlink()]
    if not notas:
        parser.error('Nenhuma nota ThermoTrace encontrada.')
    for nota in notas:
        # Não apagar notas antigas automaticamente: exclusões exigem revisão do diff.
        (destino / nota.name).write_text(nota.read_text(encoding='utf-8-sig'), encoding='utf-8', newline='\n')
    print(f'{len(notas)} notas do projeto copiadas. Revise o diff antes do commit.')


if __name__ == '__main__':
    main()
