"""WEB-10: carga compartilhada por autorização explícita, aceite e revogação.

O dono (empresa cadastrante da remessa) convida uma transportadora pelo CNPJ.
A transportadora só lê depois do aceite do gestor dela. Qualquer parte encerra.
Escopo: consultar sempre; exportar e operar só quando o dono permitir.
Operar = iniciar o monitoramento de um volume e enviar leituras, com etiqueta e
aparelho da transportadora. Documentos e cancelamento seguem só com o dono.

Cada filtro aqui é explícito além do RLS: o banco é a segunda barreira, não a única.
"""
from sqlalchemy import select, or_, text

from app.core.errors import Conflito, NaoEncontrado, RegraDeNegocio, SemPermissao
from app.core.security import agora
from app.models import CompartilhamentoRemessa, Empresa, Remessa
from app.models.enums import StatusRemessa, TipoEmpresa
from app.services.auditoria import auditar
from app.services.cargas import andamentos as andamentos_das_cargas, remessa_por_id, remessa_resumo

TIPOS_PARCEIRA = (TipoEmpresa.TRANSPORTADORA, TipoEmpresa.AMBOS)


def situacao(c, momento=None):
    momento = momento or agora()
    if c.recusado_em:
        return "recusado"
    if c.revogado_em:
        return "revogado"
    if c.expira_em and c.expira_em <= momento:
        return "expirado"
    return "ativo" if c.aceito_em else "pendente"


def vigente_para(db, remessa_id, empresa_id):
    return db.scalar(select(CompartilhamentoRemessa).where(
        CompartilhamentoRemessa.remessa_id == remessa_id,
        CompartilhamentoRemessa.empresa_parceira_id == empresa_id,
        CompartilhamentoRemessa.aceito_em.is_not(None),
        CompartilhamentoRemessa.recusado_em.is_(None),
        CompartilhamentoRemessa.revogado_em.is_(None),
        or_(CompartilhamentoRemessa.expira_em.is_(None), CompartilhamentoRemessa.expira_em > agora())))


def operaveis_por(empresa_id):
    """Subconsulta: remessas de outras empresas que esta pode operar agora."""
    return select(CompartilhamentoRemessa.remessa_id).where(
        CompartilhamentoRemessa.empresa_parceira_id == empresa_id,
        CompartilhamentoRemessa.pode_operar.is_(True),
        CompartilhamentoRemessa.aceito_em.is_not(None),
        CompartilhamentoRemessa.recusado_em.is_(None),
        CompartilhamentoRemessa.revogado_em.is_(None),
        or_(CompartilhamentoRemessa.expira_em.is_(None), CompartilhamentoRemessa.expira_em > agora()))


def remessa_legivel(db, acesso, remessa_id, exportar=False):
    """Remessa própria ou concedida. Retorna (remessa, compartilhamento|None).

    Sem concessão vigente a resposta é 404, igual a uma carga inexistente:
    a parceira não descobre cargas que não lhe foram mostradas.
    """
    r = db.scalar(select(Remessa).where(Remessa.id == remessa_id))
    if r is None:
        raise NaoEncontrado()
    if r.empresa_embarcador_id == acesso.empresa.id:
        return r, None
    c = vigente_para(db, r.id, acesso.empresa.id)
    if c is None:
        raise NaoEncontrado()
    if exportar and not c.pode_exportar:
        raise SemPermissao("O dono da carga não autorizou exportação para a sua empresa.")
    return r, c


def remessa_operavel(db, acesso, remessa_id):
    """Remessa em que a empresa atual pode bipar. Retorna (remessa, compartilhamento|None).

    A fila é a mesma para as duas empresas: uma trava por carga, tomada antes de
    qualquer linha. A transportadora não tem permissão de UPDATE na remessa, então
    não consegue travar a linha como o dono faz.
    """
    db.execute(text("SELECT pg_advisory_xact_lock(hashtextextended(:c,0))"), {"c": f"remessa:{remessa_id}"})
    r = db.scalar(select(Remessa).where(Remessa.id == remessa_id))
    if r is None:
        raise NaoEncontrado()
    if r.empresa_embarcador_id == acesso.empresa.id:
        db.execute(select(Remessa.id).where(Remessa.id == r.id).with_for_update())
        return r, None
    c = vigente_para(db, r.id, acesso.empresa.id)
    if c is None:
        raise NaoEncontrado()
    if not c.pode_operar:
        raise SemPermissao("O dono da carga não autorizou a sua empresa a operar esta carga.")
    return r, c


