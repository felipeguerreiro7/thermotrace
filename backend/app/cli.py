"""Provisionamento inicial local. Nunca recebe senha em argumento ou imprime credenciais."""
import argparse
from getpass import getpass
from sqlalchemy import select
from app.core.security import hasher
from app.db.session import SessaoLocal
from app.models import Empresa, Usuario
from app.models.enums import PapelUsuario, TipoEmpresa
from app.schemas.acesso import NovoCliente
from app.services.auditoria import auditar


def main():
    parser = argparse.ArgumentParser(description="Criar o primeiro administrador da ThermoTrace.")
    parser.add_argument("--cnpj", required=True)
    parser.add_argument("--razao-social", required=True)
    parser.add_argument("--nome", required=True)
    parser.add_argument("--email", required=True)
    args = parser.parse_args()
    senha = getpass("Senha inicial (mínimo 12 caracteres): ")
    if senha != getpass("Repita a senha: "):
        raise SystemExit("As senhas não coincidem.")
    # Reutiliza validação de identidade; o papel plataforma só existe neste bootstrap.
    dados = NovoCliente(cnpj=args.cnpj, razao_social=args.razao_social,
                        gestor={"nome": args.nome, "email": args.email, "senha": senha})
    with SessaoLocal.begin() as db:
        from sqlalchemy import text
        db.execute(text("SELECT pg_advisory_xact_lock(hashtextextended('thermotrace-bootstrap', 0))"))
        if db.scalar(select(Empresa.id).where(Empresa.tipo == TipoEmpresa.PLATAFORMA)):
            raise SystemExit("A plataforma já foi provisionada; bootstrap recusado.")
        empresa = Empresa(cnpj=dados.cnpj, razao_social=dados.razao_social, tipo=TipoEmpresa.PLATAFORMA)
        db.add(empresa); db.flush()
        usuario = Usuario(empresa_id=empresa.id, nome=dados.gestor.nome,
                          email=str(dados.gestor.email).lower(), senha_hash=hasher.hash(senha),
                          papel=PapelUsuario.ADMIN)
        db.add(usuario); db.flush()
        auditar(db, "bootstrap_plataforma", "usuario", usuario.id, usuario)
    print("Administrador criado. Use o login da API; nenhuma credencial foi exibida.")


if __name__ == "__main__":
    main()
