"""Conferência pública de um relatório de viagem, sem login.

Quem recebe o relatório impresso (ou o PDF) informa a sessão e o hash da projeção
que estão nele. A prova é a trilha de auditoria: toda exportação grava o hash
emitido (`viagem_exportada`). Comparar só com a viagem de agora reprovaria
relatórios legítimos antigos, porque o hash muda quando chegam coletas novas.

Regras de privacidade:
- Sem uma exportação com aquele par sessão+hash, a resposta é só "não confere",
  igual para sessão inexistente e hash errado: não dá para descobrir sessões.
- A empresa da carga vem da própria exportação, e só então o contexto do RLS é
  definido para ler a sessão; nenhuma outra carga é lida.
- Quando confere, devolve apenas o que o relatório já mostra a quem o tem.
"""
from sqlalchemy import select, text

from app.models import LeituraEtiqueta, LogAuditoria
from app.services import ingestao, viagem
from app.services.auditoria import auditar
from app.core.errors import NaoEncontrado

NAO_CONFERE = {"confere": False,
               "mensagem": "Este relatório não foi emitido por este servidor com esses dados. "
                           "Confira a sessão e o hash copiados do relatório."}


def _exportacoes(db, sessao_id, hash_projecao):
    return list(db.scalars(select(LogAuditoria).where(
        LogAuditoria.acao == "viagem_exportada", LogAuditoria.entidade == "sessao_monitoramento",
        LogAuditoria.entidade_id == sessao_id,
        LogAuditoria.depois["hash_projecao"].astext == hash_projecao).order_by(LogAuditoria.em)))


def conferir(db, sessao_id, hash_projecao):
    linhas = _exportacoes(db, sessao_id, hash_projecao)
    # Exportação via compartilhamento grava uma linha para cada empresa; a do dono lê a carga.
    for empresa in dict.fromkeys(l.empresa_id for l in linhas if l.empresa_id):
        db.execute(text("SELECT set_config('app.empresa_id', :id, true)"), {"id": str(empresa)})
        try:
            s, r, _ = ingestao.sessao_por_id(db, empresa, sessao_id)
        except NaoEncontrado:
            continue
        leituras = list(db.scalars(select(LeituraEtiqueta).where(
            LeituraEtiqueta.sessao_id == sessao_id, LeituraEtiqueta.contrato_ingestao == "1")
            .order_by(LeituraEtiqueta.ordem_recebimento)))
        integra = ingestao.verificar_integridade(db, empresa, sessao_id)["integra"]
        atual = viagem.consolidar(s, leituras, r, integra)
        auditar(db, "viagem_conferida_publicamente", "sessao_monitoramento", sessao_id, empresa_id=empresa,
                depois={"hash_projecao": hash_projecao, "confere": True,
                        "versao_atual": atual["hash_projecao"] == hash_projecao})
        db.commit()
        resumo = atual["resumo"] if atual["hash_projecao"] == hash_projecao else None
        return {
            "confere": True,
            "codigo_carga": r.codigo,
            "sessao_id": str(sessao_id),
            "hash_projecao": hash_projecao,
            "emitido_em": linhas[0].em.isoformat(),
            "formatos": sorted({str((l.depois or {}).get("formato")) for l in linhas}),
            "versao_atual": atual["hash_projecao"] == hash_projecao,
            # Resumo só quando o relatório ainda é a versão atual: é o mesmo que ele mostra.
            "resumo": resumo,
            "origem": atual.get("origem") if resumo else None,
            "conferencia": atual["conferencia"] if resumo else None,
            "integridade_da_cadeia": integra,
            "aviso": ("Confirma que o servidor emitiu este relatório. Não comprova calibração, "
                      "STOP físico nem libera a carga."),
        }
    return NAO_CONFERE
