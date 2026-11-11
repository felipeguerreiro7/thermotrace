"""Empresas de demonstração com viagens fictícias, para apresentar e ensaiar o portal.

Cria um dono de carga e uma transportadora, ambos marcados como fictícios, com três
cargas: uma viagem dentro da faixa, uma com excursão e uma em trânsito operada pela
transportadora. Tudo passa pelas mesmas rotas que o portal e o aplicativo usam, e as
evidências entram com origem "simulacao", que o portal mostra em cada coleta.

Uso (ambiente local ou de homologação, nunca produção):

    python -m app.demo_cli

A senha das contas de demonstração é pedida no terminal, ou lida da variável
THERMOTRACE_DEMO_SENHA. Ela não é exibida nem gravada em arquivo.
"""
import math
import os
from datetime import datetime, timedelta, timezone
from getpass import getpass
from uuid import uuid4

from sqlalchemy import select

from app.core.config import obter_config
from app.core.security import hasher
from app.db.session import SessaoLocal
from app.models import Empresa, Etiqueta, LoteEtiqueta, Usuario
from app.models.enums import PapelUsuario, TipoEmpresa

# CNPJs fora de qualquer cadastro real: raiz zerada, só para servir de chave.
DONA = ("00000000000191", "Laboratório Demonstração (dados fictícios)", TipoEmpresa.EMBARCADOR, "dono")
TRANSPORTADORA = ("00000000000272", "Transportes Demonstração (dados fictícios)", TipoEmpresa.TRANSPORTADORA, "transportadora")
INTERVALO = 600
FAIXA = (2.0, 8.0)


def email(prefixo, papel):
    return f"demo-{prefixo}-{papel}@example.org"


def serie(pontos, pico=None):
    """Temperaturas plausíveis de cadeia fria; `pico` = (início, fim, valor) cria uma excursão."""
    out = []
    for i in range(pontos):
        t = 5.0 + 1.1 * math.sin(i / 9) + 0.4 * math.sin(i / 2.3)
        if pico and pico[0] <= i < pico[1]:
            t = pico[2] - 0.6 * abs((i - (pico[0] + pico[1]) / 2) / max(1, (pico[1] - pico[0]) / 2))
        out.append(round(t * 4) / 4)  # passo de 0,25 °C do sensor
    return out


def bruto(epoch, temperaturas):
    """Mesmo formato de resposta que o aplicativo envia: cabeçalho de 12 campos e as medições."""
    f = lambda v: f"{v:g}"
    return ["1", str(epoch), "648", str(len(temperaturas)), "0", str(INTERVALO),
            f(min(temperaturas)), f(max(temperaturas)), f(FAIXA[0]), f(FAIXA[1]),
            str(sum(t < FAIXA[0] for t in temperaturas)), str(sum(t > FAIXA[1] for t in temperaturas)),
            *[f(t) for t in temperaturas]]


class Conta:
    def __init__(self, cliente, endereco, senha):
        r = cliente.post("/api/v1/auth/login", json={"email": endereco, "senha": senha})
        if r.status_code != 200:
            raise SystemExit(f"Login da conta de demonstração falhou ({r.status_code}).")
        self.c, self.h = cliente, {"Authorization": "Bearer " + r.json()["access_token"]}

    def post(self, caminho, corpo, esperado=201):
        r = self.c.post("/api/v1" + caminho, json=corpo, headers={**self.h, "Idempotency-Key": str(uuid4())})
        if r.status_code != esperado:
            raise SystemExit(f"Falha em {caminho}: {r.status_code} {r.text[:300]}")
        return r.json()


