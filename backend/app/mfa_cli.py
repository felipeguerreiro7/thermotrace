"""Desliga a MFA de uma conta que perdeu o celular. Exige acesso direto ao banco.

Uso: python -m app.mfa_cli --email pessoa@empresa --motivo "Celular perdido, conferido por telefone"
A pessoa configura de novo no próximo acesso. Fica registrado na auditoria.
"""
import argparse
from sqlalchemy import select

from app.db.session import SessaoLocal
from app.models import Usuario
from app.services.auditoria import auditar


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--email", required=True)
    parser.add_argument("--motivo", required=True)
    args = parser.parse_args()
    if len(args.motivo.strip()) < 10:
        raise SystemExit("Descreva o motivo (mínimo 10 caracteres).")
    with SessaoLocal.begin() as db:
        user = db.scalar(select(Usuario).where(Usuario.email == args.email.strip().lower()).with_for_update())
        if user is None:
            raise SystemExit("Conta não encontrada.")
        user.mfa_segredo = None; user.mfa_ativado_em = None; user.mfa_ultimo_passo = None
        auditar(db, "mfa_redefinida_pelo_suporte", "usuario", user.id, None, empresa_id=user.empresa_id,
                depois={"motivo": args.motivo.strip(), "via": "mfa_cli"})
    print("MFA desligada. A pessoa configura de novo no próximo acesso.")


if __name__ == "__main__":
    main()
