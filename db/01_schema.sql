-- =====================================================================
-- ThermoTrace — banco de auditoria de cadeia fria
-- PostgreSQL 14+
--
-- Princípios de projeto (leia antes de alterar qualquer coisa aqui):
--
--  P1. EVIDÊNCIA É IMUTÁVEL. Tudo que veio de uma etiqueta é append-only.
--      Correção se faz por novo registro + superseded_by, nunca por UPDATE.
--  P2. O DADO BRUTO SOBREVIVE AO DECODIFICADOR. Guardamos o payload cru da
--      etiqueta; a série decodificada é derivada e pode ser recalculada.
--  P3. TRÊS TEMPOS DIFERENTES, SEMPRE SEPARADOS:
--        occurred_at  — quando o fato aconteceu no mundo físico
--        recorded_at  — quando o servidor gravou
--        device_at    — o que o relógio do celular achava que era
--      A etiqueta NÃO tem relógio confiável: a base de tempo é o celular
--      que deu o START. Sem guardar o desvio, o laudo não se sustenta.
--  P4. TODA AVALIAÇÃO GRAVA A VERSÃO DA REGRA QUE A PRODUZIU.
--  P5. IDEMPOTÊNCIA. O celular opera offline e reenviará. Toda ingestão
--      tem chave natural única.
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- gen_random_uuid, digest
CREATE EXTENSION IF NOT EXISTS citext;     -- e-mail case-insensitive

CREATE SCHEMA IF NOT EXISTS tt;
SET search_path = tt, public;

-- ---------------------------------------------------------------------
-- Domínios e enums
-- ---------------------------------------------------------------------

CREATE TYPE company_role      AS ENUM ('embarcador','transportadora','destinatario','plataforma');
CREATE TYPE shipment_status   AS ENUM ('preparacao','aguardando_aceite','aguardando_coleta',
                                       'em_transporte','entregue_aguardando_leitura','concluida','cancelada');
CREATE TYPE session_status    AS ENUM ('ativa','encerrada_normal','encerrada_anormal','abandonada');
CREATE TYPE custody_party     AS ENUM ('embarcador','transportadora','destinatario');
CREATE TYPE event_source      AS ENUM ('automatico_nfc','confirmado_tempo_real','informado_posteriormente');
CREATE TYPE excursion_kind    AS ENUM ('acima_limite','abaixo_limite');
CREATE TYPE excursion_grade   AS ENUM ('alerta','acao','critica');
CREATE TYPE tag_state         AS ENUM ('estoque','vinculada','monitorando','lida','manutencao','aposentada');

-- Convenção de base de tempo gravada na etiqueta no START.
-- Os dois SDKs do fabricante divergem — verificado no código:
--   START_INSTANT : Android (InstructMap case 22) grava `now`
--   FIRST_WINDOW  : iOS (CMD_SET_START_TIME) grava `now + delay*60`
-- Sem registrar isto, uma sessão ativada por iPhone e lida como se fosse
-- Android sai deslocada de `delay` minutos, sem nenhum erro visível.
CREATE TYPE time_base         AS ENUM ('start_instant','first_window');
CREATE TYPE activation_os     AS ENUM ('android','ios');

-- ---------------------------------------------------------------------
-- 1. Organizações e pessoas
-- ---------------------------------------------------------------------

CREATE TABLE company (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    legal_name      text        NOT NULL,
    trade_name      text,
    tax_id          text        NOT NULL,           -- CNPJ
    created_at      timestamptz NOT NULL DEFAULT now(),
    active          boolean     NOT NULL DEFAULT true,
    UNIQUE (tax_id)
);

CREATE TABLE app_user (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email           citext      NOT NULL UNIQUE,
    full_name       text        NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    active          boolean     NOT NULL DEFAULT true
);

CREATE TABLE membership (
    user_id         uuid NOT NULL REFERENCES app_user(id),
    company_id      uuid NOT NULL REFERENCES company(id),
    role            company_role NOT NULL,
    PRIMARY KEY (user_id, company_id)
);

