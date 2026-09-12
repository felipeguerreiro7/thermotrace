from uuid import uuid4
from sqlalchemy import select, text, func, or_, and_

from app.core.errors import Conflito, NaoEncontrado, RegraDeNegocio
from app.models import ProdutoConfiguracao, PerfilTermico, Remessa, Documento, Volume
from app.models.enums import StatusRemessa
from app.services.auditoria import auditar
from app.services.idempotencia import hash_json


def perfil_disponivel(db, empresa_id, perfil_id):
    p = db.scalar(select(PerfilTermico).where(PerfilTermico.id == perfil_id,
        PerfilTermico.empresa_id == empresa_id).with_for_update(read=True))
    if p is None:
        raise NaoEncontrado()
    if not p.ativo or not p.aprovado_em or not p.evidencia:
        raise RegraDeNegocio("O perfil precisa estar aprovado e disponível para novas cargas.")
    return p


def produto_por_id(db, empresa_id, produto_id):
    p = db.scalar(select(ProdutoConfiguracao).where(ProdutoConfiguracao.id == produto_id,
        ProdutoConfiguracao.empresa_id == empresa_id).with_for_update(read=True))
    if p is None:
        raise NaoEncontrado()
    return p


def produto_resumo(db, p):
    perfil = db.scalar(select(PerfilTermico).where(PerfilTermico.id == p.perfil_termico_id,
                                                 PerfilTermico.empresa_id == p.empresa_id))
    return {"id": p.id, "codigo": p.codigo, "versao": p.versao, "ativo": p.ativo,
            "perfil_termico_id": p.perfil_termico_id, "intervalo_segundos": p.intervalo_segundos,
            "disponivel_para_nova_carga": bool(p.ativo and perfil and perfil.ativo and perfil.aprovado_em),
            "produto": (perfil.evidencia or {}).get("produto") if perfil else None,
            "apresentacao": (perfil.evidencia or {}).get("apresentacao") if perfil else None,
            "condicao": (perfil.evidencia or {}).get("condicao") if perfil else None,
            "min_c": str(perfil.min_c) if perfil else None,
            "max_c": str(perfil.max_c) if perfil else None}


def configurar_produto(db, acesso, body):
    db.execute(text("SELECT pg_advisory_xact_lock(hashtextextended(:chave,0))"),
               {"chave": f"produto:{acesso.empresa.id}:{body.codigo}"})
    ultimo = db.scalar(select(ProdutoConfiguracao).where(
        ProdutoConfiguracao.empresa_id == acesso.empresa.id, ProdutoConfiguracao.codigo == body.codigo)
        .order_by(ProdutoConfiguracao.versao.desc()).limit(1).with_for_update())
    if (ultimo.versao if ultimo else None) != body.versao_anterior:
        raise Conflito("O cadastro mudou. Consulte a versão atual antes de configurar novamente.")
    perfil_disponivel(db, acesso.empresa.id, body.perfil_termico_id)
    if ultimo:
        ultimo.ativo = False
        db.flush()  # Retira o anterior antes do INSERT; índice permite só um ativo por código.
    p = ProdutoConfiguracao(empresa_id=acesso.empresa.id, codigo=body.codigo,
        versao=1 if ultimo is None else ultimo.versao+1, perfil_termico_id=body.perfil_termico_id,
        intervalo_segundos=body.intervalo_segundos, criado_por=acesso.usuario.id)
    db.add(p); db.flush()
    auditar(db, "produto_configurado", "produto_configuracao", p.id, acesso.usuario,
            depois={"codigo": p.codigo, "versao": p.versao, "perfil_id": str(p.perfil_termico_id)})
    return produto_resumo(db, p)


def criterio(produto, perfil):
    return {"schema": 1,
            "produto": {"configuracao_id": str(produto.id), "codigo": produto.codigo, "versao": produto.versao},
            "perfil": {"id": str(perfil.id), "codigo": perfil.codigo, "versao": perfil.versao,
                       "min_c": str(perfil.min_c), "max_c": str(perfil.max_c),
                       "rotulo": perfil.rotulo, "tolerancia_segundos": perfil.tolerancia_segundos,
                       "alerta_min_c": str(perfil.alerta_min_c) if perfil.alerta_min_c is not None else None,
                       "alerta_max_c": str(perfil.alerta_max_c) if perfil.alerta_max_c is not None else None,
                       "tor_max_segundos": perfil.tor_max_segundos,
                       "mkt_limite_c": str(perfil.mkt_limite_c) if perfil.mkt_limite_c is not None else None,
                       "aprovado_em": perfil.aprovado_em.isoformat(), "aprovado_por": str(perfil.aprovado_por),
                       "evidencia": perfil.evidencia},
            "intervalo_segundos": produto.intervalo_segundos}


def documento_hash(body):
    if body.chave_acesso:
        return hash_json({"tipo": body.tipo.value, "chave_acesso": body.chave_acesso})
    return hash_json({"tipo": body.tipo.value, "numero": body.numero,
                      "serie": body.serie, "cnpj_emitente": body.cnpj_emitente})


def documento_resumo(d):
    return {"id": d.id, "tipo": d.tipo, "numero": d.numero, "serie": d.serie,
            "chave_acesso": d.chave_acesso, "cnpj_emitente": d.cnpj_emitente,
            "conteudo_bruto": d.conteudo_bruto, "digitado_manualmente": d.digitado_manualmente,
            "validado": d.validado, "observacao_validacao": d.observacao_validacao}