def marcar_acesso(db, dados, comp):
    """Diz a quem consulta que a carga é de outra empresa e se pode operá-la."""
    if comp is not None:
        dados.update(acesso="compartilhada", pode_operar=comp.pode_operar, pode_exportar=comp.pode_exportar,
                     empresa_dona=nomes(db, [comp.empresa_dona_id]).get(comp.empresa_dona_id))
    return dados


def auditar_acesso(db, acao, entidade, entidade_id, acesso, remessa, comp, depois=None):
    """Registra na empresa de quem acessou e, se via compartilhamento, também na do dono."""
    depois = dict(depois or {})
    if comp is None:
        auditar(db, acao, entidade, entidade_id, acesso.usuario, depois=depois)
        return
    depois.update({"via_compartilhamento": str(comp.id), "remessa_id": str(remessa.id)})
    auditar(db, acao, entidade, entidade_id, acesso.usuario, depois=depois)
    auditar(db, acao, entidade, entidade_id, acesso.usuario, empresa_id=remessa.empresa_embarcador_id,
            depois={**depois, "empresa_acesso_id": str(acesso.empresa.id)})


def nomes(db, ids):
    return {e.id: e.razao_social for e in db.scalars(select(Empresa).where(Empresa.id.in_(set(ids))))}


def resumo(c, nomes_empresas, acesso, remessa=None, andamentos=None):
    s = situacao(c)
    out = {"id": c.id, "remessa_id": c.remessa_id, "remessa_codigo": c.remessa_codigo,
           "empresa_dona_id": c.empresa_dona_id, "empresa_dona": nomes_empresas.get(c.empresa_dona_id),
           "empresa_parceira_id": c.empresa_parceira_id, "empresa_parceira": nomes_empresas.get(c.empresa_parceira_id),
           "papel": c.papel, "escopo": ["consultar"] + (["exportar"] if c.pode_exportar else []) + (["operar"] if c.pode_operar else []),
           "pode_exportar": c.pode_exportar, "pode_operar": c.pode_operar, "situacao": s, "criado_em": c.criado_em, "expira_em": c.expira_em,
           "aceito_em": c.aceito_em, "recusado_em": c.recusado_em, "revogado_em": c.revogado_em,
           "motivo_revogacao": c.motivo_revogacao,
           "minha_parte": "dona" if c.empresa_dona_id == acesso.empresa.id else "parceira",
           "remessa": None}
    # A parceira só recebe dados da carga enquanto a concessão vale.
    if remessa is not None and (out["minha_parte"] == "dona" or s == "ativo"):
        out["remessa"] = remessa_resumo(remessa, andamentos.get(remessa.id) if andamentos else None)
        if out["minha_parte"] == "parceira":
            out["remessa"].update(acesso="compartilhada", pode_operar=c.pode_operar, pode_exportar=c.pode_exportar,
                                  empresa_dona=out["empresa_dona"])
    return out


def _resumir(db, acesso, lista):
    n = nomes(db, [x.empresa_dona_id for x in lista] + [x.empresa_parceira_id for x in lista])
    remessas = {r.id: r for r in db.scalars(select(Remessa).where(Remessa.id.in_({x.remessa_id for x in lista})))} if lista else {}
    andamentos = andamentos_das_cargas(db, remessas.values())
    return [resumo(x, n, acesso, remessas.get(x.remessa_id), andamentos) for x in lista]


def convidar(db, acesso, remessa_id, body):
    r = remessa_por_id(db, acesso.empresa.id, remessa_id, escrita=True)
    if r.status == StatusRemessa.CANCELADA:
        raise RegraDeNegocio("Carga cancelada não pode ser compartilhada.")
    parceira = db.scalar(select(Empresa).where(Empresa.cnpj == body.cnpj_parceira))
    # Mesma resposta para CNPJ desconhecido, inativo ou de outro tipo: não serve de consulta de cadastro.
    if parceira is None or not parceira.ativa or parceira.tipo not in TIPOS_PARCEIRA or parceira.id == acesso.empresa.id:
        raise RegraDeNegocio("Nenhuma transportadora ativa com este CNPJ está disponível para compartilhamento.")
    if body.expira_em is not None and body.expira_em <= agora():
        raise RegraDeNegocio("O prazo do compartilhamento precisa estar no futuro.")
    existente = db.scalar(select(CompartilhamentoRemessa).where(
        CompartilhamentoRemessa.remessa_id == r.id, CompartilhamentoRemessa.empresa_parceira_id == parceira.id,
        CompartilhamentoRemessa.revogado_em.is_(None), CompartilhamentoRemessa.recusado_em.is_(None)))
    if existente is not None and situacao(existente) != "expirado":
        raise Conflito("Esta carga já tem compartilhamento pendente ou ativo com esta transportadora. Revogue antes de alterar o escopo.")
    if existente is not None:
        # Prazo vencido libera o índice de vigência sem apagar o registro antigo.
        existente.revogado_em = agora(); existente.revogado_por = acesso.usuario.id
        existente.motivo_revogacao = "Substituído após o prazo."
        db.flush()
    c = CompartilhamentoRemessa(remessa_id=r.id, remessa_codigo=r.codigo, empresa_dona_id=acesso.empresa.id,
        empresa_parceira_id=parceira.id, pode_exportar=body.pode_exportar, pode_operar=body.pode_operar,
        expira_em=body.expira_em,
        criado_por=acesso.usuario.id)
    db.add(c); db.flush()
    evento = {"remessa_id": str(r.id), "empresa_parceira_id": str(parceira.id),
              "pode_exportar": c.pode_exportar, "pode_operar": c.pode_operar,
              "expira_em": c.expira_em.isoformat() if c.expira_em else None}
    auditar(db, "compartilhamento_convidado", "compartilhamento_remessa", c.id, acesso.usuario, depois=evento)
    auditar(db, "compartilhamento_convidado", "compartilhamento_remessa", c.id, acesso.usuario,
            empresa_id=parceira.id, depois=evento)
    return resumo(c, nomes(db, [c.empresa_dona_id, c.empresa_parceira_id]), acesso, r)