-- Cada celular que ativa/le etiqueta é um instrumento de medição.
-- Precisa ser identificável no laudo.
CREATE TABLE device (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id          uuid NOT NULL REFERENCES company(id),
    install_id          text NOT NULL,              -- id de instalação do app (não IMEI: LGPD)
    model               text,
    os_version          text,
    app_version         text,
    nfc_stack           text,                       -- 'nfc_v' | 'nfc_a'
    first_seen_at       timestamptz NOT NULL DEFAULT now(),
    last_seen_at        timestamptz,
    UNIQUE (company_id, install_id)
);

-- ---------------------------------------------------------------------
-- 2. Etiquetas — identidade física e rastreabilidade de instrumento
-- ---------------------------------------------------------------------
--
-- Decisão (confirmada com a fábrica): o identificador COMERCIAL é o nosso
-- serial/QR. NFC UID, UHF EPC e UHF TID são identificadores TÉCNICOS
-- vinculados a ele. Isso permite trocar de fabricante/chip sem reescrever
-- a lógica do produto.

CREATE TABLE tag_batch (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    supplier            text        NOT NULL,
    purchase_order      text,
    hardware_model      text        NOT NULL,       -- 'MI8654TE'
    ic_part_number      text,                       -- 'FM13DT160' (a confirmar formalmente)
    ic_manufacturer     text,                       -- 'Shanghai Fudan Microelectronics'
    manufactured_on     date,
    received_on         date,
    quantity            integer     NOT NULL CHECK (quantity > 0),
    -- Calibração: sem isto o dado não vale em auditoria.
    calibration_cert_ref     text,
    calibration_cert_file    text,                  -- caminho no object storage
    calibration_points_c     numeric[],             -- ex.: {0.0, 5.0, 25.0}
    calibration_uncertainty_c numeric,              -- ex.: 0.5
    calibration_valid_until  date,
    notes               text,
    created_at          timestamptz NOT NULL DEFAULT now()
);

COMMENT ON COLUMN tag_batch.calibration_cert_ref IS
  'Auditoria ANVISA/RDC pede certificado rastreável. Lote sem certificado deve ser bloqueado para uso regulado.';

CREATE TABLE tag (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_id            uuid NOT NULL REFERENCES tag_batch(id),
    serial              text NOT NULL,              -- nosso serial impresso, ex. 'TT-A7K9P2X4'
    qr_payload          text NOT NULL,              -- conteúdo exato codificado no QR
    nfc_uid             text NOT NULL,              -- hex maiúsculo, sem separador
    uhf_epc             text,
    uhf_tid             text,
    state               tag_state NOT NULL DEFAULT 'estoque',
    owner_company_id    uuid REFERENCES company(id),
    -- Ciclo de vida / orçamento energético
    activation_cycles   integer     NOT NULL DEFAULT 0,
    last_voltage_v      numeric(4,2),
    last_voltage_at     timestamptz,
    retired_at          timestamptz,
    retired_reason      text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (serial),
    UNIQUE (qr_payload),
    UNIQUE (nfc_uid)
);

CREATE INDEX ON tag (state) WHERE state <> 'aposentada';
CREATE INDEX ON tag (owner_company_id);

-- ---------------------------------------------------------------------
-- 3. Perfis térmicos — versionados, nunca string livre
-- ---------------------------------------------------------------------
-- Corrige o defeito D7 (comparação de perfil por travessão no app).

CREATE TABLE thermal_profile (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code                text        NOT NULL,        -- 'REFRIG_2_8'
    label               text        NOT NULL,        -- '2 a 8 °C'
    version             integer     NOT NULL DEFAULT 1,
    min_c               numeric(5,2) NOT NULL,
    max_c               numeric(5,2) NOT NULL,
    -- Limites de alerta (soft) separados dos de ação (hard)
    alert_min_c         numeric(5,2),
    alert_max_c         numeric(5,2),
    -- Tolerância operacional: excursão mais curta que isto não vira ocorrência
    grace_seconds       integer     NOT NULL DEFAULT 0,
    -- Tempo acumulado fora da faixa que dispara ação
    max_tor_seconds     integer,
    mkt_limit_c         numeric(5,2),
    active              boolean     NOT NULL DEFAULT true,
    created_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (code, version),
    CHECK (min_c < max_c)
);