def viagem(conta, volume_id, etiqueta, aparelho, inicio, coletas, serie_completa):
    """Ativa a etiqueta e envia as coletas. `coletas` = [(tipo, horas depois do início)]."""
    epoch = int(inicio.timestamp())
    ev = lambda quando, resposta: {"evento_id": str(uuid4()), "dispositivo_id": aparelho, "lida_em": quando.isoformat(),
                                  "uid_canonico": etiqueta.nfc_uid, "versao_sdk": "demonstracao-1",
                                  "versao_decodificador": "fm13dt160-decoder-1.1", "resposta_bruta": resposta,
                                  "origem": "simulacao"}
    sessao = str(uuid4())
    conta.post(f"/volumes/{volume_id}/sessoes", {
        "sessao_id": sessao, "etiqueta_id": str(etiqueta.id), "epoch_inicio_etiqueta": epoch,
        "intervalo_segundos": INTERVALO, "quantidade_planejada": 648,
        "min_configurado_c": f"{FAIXA[0]:.2f}", "max_configurado_c": f"{FAIXA[1]:.2f}", "ativacao_confirmada": True,
        "evidencia": ev(inicio, ["ATIVACAO DE DEMONSTRACAO"])})
    for tipo, horas in coletas:
        quando = inicio + timedelta(hours=horas)
        medidas = serie_completa[:int(horas * 3600 // INTERVALO)]
        conta.post(f"/sessoes/{sessao}/leituras", {**ev(quando, bruto(epoch, medidas)), "tipo": tipo,
                                                   "epoch_inicio_etiqueta": epoch, "intervalo_relatado_s": INTERVALO})
    return sessao


def main():
    cfg = obter_config()
    if cfg.em_producao:
        raise SystemExit("Dados de demonstração não são criados em produção.")
    senha = os.environ.get("THERMOTRACE_DEMO_SENHA") or getpass("Senha das contas de demonstração (mínimo 12 caracteres): ")
    if len(senha) < 12:
        raise SystemExit("A senha precisa ter pelo menos 12 caracteres.")
    try:
        from fastapi.testclient import TestClient
    except Exception:  # dependência de desenvolvimento
        raise SystemExit("Instale as dependências de desenvolvimento (httpx) para criar a demonstração.")
    from app.main import app

    etiquetas = {}
    with SessaoLocal.begin() as db:
        if db.scalar(select(Empresa.id).where(Empresa.cnpj.in_([DONA[0], TRANSPORTADORA[0]]))):
            raise SystemExit("As empresas de demonstração já existem neste banco; nada foi alterado.")
        ph = hasher.hash(senha)
        lote = LoteEtiqueta(fornecedor="Demonstração", modelo_hardware="SIMULACAO", quantidade=4)
        db.add(lote); db.flush()
        for cnpj, razao, tipo, prefixo in (DONA, TRANSPORTADORA):
            e = Empresa(cnpj=cnpj, razao_social=razao, tipo=tipo)
            db.add(e); db.flush()
            for papel in (PapelUsuario.GESTOR, PapelUsuario.OPERADOR):
                db.add(Usuario(empresa_id=e.id, nome=f"{papel.value.capitalize()} de demonstração",
                               email=email(prefixo, papel.value), senha_hash=ph, papel=papel))
            etiquetas[prefixo] = []
            for n in range(2):
                tag = Etiqueta(lote_id=lote.id, empresa_id=e.id, serial=f"DEMO-{prefixo[:4].upper()}-{n+1}",
                               qr_payload=f"DEMO:{uuid4().hex}", nfc_uid=uuid4().hex[:16].upper())
                db.add(tag); db.flush(); db.expunge(tag)
                etiquetas[prefixo].append(tag)

    with TestClient(app) as cliente:
        dono = Conta(cliente, email("dono", "gestor"), senha)
        dono_op = Conta(cliente, email("dono", "operador"), senha)
        transp = Conta(cliente, email("transportadora", "gestor"), senha)
        transp_op = Conta(cliente, email("transportadora", "operador"), senha)

        perfil = dono.post("/perfis", {
            "codigo": "DEMO-2A8", "rotulo": "Refrigerado 2 a 8 °C (demonstração)", "min_c": "2.00", "max_c": "8.00",
            "evidencia": {"produto": "Produto fictício de demonstração", "apresentacao": "Caixa térmica",
                          "condicao": "Conservar entre 2 e 8 °C", "fonte_url": "https://example.org/demonstracao",
                          "fonte_titulo": "Fonte fictícia para demonstração", "fonte_versao": "1",
                          "verificada_em": (datetime.now(timezone.utc) - timedelta(days=1)).date().isoformat(),
                          "justificativa": "Perfil criado apenas para demonstrar o portal; não usar em operação real."}})
        dono.post(f"/perfis/{perfil['id']}/aprovar",
                  {"declaracao_responsavel": "Perfil fictício aprovado somente para demonstração do portal."}, 200)
        produto = dono.post("/produtos", {"codigo": "DEMO-PRODUTO", "perfil_termico_id": perfil["id"],
                                          "intervalo_segundos": INTERVALO})

        def carga(codigo, descricao, volumes=1):
            return dono.post("/remessas", {"codigo": codigo, "produto_configuracao_id": produto["id"],
                                           "destinatario_nome": "Hospital Demonstração (fictício)",
                                           "descricao_carga": descricao, "quantidade_volumes": volumes})

        ap = lambda conta, modelo: conta.post("/dispositivos", {"install_id": str(uuid4()), "modelo": modelo,
                                                               "versao_so": "demonstração", "versao_app": "0.8.9-demo"})["id"]
        ap_dono, ap_transp = ap(dono_op, "Celular do dono (demonstração)"), ap(transp_op, "Celular da transportadora (demonstração)")
        agora = datetime.now(timezone.utc).replace(microsecond=0)

        conforme = carga("DEMO-CONFORME", "Viagem fictícia dentro da faixa, do início à leitura final.")
        viagem(dono_op, conforme["volumes"][0]["id"], etiquetas["dono"][0], ap_dono, agora - timedelta(hours=26),
               [("checkpoint", 12), ("final", 24)], serie(144))

        excursao = carga("DEMO-EXCURSAO", "Viagem fictícia com temperatura acima da faixa por cerca de duas horas.")
        viagem(dono_op, excursao["volumes"][0]["id"], etiquetas["dono"][1], ap_dono, agora - timedelta(hours=20),
               [("checkpoint", 9), ("final", 18)], serie(108, pico=(60, 72, 11.5)))

        transito = carga("DEMO-EM-TRANSITO", "Carga fictícia em viagem, operada pela transportadora.", volumes=2)
        convite = dono.post(f"/remessas/{transito['id']}/compartilhamentos",
                            {"cnpj_parceira": TRANSPORTADORA[0], "pode_exportar": True, "pode_operar": True})
        transp.post(f"/compartilhamentos/{convite['id']}/aceitar", {}, 200)
        viagem(transp_op, transito["volumes"][0]["id"], etiquetas["transportadora"][0], ap_transp,
               agora - timedelta(hours=6), [("checkpoint", 5)], serie(36))

    print("Demonstração criada: 2 empresas fictícias, 3 cargas, 3 viagens simuladas.")
    for _, razao, _, prefixo in (DONA, TRANSPORTADORA):
        print(f"  {razao}: {email(prefixo, 'gestor')} e {email(prefixo, 'operador')}")
    print("A senha é a que você informou; ela não foi exibida nem gravada em arquivo.")


if __name__ == "__main__":
    main()
