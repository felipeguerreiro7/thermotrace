"""Cadastro local de uma etiqueta em lote existente, executado pelo operador de implantação."""
import argparse
from uuid import UUID
from pydantic import Field
from sqlalchemy import select, text
from app.schemas.acesso import Entrada
from app.schemas.ingestao import UID
from app.models import Usuario, Empresa, LoteEtiqueta, Etiqueta
from app.models.enums import PapelUsuario, TipoEmpresa
from app.core.errors import RegraDeNegocio, Conflito
from app.db.session import SessaoLocal
from app.services.auditoria import auditar


class CadastroEtiqueta(Entrada):
    empresa_id: UUID
    lote_id: UUID
    serial: str = Field(min_length=1,max_length=64)
    qr_payload: str = Field(min_length=1,max_length=2048)
    uid_canonico: UID


def cadastrar(db, admin_id, body):
    u=db.get(Usuario,admin_id)
    if not u or not u.ativo or u.papel!=PapelUsuario.ADMIN or db.get(Empresa,u.empresa_id).tipo!=TipoEmpresa.PLATAFORMA:
        raise RegraDeNegocio('Identifique um administrador ativo da plataforma para registrar a autoria.')
    e=db.get(Empresa,body.empresa_id);lote=db.get(LoteEtiqueta,body.lote_id)
    if not e or not e.ativa or e.tipo==TipoEmpresa.PLATAFORMA or not lote:
        raise RegraDeNegocio('Cliente ativo e lote previamente cadastrado são obrigatórios.')
    db.execute(text('SELECT pg_advisory_xact_lock(hashtextextended(:k,0))'),{'k':'catalogo:'+body.uid_canonico})
    if db.scalar(select(Etiqueta.id).where(Etiqueta.nfc_uid==body.uid_canonico)):
        raise Conflito('UID já cadastrado; não transferir nem substituir silenciosamente.')
    tag=Etiqueta(empresa_id=e.id,lote_id=lote.id,serial=body.serial,qr_payload=body.qr_payload,nfc_uid=body.uid_canonico)
    db.add(tag);db.flush()
    auditar(db,'etiqueta_cadastrada_localmente','etiqueta',tag.id,u,empresa_id=e.id,
            depois={'lote_id':str(lote.id),'origem':'inventario_declarado_sem_validacao_fisica'})
    return tag.id


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--admin-id',type=UUID,required=True)
    for name in ['empresa-id','lote-id','serial','qr-payload','uid-canonico']:p.add_argument('--'+name,required=True)
    args=vars(p.parse_args());admin=args.pop('admin_id');body=CadastroEtiqueta(**args)
    with SessaoLocal.begin() as db:id_=cadastrar(db,admin,body)
    print(f'Etiqueta registrada: {id_}. Cadastro não confirma calibração ou identidade física.')

if __name__=='__main__':main()
