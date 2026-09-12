"""Prova de que a evidência não pode ser alterada.

Este é o teste que se mostra numa auditoria. Se ele quebrar, o produto perdeu
a característica que o justifica.

Os testes usam SQL direto de propósito: o que está sendo verificado é a
garantia do **banco**, não a da aplicação. Passar por um serviço Python aqui
testaria a coisa errada.
"""

import uuid
from datetime import UTC, datetime

import pytest
from sqlalchemy import text
from sqlalchemy.exc import DBAPIError, IntegrityError


def _semear_leitura(sessao):
    """Cria a cadeia mínima até uma leitura: empresa → ... → leitura."""
    ids = {
        k: uuid.uuid4()
        for k in (
            "empresa", "lote", "etiqueta", "perfil", "remessa",
            "volume", "vinculo", "sessao", "leitura",
        )
    }
    agora = datetime.now(UTC)
    sufixo = uuid.uuid4().hex[:8].upper()

    sessao.execute(
        text(
            "INSERT INTO empresa (id, razao_social, cnpj, tipo) "
            "VALUES (:id, 'Teste LTDA', :cnpj, 'embarcador')"
        ),
        {"id": ids["empresa"], "cnpj": str(uuid.uuid4().int)[:14]},
    )
    sessao.execute(
        text(
            "INSERT INTO lote_etiqueta (id, fornecedor, modelo_hardware, quantidade) "
            "VALUES (:id, 'Fornecedor', 'MI8654TE', 100)"
        ),
        {"id": ids["lote"]},
    )
    sessao.execute(
        text(
            # Parâmetros distintos para serial e qr_payload de propósito:
            # as colunas têm tipos diferentes (varchar x text) e reusar o
            # mesmo parâmetro faz o Postgres recusar por AmbiguousParameter.
            "INSERT INTO etiqueta (id, lote_id, serial, qr_payload, nfc_uid) "
            "VALUES (:id, :lote, :serial, :qr, :uid)"
        ),
        {
            "id": ids["etiqueta"],
            "lote": ids["lote"],
            "serial": f"TT-{sufixo}",
            "qr": f"TT-{sufixo}",
            "uid": uuid.uuid4().hex[:13].upper(),
        },
    )
    sessao.execute(
        text(
            "INSERT INTO perfil_termico (id, codigo, rotulo, min_c, max_c) "
            "VALUES (:id, :cod, '2 a 8 C', 2, 8)"
        ),
        {"id": ids["perfil"], "cod": f"P{sufixo}"},
    )
    sessao.execute(
        text(
            "INSERT INTO remessa (id, codigo, empresa_embarcador_id, "
            "destinatario_nome, perfil_termico_id) "
            "VALUES (:id, :cod, :emp, 'Hospital Teste', :perfil)"
        ),
        {
            "id": ids["remessa"], "cod": f"REM-{sufixo}",
            "emp": ids["empresa"], "perfil": ids["perfil"],
        },
    )
    sessao.execute(
        text(
            "INSERT INTO volume (id, remessa_id, sequencia, monitorado) "
            "VALUES (:id, :rem, 1, true)"
        ),
        {"id": ids["volume"], "rem": ids["remessa"]},
    )
    sessao.execute(
        text(
            "INSERT INTO vinculo_etiqueta (id, volume_id, etiqueta_id) "
            "VALUES (:id, :vol, :etq)"
        ),
        {"id": ids["vinculo"], "vol": ids["volume"], "etq": ids["etiqueta"]},
    )
    sessao.execute(
        text(
            "INSERT INTO sessao_monitoramento "
            "(id, etiqueta_id, vinculo_id, remessa_id, perfil_termico_id, "
            " epoch_inicio_etiqueta, inicio_dispositivo_em, inicio_servidor_em, "
            " intervalo_segundos, quantidade_planejada, "
            " min_configurado_c, max_configurado_c) "
            "VALUES (:id, :etq, :vin, :rem, :perfil, 1755000000, :ag, :ag, "
            "        600, 648, 2, 8)"
        ),
        {
            "id": ids["sessao"], "etq": ids["etiqueta"], "vin": ids["vinculo"],
            "rem": ids["remessa"], "perfil": ids["perfil"], "ag": agora,
        },
    )
    sessao.execute(
        text(
            "INSERT INTO leitura_etiqueta "
            "(id, sessao_id, etiqueta_id, chave_idempotencia, tipo_leitura, "
            " lida_em_dispositivo, recebida_em_servidor, resposta_bruta, "
            " versao_decodificador, hash_payload, hash_encadeado) "
            "VALUES (:id, :ses, :etq, :chave, 'ativacao', :ag, :ag, "
            "        CAST(:bruto AS jsonb), 'fm13dt160-1.0', "
            "        decode('00', 'hex'), decode('01', 'hex'))"
        ),
        {
            "id": ids["leitura"], "ses": ids["sessao"], "etq": ids["etiqueta"],
            "chave": f"dev:{uuid.uuid4().hex}:read:1", "ag": agora,
            "bruto": '["3", "1755000000", "648", "180"]',
        },
    )
    return ids