-- ---------------------------------------------------------------------
-- 4. Remessa, documentos e volumes
-- ---------------------------------------------------------------------

CREATE TABLE shipment (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code                text        NOT NULL,        -- 'REM-2026-00184'
    shipper_company_id  uuid NOT NULL REFERENCES company(id),
    carrier_company_id  uuid REFERENCES company(id),
    consignee_name      text,
    consignee_tax_id    text,
    consignee_address   jsonb,
    consignee_contact   jsonb,
    thermal_profile_id  uuid NOT NULL REFERENCES thermal_profile(id),
    planned_pickup_at   timestamptz,
    planned_delivery_at timestamptz,
    cargo_description   text,
    status              shipment_status NOT NULL DEFAULT 'preparacao',
    created_by          uuid REFERENCES app_user(id),
    created_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (shipper_company_id, code)
);

CREATE INDEX ON shipment (carrier_company_id, status);
CREATE INDEX ON shipment (shipper_company_id, created_at DESC);

CREATE TABLE shipment_document (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    shipment_id         uuid NOT NULL REFERENCES shipment(id) ON DELETE CASCADE,
    doc_type            text NOT NULL,               -- 'NFE','AWB','CTE','PEDIDO','OUTRO','SEM_DOC'
    doc_number          text,
    doc_key             text,                        -- chave de acesso NF-e, 44 dígitos
    -- Spec §6.2: guardar o conteúdo BRUTO lido, além dos campos interpretados
    scanned_raw         text,
    scanned_symbology   text,                        -- 'QR_CODE','CODE_128','DATA_MATRIX'
    scanned_at          timestamptz,
    entered_manually    boolean NOT NULL DEFAULT false,
    created_at          timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE volume (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    shipment_id         uuid NOT NULL REFERENCES shipment(id) ON DELETE CASCADE,
    sequence_no         integer NOT NULL,
    external_code       text,                        -- código já existente na caixa
    external_code_raw   text,
    monitored           boolean NOT NULL DEFAULT false,
    description         text,
    UNIQUE (shipment_id, sequence_no)
);

-- Vínculo etiqueta<->volume. Histórico: nunca sobrescrever, encerrar e criar novo.
CREATE TABLE tag_assignment (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    volume_id           uuid NOT NULL REFERENCES volume(id),
    tag_id              uuid NOT NULL REFERENCES tag(id),
    assigned_at         timestamptz NOT NULL DEFAULT now(),
    assigned_by         uuid REFERENCES app_user(id),
    assigned_device_id  uuid REFERENCES device(id),
    -- Como a identidade física foi provada no momento do vínculo:
    qr_scanned          boolean NOT NULL DEFAULT false,
    uid_matched_qr      boolean NOT NULL DEFAULT false,
    released_at         timestamptz,
    release_reason      text
);

-- Uma etiqueta não pode estar vinculada a dois volumes ao mesmo tempo.
CREATE UNIQUE INDEX tag_assignment_one_active
    ON tag_assignment (tag_id) WHERE released_at IS NULL;

-- ---------------------------------------------------------------------
-- 5. Sessão de monitoramento — o núcleo da auditoria
-- ---------------------------------------------------------------------
-- Uma sessão = um ciclo START..leitura de uma etiqueta.
-- Chave natural para idempotência: (tag_id, tag_start_epoch).

CREATE TABLE monitoring_session (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tag_id                  uuid NOT NULL REFERENCES tag(id),
    tag_assignment_id       uuid NOT NULL REFERENCES tag_assignment(id),
    shipment_id             uuid NOT NULL REFERENCES shipment(id),
    thermal_profile_id      uuid NOT NULL REFERENCES thermal_profile(id),

    -- ---- Base de tempo (defeitos D2/D4) --------------------------------
    -- Valor gravado NA etiqueta pelo celular no START. É a origem de todos
    -- os timestamps da série. NÃO é um relógio confiável.
    tag_start_epoch         bigint      NOT NULL,
    -- O que o celular achava que era a hora, no START.
    device_start_at         timestamptz NOT NULL,
    -- Hora do servidor no momento em que a ativação foi sincronizada.
    server_start_at         timestamptz NOT NULL DEFAULT now(),
    -- Desvio medido do relógio do celular contra a referência, no START.
    -- Positivo = celular adiantado.
    device_clock_skew_ms    bigint,
    clock_reference         text,                    -- 'ntp','servidor','nenhuma'
    -- Qual convenção o app que ativou usou ao gravar tag_start_epoch.
    -- Determina a fórmula do primeiro ponto da série. Ver o tipo time_base.
    time_base               time_base   NOT NULL DEFAULT 'start_instant',
    activation_os           activation_os,

    -- ---- Configuração efetivamente gravada na etiqueta ------------------
    delay_minutes           integer     NOT NULL DEFAULT 0,
    interval_seconds        integer     NOT NULL CHECK (interval_seconds BETWEEN 1 AND 65535),
    planned_count           integer     NOT NULL CHECK (planned_count > 0),
    configured_min_c        numeric(5,2) NOT NULL,
    configured_max_c        numeric(5,2) NOT NULL,
    storage_mode            smallint,                -- 3=normal, 1=comprimido, 6=limit2, 7=raw
    -- Confirmação de leitura de volta após o START (defeito D5)
    activation_verified     boolean     NOT NULL DEFAULT false,
    activation_verified_at  timestamptz,

    activated_by            uuid REFERENCES app_user(id),
    activated_device_id     uuid REFERENCES device(id),
    voltage_at_start_v      numeric(4,2),

    status                  session_status NOT NULL DEFAULT 'ativa',
    closed_at               timestamptz,

    created_at              timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tag_id, tag_start_epoch)
);