def _obter(db, acesso, id_):
    c = db.scalar(select(CompartilhamentoRemessa).where(CompartilhamentoRemessa.id == id_,
        or_(CompartilhamentoRemessa.empresa_dona_id == acesso.empresa.id,
            CompartilhamentoRemessa.empresa_parceira_id == acesso.empresa.id)).with_for_update())
    if c is None:
        raise NaoEncontrado()
    return c


def _registrar(db, acesso, c, acao, depois=None):
    evento = {"remessa_id": str(c.remessa_id), **(depois or {})}
    for empresa in (c.empresa_dona_id, c.empresa_parceira_id):
        auditar(db, acao, "compartilhamento_remessa", c.id, acesso.usuario, empresa_id=empresa, depois=evento)
    db.flush()
    r = db.scalar(select(Remessa).where(Remessa.id == c.remessa_id))
    return resumo(c, nomes(db, [c.empresa_dona_id, c.empresa_parceira_id]), acesso, r)


def decidir(db, acesso, id_, aceitar):
    c = _obter(db, acesso, id_)
    if c.empresa_parceira_id != acesso.empresa.id:
        raise SemPermissao("Somente a empresa convidada aceita ou recusa o compartilhamento.")
    s = situacao(c)
    if s != "pendente":
        raise RegraDeNegocio(f"Este convite não está pendente (situação: {s}).")
    if aceitar:
        c.aceito_em = agora(); c.aceito_por = acesso.usuario.id
    else:
        c.recusado_em = agora(); c.recusado_por = acesso.usuario.id
    return _registrar(db, acesso, c, "compartilhamento_aceito" if aceitar else "compartilhamento_recusado")


def revogar(db, acesso, id_, body):
    c = _obter(db, acesso, id_)
    s = situacao(c)
    if s in ("revogado", "recusado"):
        raise RegraDeNegocio(f"Este compartilhamento já está encerrado (situação: {s}).")
    c.revogado_em = agora(); c.revogado_por = acesso.usuario.id; c.motivo_revogacao = body.motivo
    return _registrar(db, acesso, c, "compartilhamento_revogado",
                      {"motivo": body.motivo, "situacao_anterior": s,
                       "por": "dona" if acesso.empresa.id == c.empresa_dona_id else "parceira"})


def da_remessa(db, acesso, remessa_id):
    r = remessa_por_id(db, acesso.empresa.id, remessa_id)
    lista = list(db.scalars(select(CompartilhamentoRemessa).where(CompartilhamentoRemessa.remessa_id == r.id,
        CompartilhamentoRemessa.empresa_dona_id == acesso.empresa.id)
        .order_by(CompartilhamentoRemessa.criado_em.desc(), CompartilhamentoRemessa.id)))
    return _resumir(db, acesso, lista)


def recebidos(db, acesso, situacoes, limite, offset):
    """Convites e cargas concedidas à empresa atual. Operadores veem só os ativos."""
    q = select(CompartilhamentoRemessa).where(CompartilhamentoRemessa.empresa_parceira_id == acesso.empresa.id)
    lista = [c for c in db.scalars(q.order_by(CompartilhamentoRemessa.criado_em.desc(), CompartilhamentoRemessa.id))
             if situacao(c) in situacoes]
    return _resumir(db, acesso, lista[offset:offset+limite])