def test_leitura_nao_aceita_update(sessao):
    ids = _semear_leitura(sessao)
    with pytest.raises(DBAPIError, match="append-only"):
        sessao.execute(
            text("UPDATE leitura_etiqueta SET tensao_v = 9.99 WHERE id = :id"),
            {"id": ids["leitura"]},
        )


def test_leitura_nao_aceita_delete(sessao):
    ids = _semear_leitura(sessao)
    with pytest.raises(DBAPIError, match="append-only"):
        sessao.execute(
            text("DELETE FROM leitura_etiqueta WHERE id = :id"),
            {"id": ids["leitura"]},
        )


def test_chave_de_idempotencia_impede_leitura_duplicada(sessao):
    """Reenvio do outbox não pode virar evidência duplicada."""
    ids = _semear_leitura(sessao)
    chave = sessao.execute(
        text("SELECT chave_idempotencia FROM leitura_etiqueta WHERE id = :id"),
        {"id": ids["leitura"]},
    ).scalar_one()

    with pytest.raises(IntegrityError):
        sessao.execute(
            text(
                "INSERT INTO leitura_etiqueta "
                "(sessao_id, etiqueta_id, chave_idempotencia, tipo_leitura, "
                " lida_em_dispositivo, recebida_em_servidor, resposta_bruta, "
                " versao_decodificador, hash_payload, hash_encadeado) "
                "VALUES (:ses, :etq, :chave, 'checkpoint', now(), now(), "
                "        CAST('[]' AS jsonb), 'x', "
                "        decode('02','hex'), decode('03','hex'))"
            ),
            {"ses": ids["sessao"], "etq": ids["etiqueta"], "chave": chave},
        )


def test_etiqueta_nao_fica_em_dois_volumes(sessao):
    """A restrição que impede o erro operacional mais caro do sistema:
    trinta etiquetas na bancada e a errada indo para a caixa errada."""
    ids = _semear_leitura(sessao)
    outro_volume = uuid.uuid4()
    sessao.execute(
        text(
            "INSERT INTO volume (id, remessa_id, sequencia, monitorado) "
            "VALUES (:id, :rem, 2, true)"
        ),
        {"id": outro_volume, "rem": ids["remessa"]},
    )

    with pytest.raises(IntegrityError):
        sessao.execute(
            text(
                "INSERT INTO vinculo_etiqueta (volume_id, etiqueta_id) "
                "VALUES (:vol, :etq)"
            ),
            {"vol": outro_volume, "etq": ids["etiqueta"]},
        )


def test_mesma_ativacao_nao_cria_duas_sessoes(sessao):
    ids = _semear_leitura(sessao)
    with pytest.raises(IntegrityError):
        sessao.execute(
            text(
                "INSERT INTO sessao_monitoramento "
                "(etiqueta_id, vinculo_id, remessa_id, perfil_termico_id, "
                " epoch_inicio_etiqueta, inicio_dispositivo_em, inicio_servidor_em, "
                " intervalo_segundos, quantidade_planejada, "
                " min_configurado_c, max_configurado_c) "
                "VALUES (:etq, :vin, :rem, :perfil, 1755000000, now(), now(), "
                "        600, 648, 2, 8)"
            ),
            {
                "etq": ids["etiqueta"], "vin": ids["vinculo"],
                "rem": ids["remessa"], "perfil": ids["perfil"],
            },
        )


def test_faixa_termica_invertida_e_recusada(sessao):
    """min >= max não é dado válido — o banco recusa antes de virar laudo."""
    ids = _semear_leitura(sessao)
    with pytest.raises(IntegrityError):
        sessao.execute(
            text(
                "INSERT INTO perfil_termico (codigo, rotulo, min_c, max_c) "
                "VALUES (:cod, 'invertido', 8, 2)"
            ),
            {"cod": f"X{uuid.uuid4().hex[:8]}"},
        )