CREATE INDEX ON monitoring_session (shipment_id);
CREATE INDEX ON monitoring_session (status) WHERE status = 'ativa';

COMMENT ON COLUMN monitoring_session.tag_start_epoch IS
  'Gravado por InstructMap case 22 = System.currentTimeMillis()/1000 do CELULAR. '
  'Todo timestamp da série deriva daqui. Ver device_clock_skew_ms.';

-- ---------------------------------------------------------------------
-- 6. Leitura de etiqueta — evidência bruta, imutável
-- ---------------------------------------------------------------------
-- Uma sessão pode ser lida várias vezes (checkpoint intermediário, destino).
-- Cada leitura é uma evidência independente e é preservada inteira.

CREATE TABLE tag_read (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id              uuid NOT NULL REFERENCES monitoring_session(id),
    tag_id                  uuid NOT NULL REFERENCES tag(id),

    -- Idempotência do sync offline: o app gera esta chave e reenvia à vontade.
    ingest_key              text        NOT NULL,
    read_purpose            text        NOT NULL,    -- 'checkpoint','destino','diagnostico'

    device_id               uuid REFERENCES device(id),
    read_by                 uuid REFERENCES app_user(id),
    device_read_at          timestamptz NOT NULL,    -- relógio do celular
    server_received_at      timestamptz NOT NULL DEFAULT now(),
    device_clock_skew_ms    bigint,

    -- ---- P2: o dado bruto sobrevive ao decodificador --------------------
    raw_sdk_response        jsonb       NOT NULL,    -- array cru de getLoggingResult()
    raw_memory_hex          text,                    -- blocos lidos, se capturados
    sdk_version             text,
    decoder_version         text        NOT NULL,    -- versão do NOSSO decodificador

    -- ---- Cabeçalho decodificado ----------------------------------------
    tag_status_code         text,                    -- response[0]: 0/1/2/3
    reported_planned_count  integer,                 -- response[2]
    reported_sample_count   integer,                 -- response[3]
    reported_interval_s     integer,                 -- response[5]
    reported_delay_min      integer,                 -- response[4]
    voltage_v               numeric(4,2),
    instant_temp_c          numeric(6,2),
    battery_ok              boolean,

    -- ---- Deriva do RTC (defeito D4) -------------------------------------
    -- expected_end = tag_start_epoch + delay*60 + n*interval
    -- drift = device_read_at - expected_end  (aprox., inclui tempo desde a última amostra)
    rtc_drift_seconds       bigint,
    timestamps_corrected    boolean     NOT NULL DEFAULT false,

    -- ---- Cadeia de integridade -----------------------------------------
    payload_sha256          bytea       NOT NULL,
    prev_read_sha256        bytea,
    chain_sha256            bytea       NOT NULL,

    UNIQUE (ingest_key),
    UNIQUE (session_id, payload_sha256)
);