def inserir_documento(db, remessa, body):
    digest = documento_hash(body)
    # Documento legado não recebe hash retroativo nem é alterado. Conferir sua
    # identidade normalizada evita duplicá-lo ao acrescentar um vínculo novo.
    identidade_legada = (func.upper(func.trim(Documento.chave_acesso)) == body.chave_acesso
        if body.chave_acesso else and_(
            func.upper(func.trim(Documento.numero)) == body.numero,
            func.upper(func.trim(Documento.serie)) == body.serie,
            func.upper(func.trim(Documento.cnpj_emitente)) == body.cnpj_emitente))
    existente = or_(Documento.identidade_hash == digest,
                    and_(Documento.identidade_hash.is_(None), Documento.tipo == body.tipo, identidade_legada))
    if db.scalar(select(Documento.id).where(Documento.remessa_id == remessa.id, existente)):
        raise Conflito("Este documento já está vinculado a esta remessa.")
    d = Documento(remessa_id=remessa.id, identidade_hash=digest,
                  **body.model_dump(), validado=False,
                  observacao_validacao="Identificador armazenado; autenticidade e validade fiscal não verificadas.")
    db.add(d); db.flush()
    return d


def remessa_por_id(db, empresa_id, id_, escrita=False):
    q = select(Remessa).where(Remessa.id == id_, Remessa.empresa_embarcador_id == empresa_id)
    if escrita:
        q = q.with_for_update()
    r = db.scalar(q)
    if r is None:
        raise NaoEncontrado()
    return r


def remessa_resumo(r):
    return {"id": r.id, "codigo": r.codigo, "status": r.status, "criado_em": r.criado_em,
            "empresa_id": r.empresa_embarcador_id, "destinatario_nome": r.destinatario_nome,
            "produto_configuracao_id": r.produto_configuracao_id,
            "perfil_termico_id": r.perfil_termico_id, "intervalo_segundos": r.intervalo_segundos}


def detalhe_remessa(db, r):
    return {**remessa_resumo(r), "criterio": r.criterio_snapshot, "descricao_carga": r.descricao_carga,
            "documentos": [documento_resumo(d) for d in db.scalars(select(Documento).where(
                Documento.remessa_id == r.id).order_by(Documento.criado_em, Documento.id))],
            "volumes": [{"id": v.id, "sequencia": v.sequencia, "identidade": v.identidade,
                         "status": v.status, "monitorado": v.monitorado}
                        for v in db.scalars(select(Volume).where(Volume.remessa_id == r.id).order_by(Volume.sequencia))]}


def criar_remessa(db, acesso, body):
    produto = produto_por_id(db, acesso.empresa.id, body.produto_configuracao_id)
    if not produto.ativo:
        raise RegraDeNegocio("A configuração do produto foi substituída. Consulte o cadastro atual.")
    perfil = perfil_disponivel(db, acesso.empresa.id, produto.perfil_termico_id)
    hashes = [documento_hash(d) for d in body.documentos]
    if len(set(hashes)) != len(hashes):
        raise RegraDeNegocio("O mesmo documento aparece mais de uma vez na carga.")
    codigo = body.codigo or "REM-" + uuid4().hex[:20].upper()
    if db.scalar(select(Remessa.id).where(Remessa.empresa_embarcador_id == acesso.empresa.id, Remessa.codigo == codigo)):
        raise Conflito("Já existe uma remessa com este código na sua empresa.")
    r = Remessa(codigo=codigo, empresa_embarcador_id=acesso.empresa.id,
        produto_configuracao_id=produto.id, perfil_termico_id=perfil.id, criterio_snapshot=criterio(produto, perfil),
        intervalo_segundos=produto.intervalo_segundos, destinatario_nome=body.destinatario_nome,
        descricao_carga=body.descricao_carga, criada_por=acesso.usuario.id)
    db.add(r); db.flush()
    for d in body.documentos:
        inserir_documento(db, r, d)
    for seq in range(1, body.quantidade_volumes+1):
        db.add(Volume(remessa_id=r.id, sequencia=seq, identidade=f"{r.id}#V{seq:03}", monitorado=False))
    db.flush()
    auditar(db, "remessa_criada", "remessa", r.id, acesso.usuario,
            depois={"codigo": r.codigo, "produto_configuracao_id": str(produto.id),
                    "perfil_termico_id": str(perfil.id), "volumes": body.quantidade_volumes})
    return detalhe_remessa(db, r)


def adicionar_documento(db, acesso, id_, body):
    r = remessa_por_id(db, acesso.empresa.id, id_, escrita=True)
    if r.status != StatusRemessa.PREPARACAO:
        raise RegraDeNegocio("A inclusão de documentos está disponível apenas durante a preparação.")
    if db.scalar(select(func.count()).select_from(Documento).where(Documento.remessa_id == r.id)) >= 100:
        raise RegraDeNegocio("O limite de 100 documentos por remessa foi atingido.")
    d = inserir_documento(db, r, body)
    auditar(db, "documento_vinculado", "remessa", r.id, acesso.usuario,
            depois={"documento_id": str(d.id), "tipo": d.tipo})
    return documento_resumo(d)


def cancelar_remessa(db, acesso, id_, body):
    r = remessa_por_id(db, acesso.empresa.id, id_, escrita=True)
    if r.status != StatusRemessa.PREPARACAO:
        raise RegraDeNegocio("Somente uma remessa em preparação pode ser cancelada por este fluxo.")
    if db.scalar(select(Volume.id).where(Volume.remessa_id == r.id, Volume.monitorado.is_(True)).limit(1)):
        raise RegraDeNegocio("Carga com monitoramento iniciado exige tratamento específico; este cancelamento não encerra etiquetas.")
    r.status = StatusRemessa.CANCELADA
    auditar(db, "remessa_cancelada", "remessa", r.id, acesso.usuario, depois={"motivo": body.motivo})
    db.flush()
    return remessa_resumo(r)
