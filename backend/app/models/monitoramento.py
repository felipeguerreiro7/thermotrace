"""Sessão, leitura, série e excursão — o núcleo da evidência.

Três princípios herdados do app e que o banco reforça:

1. **A leitura bruta é imutável.** Trigger de banco bloqueia UPDATE e DELETE.
   Um bug no Python não consegue driblar um gatilho.
2. **A série é derivada, não evidência.** Se o decodificador melhorar,
   recalcula-se a série a partir da `resposta_bruta` sem tocar na leitura.
3. **Três tempos, nunca um.** Quando aconteceu, quando foi detectado, quando
   foi lançado.
"""

from datetime import datetime
from decimal import Decimal
from uuid import UUID

from sqlalchemy import (
    BigInteger,
    Boolean,
    CheckConstraint,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    LargeBinary,
    SmallInteger,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.dialects.postgresql import ARRAY, INET, JSONB, REAL
from sqlalchemy.dialects.postgresql import UUID as PgUUID
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db.base import Base, CriadoMixin, IdMixin
from app.models.enums import (
    BaseDeTempo,
    Gravidade,
    Plataforma,
    StatusSessao,
    TipoExcursao,
    TipoLeitura,
)
from app.models.tipos import TemperaturaC, TensaoV, enum_pg


class SessaoMonitoramento(Base, IdMixin, CriadoMixin):
    """Um ciclo START → leitura final de uma etiqueta."""

    __tablename__ = "sessao_monitoramento"
    contrato_ingestao: Mapped[str | None] = mapped_column(String(16))

    etiqueta_id: Mapped[UUID] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("etiqueta.id"), nullable=False
    )
    vinculo_id: Mapped[UUID] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("vinculo_etiqueta.id"), nullable=False
    )
    remessa_id: Mapped[UUID] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("remessa.id"), nullable=False
    )
    perfil_termico_id: Mapped[UUID] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("perfil_termico.id"), nullable=False
    )

    # ---- base de tempo -------------------------------------------------
    # A etiqueta NÃO tem relógio confiável. O epoch abaixo foi gravado pelo
    # celular no START, e é a origem de todos os horários da série.
    epoch_inicio_etiqueta: Mapped[int] = mapped_column(BigInteger, nullable=False)
    inicio_dispositivo_em: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False
    )
    inicio_servidor_em: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False
    )
    desvio_relogio_ms: Mapped[int | None] = mapped_column(BigInteger)
    referencia_relogio: Mapped[str | None] = mapped_column(String(16))

    # Android grava o instante do START; iOS grava START + delay. Com
    # delay = 0 as duas coincidem e o problema fica invisível. Com delay, a
    # série sai deslocada em silêncio.
    base_de_tempo: Mapped[BaseDeTempo] = mapped_column(
        enum_pg(BaseDeTempo, "base_de_tempo"),
        nullable=False,
        server_default=BaseDeTempo.INSTANTE_START.value,
    )
    plataforma_ativacao: Mapped[Plataforma | None] = mapped_column(
        enum_pg(Plataforma, "plataforma")
    )

    # ---- configuração gravada na etiqueta ------------------------------
    delay_minutos: Mapped[int] = mapped_column(
        Integer, nullable=False, server_default="0"
    )
    intervalo_segundos: Mapped[int] = mapped_column(Integer, nullable=False)
    quantidade_planejada: Mapped[int] = mapped_column(Integer, nullable=False)
    min_configurado_c: Mapped[Decimal] = mapped_column(TemperaturaC, nullable=False)
    max_configurado_c: Mapped[Decimal] = mapped_column(TemperaturaC, nullable=False)
    modo_armazenamento: Mapped[int | None] = mapped_column(SmallInteger)

    # ---- confirmação e estado ------------------------------------------
    ativacao_confirmada: Mapped[bool] = mapped_column(
        Boolean, nullable=False, server_default="false"
    )
    ativacao_confirmada_em: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True)
    )
    ativada_por: Mapped[UUID | None] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("usuario.id")
    )
    dispositivo_id: Mapped[UUID | None] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("dispositivo.id")
    )
    tensao_no_start_v: Mapped[Decimal | None] = mapped_column(TensaoV)

    # "A etiqueta está ativa?" — só se sabe encostando o telefone. Estas duas
    # colunas separam o que foi confirmado do que é presunção.
    verificado_em: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    registrando_na_verificacao: Mapped[bool | None] = mapped_column(Boolean)

    status: Mapped[StatusSessao] = mapped_column(
        enum_pg(StatusSessao, "status_sessao"),
        nullable=False,
        server_default=StatusSessao.ATIVA.value,
    )
    encerrada_em: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    leituras: Mapped[list["LeituraEtiqueta"]] = relationship(back_populates="sessao")

    __table_args__ = (
        # Chave natural: reativar por engano não cria sessão duplicada.
        UniqueConstraint(
            "etiqueta_id", "epoch_inicio_etiqueta", name="uq_sessao_etiqueta_epoch"
        ),
        Index("ix_sessao_monitoramento_remessa_id", "remessa_id"),
        Index(
            "ix_sessao_monitoramento_ativas",
            "status",
            postgresql_where=status == StatusSessao.ATIVA.value,
        ),
        CheckConstraint(
            "intervalo_segundos BETWEEN 1 AND 65535", name="intervalo_valido"
        ),
        CheckConstraint("min_configurado_c < max_configurado_c", name="faixa_coerente"),
    )