CREATE INDEX ON tag_read (session_id, device_read_at);

-- ---------------------------------------------------------------------
-- 7. Série de medições
-- ---------------------------------------------------------------------
-- DECISÃO DE MODELAGEM: a série é gravada UMA VEZ, lida inteira, e nunca
-- sofre UPDATE de linha individual. Guardar 4.864 linhas por leitura numa
-- tabela normalizada custa caro e não traz benefício de consulta real.
-- Guardamos a série como array (uma linha por leitura) + expandimos sob
-- demanda pela função tt.expand_series().
--
-- Se o volume crescer e houver consulta analítica cross-remessa pesada,
-- o caminho de migração é TimescaleDB/tabela particionada alimentada a
-- partir daqui — sem perder a evidência, que continua sendo o tag_read.

CREATE TABLE measurement_series (
    tag_read_id         uuid PRIMARY KEY REFERENCES tag_read(id),
    session_id          uuid NOT NULL REFERENCES monitoring_session(id),
    -- t0 real do primeiro ponto, já corrigido de deriva se aplicável
    first_sample_at     timestamptz NOT NULL,
    interval_seconds    integer     NOT NULL,
    sample_count        integer     NOT NULL,
    temperatures_c      real[]      NOT NULL,
    -- Bit de campo NFC por amostra, quando o SDK devolve "temp:field"
    field_flags         smallint[],
    -- Agregados pré-calculados (evitam varrer o array em toda consulta)
    min_c               real,
    max_c               real,
    avg_c               real,
    mkt_c               real,                        -- temperatura cinética média
    tor_below_seconds   integer,                     -- tempo acumulado abaixo do mínimo
    tor_above_seconds   integer,
    longest_excursion_s integer,
    rule_version        text,
    CHECK (sample_count = cardinality(temperatures_c))
);

CREATE INDEX ON measurement_series (session_id);

-- Expande a série em (índice, instante, temperatura). Usar em laudo/export.
CREATE OR REPLACE FUNCTION expand_series(p_tag_read_id uuid)
RETURNS TABLE (sample_index integer, measured_at timestamptz, temperature_c real)
LANGUAGE sql STABLE AS $$
    SELECT  i - 1                                                   AS sample_index,
            s.first_sample_at + ((i - 1) * s.interval_seconds) * interval '1 second',
            s.temperatures_c[i]
    FROM    measurement_series s,
            generate_series(1, s.sample_count) AS i
    WHERE   s.tag_read_id = p_tag_read_id
    ORDER BY i;
$$;

-- ---------------------------------------------------------------------
-- 8. Excursões — segmentos contínuos, não pontos soltos (lacuna F1)
-- ---------------------------------------------------------------------

CREATE TABLE excursion (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id          uuid NOT NULL REFERENCES monitoring_session(id),
    tag_read_id         uuid NOT NULL REFERENCES tag_read(id),
    kind                excursion_kind NOT NULL,
    grade               excursion_grade NOT NULL,
    started_at          timestamptz NOT NULL,
    ended_at            timestamptz NOT NULL,
    duration_seconds    integer     NOT NULL,
    sample_count        integer     NOT NULL,
    peak_c              real        NOT NULL,        -- pior temperatura do segmento
    limit_c             real        NOT NULL,        -- limite violado
    first_sample_index  integer     NOT NULL,
    -- P4: o veredito é reproduzível porque a regra está gravada
    rule_version        text        NOT NULL,
    evaluated_at        timestamptz NOT NULL DEFAULT now(),
    superseded_by       uuid REFERENCES excursion(id),
    CHECK (ended_at >= started_at)
);

CREATE INDEX ON excursion (session_id) WHERE superseded_by IS NULL;

-- ---------------------------------------------------------------------
-- 9. Custódia, ocorrências e ações
-- ---------------------------------------------------------------------

CREATE TABLE custody_event (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    shipment_id         uuid NOT NULL REFERENCES shipment(id),
    from_party          custody_party,
    to_party            custody_party NOT NULL,
    from_company_id     uuid REFERENCES company(id),
    to_company_id       uuid REFERENCES company(id),
    -- P3: os três tempos
    occurred_at         timestamptz NOT NULL,        -- declarado: quando aconteceu
    recorded_at         timestamptz NOT NULL DEFAULT now(),
    device_at           timestamptz,
    source              event_source NOT NULL,       -- automático / tempo real / retroativo
    volumes_confirmed   integer,
    receiver_name       text,
    evidence_url        text,
    note                text,
    recorded_by         uuid REFERENCES app_user(id),
    device_id           uuid REFERENCES device(id),
    geo                 jsonb                        -- {lat,lon,accuracy_m} quando autorizado
);

CREATE INDEX ON custody_event (shipment_id, occurred_at);

COMMENT ON COLUMN custody_event.source IS
  'Spec §11: a auditoria precisa distinguir evento automático, confirmado em tempo real '
  'e informado posteriormente. Nunca colapsar em um único timestamp.';

CREATE TABLE occurrence (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    shipment_id         uuid NOT NULL REFERENCES shipment(id),
    volume_id           uuid REFERENCES volume(id),
    excursion_id        uuid REFERENCES excursion(id),   -- preenchido se for térmica
    category            text NOT NULL,                   -- 'termica','avaria','atraso','divergencia',...
    grade               excursion_grade,
    occurred_at         timestamptz NOT NULL,
    recorded_at         timestamptz NOT NULL DEFAULT now(),
    source              event_source NOT NULL,
    title               text NOT NULL,
    detail              text,
    opened_by           uuid REFERENCES app_user(id),
    closed_at           timestamptz,
    closed_by           uuid REFERENCES app_user(id)
);

CREATE INDEX ON occurrence (shipment_id, occurred_at);

CREATE TABLE corrective_action (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    occurrence_id       uuid NOT NULL REFERENCES occurrence(id),
    action_type         text NOT NULL,               -- 'troca_gelox','recondicionamento','camara_fria',...
    occurred_at         timestamptz NOT NULL,
    recorded_at         timestamptz NOT NULL DEFAULT now(),
    source              event_source NOT NULL,
    note                text,
    evidence_url        text,
    performed_by        uuid REFERENCES app_user(id),
    company_id          uuid REFERENCES company(id)
);

-- ---------------------------------------------------------------------
-- 10. Laudo e verificação pública
-- ---------------------------------------------------------------------

CREATE TABLE report (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    shipment_id         uuid NOT NULL REFERENCES shipment(id),
    version             integer NOT NULL DEFAULT 1,
    generated_at        timestamptz NOT NULL DEFAULT now(),
    generated_by        uuid REFERENCES app_user(id),
    rule_version        text NOT NULL,
    decoder_version     text NOT NULL,
    verdict             text NOT NULL,               -- 'conforme','excursao_detectada','inconclusivo'
    -- Snapshot congelado: o laudo não pode mudar se o mundo mudar depois
    payload             jsonb NOT NULL,
    payload_sha256      bytea NOT NULL,
    -- Código curto impresso no PDF, para conferência pública
    verification_code   text NOT NULL,
    pdf_url             text,
    xlsx_url            text,
    UNIQUE (shipment_id, version),
    UNIQUE (verification_code)
);

-- ---------------------------------------------------------------------
-- 11. Trilha de auditoria de sistema (quem fez o quê)
-- ---------------------------------------------------------------------

CREATE TABLE audit_log (
    id              bigserial PRIMARY KEY,
    at              timestamptz NOT NULL DEFAULT now(),
    actor_user_id   uuid REFERENCES app_user(id),
    actor_company_id uuid REFERENCES company(id),
    device_id       uuid REFERENCES device(id),
    action          text NOT NULL,                   -- 'session.activate','read.ingest','report.generate'
    entity          text NOT NULL,
    entity_id       uuid,
    before          jsonb,
    after           jsonb,
    ip              inet,
    note            text
);

CREATE INDEX ON audit_log (entity, entity_id, at DESC);
CREATE INDEX ON audit_log (at DESC);