class LeituraEtiqueta(Base, IdMixin):
    """Evidência bruta. Append-only, protegida por trigger.

    Sem `atualizado_em` de propósito: a ausência da coluna é documentação
    executável de que esta tabela não muda.
    """

    __tablename__ = "leitura_etiqueta"
    contrato_ingestao: Mapped[str | None] = mapped_column(String(16))
    evento_id: Mapped[UUID | None] = mapped_column(PgUUID(as_uuid=True))
    ordem_recebimento: Mapped[int | None] = mapped_column(Integer)
    envelope_integridade: Mapped[dict | None] = mapped_column(JSONB)

    sessao_id: Mapped[UUID] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("sessao_monitoramento.id"), nullable=False
    )
    etiqueta_id: Mapped[UUID] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("etiqueta.id"), nullable=False
    )

    # Idempotência: dev:<install_id>:sess:<id>:read:<n>.
    # O app tem retry agressivo no WorkManager; isto o torna inofensivo.
    chave_idempotencia: Mapped[str] = mapped_column(
        String(160), nullable=False, unique=True
    )
    tipo_leitura: Mapped[TipoLeitura] = mapped_column(
        enum_pg(TipoLeitura, "tipo_leitura"), nullable=False
    )

    dispositivo_id: Mapped[UUID | None] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("dispositivo.id")
    )
    lida_por: Mapped[UUID | None] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("usuario.id")
    )
    lida_em_dispositivo: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False
    )
    recebida_em_servidor: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False
    )
    desvio_relogio_ms: Mapped[int | None] = mapped_column(BigInteger)

    # A EVIDÊNCIA. Array cru devolvido pelo SDK do fabricante.
    # Nunca descartar: se o decodificador melhorar, a série é recalculada
    # a partir daqui e as duas versões podem ser comparadas.
    resposta_bruta: Mapped[dict | list] = mapped_column(JSONB, nullable=False)
    memoria_bruta_hex: Mapped[str | None] = mapped_column(Text)
    versao_sdk: Mapped[str | None] = mapped_column(String(64))
    versao_decodificador: Mapped[str] = mapped_column(String(64), nullable=False)

    codigo_estado: Mapped[str | None] = mapped_column(String(8))
    quantidade_planejada_relatada: Mapped[int | None] = mapped_column(Integer)
    quantidade_medida: Mapped[int | None] = mapped_column(Integer)
    intervalo_relatado_s: Mapped[int | None] = mapped_column(Integer)
    delay_relatado_min: Mapped[int | None] = mapped_column(Integer)

    tensao_v: Mapped[Decimal | None] = mapped_column(TensaoV)
    temperatura_instantanea_c: Mapped[Decimal | None] = mapped_column(TemperaturaC)
    bateria_ok: Mapped[bool | None] = mapped_column(Boolean)

    # Deriva acumulada do oscilador da etiqueta. Em 30 dias pode ser dezenas
    # de minutos; declarar a correção é o que a torna aceitável no laudo.
    deriva_rtc_segundos: Mapped[int | None] = mapped_column(BigInteger)
    horarios_corrigidos: Mapped[bool] = mapped_column(
        Boolean, nullable=False, server_default="false"
    )

    # Cadeia de integridade. `verificar_cadeia(sessao_id)` recalcula tudo e
    # aponta exatamente onde quebrou — é a consulta que se roda na auditoria.
    hash_payload: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)
    hash_anterior: Mapped[bytes | None] = mapped_column(LargeBinary)
    hash_encadeado: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)

    ip_origem: Mapped[str | None] = mapped_column(INET)

    sessao: Mapped[SessaoMonitoramento] = relationship(back_populates="leituras")

    __table_args__ = (
        UniqueConstraint("sessao_id", "hash_payload", name="uq_leitura_sessao_hash"),
        UniqueConstraint("sessao_id", "evento_id", name="uq_leitura_sessao_evento"),
        UniqueConstraint("sessao_id", "ordem_recebimento", name="uq_leitura_sessao_ordem"),
        CheckConstraint("(contrato_ingestao IS NULL AND evento_id IS NULL AND ordem_recebimento IS NULL AND envelope_integridade IS NULL) OR (contrato_ingestao IS NOT NULL AND contrato_ingestao = '1' AND evento_id IS NOT NULL AND ordem_recebimento IS NOT NULL AND ordem_recebimento > 0 AND envelope_integridade IS NOT NULL)", name="envelope_ingestao_completo"),
        Index("uq_leitura_final_ingestao", "sessao_id", unique=True,
              postgresql_where=(tipo_leitura == TipoLeitura.FINAL.value) & contrato_ingestao.is_not(None)),
        Index("ix_leitura_etiqueta_sessao_lida", "sessao_id", "lida_em_dispositivo"),
    )