-- ---------------------------------------------------------------------
-- 12. Imutabilidade (P1)
-- ---------------------------------------------------------------------
-- Evidência não se corrige por UPDATE. Se o decodificador estava errado,
-- gera-se nova measurement_series/excursion e marca-se superseded_by.

CREATE OR REPLACE FUNCTION deny_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION
      'Tabela % é append-only (evidência de auditoria). Operação % bloqueada.',
      TG_TABLE_NAME, TG_OP;
END; $$;

CREATE TRIGGER tag_read_immutable
    BEFORE UPDATE OR DELETE ON tag_read
    FOR EACH ROW EXECUTE FUNCTION deny_mutation();

CREATE TRIGGER measurement_series_immutable
    BEFORE UPDATE OR DELETE ON measurement_series
    FOR EACH ROW EXECUTE FUNCTION deny_mutation();

CREATE TRIGGER audit_log_immutable
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION deny_mutation();

CREATE TRIGGER report_immutable
    BEFORE UPDATE OR DELETE ON report
    FOR EACH ROW EXECUTE FUNCTION deny_mutation();

-- excursion permite UPDATE apenas para preencher superseded_by
CREATE OR REPLACE FUNCTION excursion_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'excursion é append-only.';
    END IF;
    IF ROW(NEW.*) IS DISTINCT FROM ROW(OLD.*)
       AND NEW.superseded_by IS NOT DISTINCT FROM OLD.superseded_by THEN
        RAISE EXCEPTION 'excursion só aceita UPDATE de superseded_by.';
    END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER excursion_append_only
    BEFORE UPDATE OR DELETE ON excursion
    FOR EACH ROW EXECUTE FUNCTION excursion_guard();

-- ---------------------------------------------------------------------
-- 13. Visões de trabalho
-- ---------------------------------------------------------------------

-- Situação atual de cada volume monitorado.
CREATE OR REPLACE VIEW v_volume_status AS
SELECT  s.id                    AS shipment_id,
        s.code                  AS shipment_code,
        s.status                AS shipment_status,
        v.id                    AS volume_id,
        v.sequence_no,
        t.serial                AS tag_serial,
        t.nfc_uid,
        ms.id                   AS session_id,
        ms.status               AS session_status,
        ms.interval_seconds,
        lr.device_read_at       AS last_read_at,
        lr.rtc_drift_seconds,
        sr.sample_count,
        sr.min_c, sr.max_c, sr.mkt_c,
        sr.tor_below_seconds + sr.tor_above_seconds AS tor_total_seconds,
        (SELECT count(*) FROM excursion e
          WHERE e.session_id = ms.id AND e.superseded_by IS NULL)        AS excursion_count
FROM    shipment s
JOIN    volume v            ON v.shipment_id = s.id AND v.monitored
LEFT JOIN tag_assignment ta ON ta.volume_id = v.id AND ta.released_at IS NULL
LEFT JOIN tag t             ON t.id = ta.tag_id
LEFT JOIN monitoring_session ms ON ms.tag_assignment_id = ta.id
LEFT JOIN LATERAL (
        SELECT * FROM tag_read r
         WHERE r.session_id = ms.id
         ORDER BY r.device_read_at DESC LIMIT 1
) lr ON true
LEFT JOIN measurement_series sr ON sr.tag_read_id = lr.id;

-- Etiquetas que não deveriam mais ser usadas.
CREATE OR REPLACE VIEW v_tags_para_revisao AS
SELECT  t.serial, t.nfc_uid, t.state, t.activation_cycles,
        t.last_voltage_v, t.last_voltage_at,
        b.calibration_valid_until,
        CASE
          WHEN t.retired_at IS NOT NULL                        THEN 'aposentada'
          WHEN b.calibration_valid_until < current_date        THEN 'calibracao_vencida'
          WHEN t.last_voltage_v < 1.30                         THEN 'bateria_baixa'
          WHEN t.activation_cycles >= 20                       THEN 'ciclos_excedidos'
        END AS motivo
FROM    tag t
JOIN    tag_batch b ON b.id = t.batch_id
WHERE   t.retired_at IS NOT NULL
   OR   b.calibration_valid_until < current_date
   OR   t.last_voltage_v < 1.30
   OR   t.activation_cycles >= 20;