class SerieMedicao(Base, CriadoMixin):
    """Série decodificada. Derivada e recalculável.

    Separada de `leitura_etiqueta` apesar do 1:1 porque tem **ciclo de vida
    diferente**: a leitura é evidência imutável, a série é interpretação.

    A série vai como array e não uma linha por medição: 2,2 milhões de pontos
    em cinco anos viram ~3.600 linhas em vez de 2,2 milhões. É escrita uma
    vez, lida inteira, e nunca sofre UPDATE de ponto individual.
    """

    __tablename__ = "serie_medicao"

    leitura_id: Mapped[UUID] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("leitura_etiqueta.id"), primary_key=True
    )
    sessao_id: Mapped[UUID] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("sessao_monitoramento.id"), nullable=False
    )

    primeiro_ponto_em: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False
    )
    intervalo_segundos: Mapped[int] = mapped_column(Integer, nullable=False)
    quantidade: Mapped[int] = mapped_column(Integer, nullable=False)

    # REAL e não NUMERIC: o sensor tem passo de 0,25 °C, e REAL ocupa metade
    # do espaço num array de milhares de elementos.
    temperaturas_c: Mapped[list[float]] = mapped_column(ARRAY(REAL), nullable=False)
    bits_campo: Mapped[list[int] | None] = mapped_column(ARRAY(SmallInteger))

    # Agregados pré-calculados: evitam varrer o array em toda consulta.
    min_c: Mapped[float | None] = mapped_column(REAL)
    max_c: Mapped[float | None] = mapped_column(REAL)
    media_c: Mapped[float | None] = mapped_column(REAL)
    mkt_c: Mapped[float | None] = mapped_column(REAL)

    # Tempo fora da faixa, não contagem de pontos. Contar pontos erra nos
    # dois sentidos: superestima uma abertura de caixa de 2 min e subestima
    # um desvio de 4 h.
    tor_abaixo_s: Mapped[int | None] = mapped_column(Integer)
    tor_acima_s: Mapped[int | None] = mapped_column(Integer)
    maior_excursao_s: Mapped[int | None] = mapped_column(Integer)

    versao_regra: Mapped[str] = mapped_column(String(32), nullable=False)

    __table_args__ = (
        CheckConstraint(
            "quantidade = cardinality(temperaturas_c)", name="quantidade_bate_com_array"
        ),
        Index("ix_serie_medicao_sessao_id", "sessao_id"),
    )


class Excursao(Base, IdMixin, CriadoMixin):
    """Segmento CONTÍNUO fora da faixa, não ponto solto."""

    __tablename__ = "excursao"

    sessao_id: Mapped[UUID] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("sessao_monitoramento.id"), nullable=False
    )
    leitura_id: Mapped[UUID] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("leitura_etiqueta.id"), nullable=False
    )

    tipo: Mapped[TipoExcursao] = mapped_column(
        enum_pg(TipoExcursao, "tipo_excursao"), nullable=False
    )
    gravidade: Mapped[Gravidade] = mapped_column(
        enum_pg(Gravidade, "gravidade"), nullable=False
    )

    inicio_em: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    fim_em: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    duracao_segundos: Mapped[int] = mapped_column(Integer, nullable=False)
    quantidade_pontos: Mapped[int] = mapped_column(Integer, nullable=False)

    pico_c: Mapped[float] = mapped_column(REAL, nullable=False)
    limite_c: Mapped[float] = mapped_column(REAL, nullable=False)
    indice_primeira_amostra: Mapped[int] = mapped_column(Integer, nullable=False)

    # Faz o veredito ser reproduzível: se a regra mudar em 2027, os laudos
    # de 2026 continuam explicáveis.
    versao_regra: Mapped[str] = mapped_column(String(32), nullable=False)
    avaliada_em: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False
    )
    # Reavaliação com decodificador novo não apaga a anterior: aponta para ela.
    substituida_por: Mapped[UUID | None] = mapped_column(
        PgUUID(as_uuid=True), ForeignKey("excursao.id")
    )

    __table_args__ = (
        CheckConstraint("fim_em >= inicio_em", name="intervalo_coerente"),
        Index(
            "ix_excursao_sessao_vigente",
            "sessao_id",
            postgresql_where=substituida_por.is_(None),
        ),
    )
