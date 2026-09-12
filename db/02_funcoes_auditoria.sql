-- =====================================================================
-- ThermoTrace — funções de auditoria térmica
--
-- Cobre a lacuna F1 da especificação: hoje ela conta PONTOS fora da faixa.
-- Auditoria de cadeia fria trabalha com TEMPO fora da faixa (TOR),
-- maior excursão CONTÍNUA e MKT. Contar pontos superestima aberturas
-- rápidas de caixa e subestima desvios longos.
--
-- Toda avaliação grava rule_version. Se a regra mudar, os laudos antigos
-- continuam reproduzíveis (princípio P4 do schema).
-- =====================================================================

SET search_path = tt, public;

-- Versão corrente do motor de regras. Suba isto a cada mudança de critério.
CREATE OR REPLACE FUNCTION current_rule_version() RETURNS text
LANGUAGE sql IMMUTABLE AS $$ SELECT 'rules-2026.08.1'::text $$;

CREATE OR REPLACE FUNCTION current_decoder_version() RETURNS text
LANGUAGE sql IMMUTABLE AS $$ SELECT 'fm13dt160-decoder-1.0'::text $$;

-- ---------------------------------------------------------------------
-- MKT — Mean Kinetic Temperature
-- ---------------------------------------------------------------------
--            ΔH / R
-- MKT = ----------------------------------------
--        -ln( (1/n) · Σ exp( -ΔH / (R · T_i) ) )
--
-- ΔH = 83,144 kJ/mol (valor convencional da indústria farmacêutica)
-- R  = 8,314 J/(mol·K)  →  ΔH/R = 10000 K
-- T_i em Kelvin. Resultado devolvido em °C.
--
-- Assume amostras equiespaçadas — verdadeiro aqui: o logger grava em
-- intervalo fixo. Se um dia houver intervalo variável, ponderar por Δt.
-- ---------------------------------------------------------------------

CREATE OR REPLACE FUNCTION mkt_celsius(temps real[], delta_h_over_r double precision DEFAULT 10000.0)
RETURNS real
LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE
    acc double precision := 0;
    n   integer := cardinality(temps);
    t   real;
    k   double precision;
BEGIN
    IF n IS NULL OR n = 0 THEN
        RETURN NULL;
    END IF;

    FOREACH t IN ARRAY temps LOOP
        k := t + 273.15;
        IF k <= 0 THEN                     -- leitura impossível, ignora
            n := n - 1;
            CONTINUE;
        END IF;
        acc := acc + exp(-delta_h_over_r / k);
    END LOOP;

    IF n <= 0 OR acc <= 0 THEN
        RETURN NULL;
    END IF;

    RETURN (delta_h_over_r / (-ln(acc / n)) - 273.15)::real;
END; $$;

-- ---------------------------------------------------------------------
-- Segmentação de excursões
-- ---------------------------------------------------------------------
-- Percorre a série e devolve SEGMENTOS CONTÍNUOS fora da faixa.
-- grace_seconds: segmento mais curto que isso não é excursão (abrir a
-- caixa, transferir entre docas). Vem do perfil térmico, não é chute.
-- ---------------------------------------------------------------------

CREATE OR REPLACE FUNCTION detect_excursions(
    p_temps           real[],
    p_first_sample_at timestamptz,
    p_interval_s      integer,
    p_min_c           numeric,
    p_max_c           numeric,
    p_grace_s         integer DEFAULT 0
)
RETURNS TABLE (
    kind               excursion_kind,
    first_sample_index integer,
    sample_count       integer,
    started_at         timestamptz,
    ended_at           timestamptz,
    duration_seconds   integer,
    peak_c             real,
    limit_c            real
)
LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE
    i            integer;
    n            integer := cardinality(p_temps);
    t            real;
    cur_kind     excursion_kind := NULL;
    seg_start    integer := NULL;
    seg_peak     real := NULL;
    this_kind    excursion_kind;
BEGIN
    IF n IS NULL OR n = 0 THEN RETURN; END IF;

    FOR i IN 1..n + 1 LOOP
        IF i <= n THEN
            t := p_temps[i];
            this_kind := CASE
                            WHEN t > p_max_c THEN 'acima_limite'::excursion_kind
                            WHEN t < p_min_c THEN 'abaixo_limite'::excursion_kind
                            ELSE NULL
                         END;
        ELSE
            this_kind := NULL;              -- sentinela: fecha segmento aberto
        END IF;

        IF this_kind IS DISTINCT FROM cur_kind THEN
            -- fecha o segmento anterior
            IF cur_kind IS NOT NULL THEN
                first_sample_index := seg_start - 1;
                sample_count       := (i - 1) - seg_start + 1;
                started_at         := p_first_sample_at + ((seg_start - 1) * p_interval_s) * interval '1 second';
                -- o desvio persiste até a próxima amostra em faixa
                ended_at           := p_first_sample_at + ((i - 1) * p_interval_s) * interval '1 second';
                duration_seconds   := sample_count * p_interval_s;
                kind               := cur_kind;
                peak_c             := seg_peak;
                limit_c            := CASE WHEN cur_kind = 'acima_limite'
                                           THEN p_max_c::real ELSE p_min_c::real END;
                IF duration_seconds > COALESCE(p_grace_s, 0) THEN
                    RETURN NEXT;
                END IF;
            END IF;
            -- abre o novo
            cur_kind  := this_kind;
            seg_start := i;
            seg_peak  := t;
        ELSIF cur_kind IS NOT NULL THEN
            IF cur_kind = 'acima_limite' THEN
                seg_peak := greatest(seg_peak, t);
            ELSE
                seg_peak := least(seg_peak, t);
            END IF;
        END IF;
    END LOOP;
    RETURN;
END; $$;

-- ---------------------------------------------------------------------
-- Classificação de gravidade
-- ---------------------------------------------------------------------

CREATE OR REPLACE FUNCTION grade_excursion(
    p_duration_s integer,
    p_peak_c     real,
    p_limit_c    real,
    p_profile    thermal_profile
) RETURNS excursion_grade
LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE
        -- desvio grande em magnitude, ou muito longo → crítica
        WHEN abs(p_peak_c - p_limit_c) >= 5.0
          OR p_duration_s >= 4 * 3600                       THEN 'critica'::excursion_grade
        -- passou do teto de tempo fora de faixa do perfil → exige ação
        WHEN p_profile.max_tor_seconds IS NOT NULL
         AND p_duration_s >= p_profile.max_tor_seconds      THEN 'acao'::excursion_grade
        WHEN p_duration_s >= 30 * 60                        THEN 'acao'::excursion_grade
        ELSE 'alerta'::excursion_grade
    END;
$$;

-- ---------------------------------------------------------------------
-- Ingestão idempotente de uma leitura de etiqueta
-- ---------------------------------------------------------------------
-- Chamada pelo backend quando o app sincroniza (possivelmente horas
-- depois, possivelmente duas vezes). Reenvio com o mesmo ingest_key é
-- no-op e devolve o id já existente.
--
-- Faz, em uma transação:
--   1. valida a identidade da etiqueta contra a sessão
--   2. calcula a deriva do RTC e corrige a base de tempo
--   3. grava a evidência bruta + hash encadeado
--   4. grava a série, agregados, MKT e TOR
--   5. detecta e grava as excursões com rule_version
-- ---------------------------------------------------------------------

CREATE OR REPLACE FUNCTION ingest_tag_read(
    p_ingest_key        text,
    p_session_id        uuid,
    p_nfc_uid           text,
    p_read_purpose      text,
    p_device_id         uuid,
    p_read_by           uuid,
    p_device_read_at    timestamptz,
    p_device_skew_ms    bigint,
    p_raw_sdk_response  jsonb,          -- array cru de getLoggingResult()
    p_temps             real[],         -- série já decodificada pelo app
    p_field_flags       smallint[],
    p_voltage_v         numeric,
    p_instant_temp_c    numeric,
    p_sdk_version       text,
    p_raw_memory_hex    text DEFAULT NULL,
    p_correct_drift     boolean DEFAULT true
) RETURNS uuid
LANGUAGE plpgsql AS $$
DECLARE
    v_existing      uuid;
    v_read_id       uuid;
    v_session       monitoring_session;
    v_tag           tag;
    v_profile       thermal_profile;
    v_n             integer := COALESCE(cardinality(p_temps), 0);
    v_nominal_start timestamptz;
    v_nominal_end   timestamptz;
    v_drift_s       bigint;
    v_first_at      timestamptz;
    v_eff_interval  double precision;
    v_payload_hash  bytea;
    v_prev_hash     bytea;
    v_chain_hash    bytea;
    v_tor_below     integer := 0;
    v_tor_above     integer := 0;
    v_longest       integer := 0;
    r               record;
BEGIN
    -- 0. idempotência -------------------------------------------------
    SELECT id INTO v_existing FROM tag_read WHERE ingest_key = p_ingest_key;
    IF FOUND THEN
        RETURN v_existing;
    END IF;

    SELECT * INTO v_session FROM monitoring_session WHERE id = p_session_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Sessão % inexistente', p_session_id;
    END IF;

    SELECT * INTO v_tag FROM tag WHERE id = v_session.tag_id;
    SELECT * INTO v_profile FROM thermal_profile WHERE id = v_session.thermal_profile_id;

    -- 1. identidade — defeito D1 do app, barrado também aqui ----------
    IF upper(replace(p_nfc_uid, ':', '')) <> upper(v_tag.nfc_uid) THEN
        RAISE EXCEPTION
          'UID lido (%) não corresponde à etiqueta da sessão (%). Leitura recusada.',
          p_nfc_uid, v_tag.nfc_uid;
    END IF;

    -- 2. base de tempo e deriva do RTC — defeitos D2/D4 ---------------
    -- O chip grava a amostra ao FIM de cada janela: o 1º ponto vale para
    -- start + delay + 1 intervalo.
    -- MAS o significado de `start` depende de quem ativou:
    --   start_instant (Android) → o delay ainda precisa ser somado
    --   first_window  (iOS)     → o delay JÁ está embutido no epoch gravado
    v_nominal_start := to_timestamp(v_session.tag_start_epoch)
                       + CASE v_session.time_base
                           WHEN 'start_instant'
                             THEN (v_session.delay_minutes * 60) * interval '1 second'
                           ELSE interval '0'
                         END
                       + v_session.interval_seconds * interval '1 second';
    v_nominal_end   := v_nominal_start
                       + (GREATEST(v_n - 1, 0) * v_session.interval_seconds) * interval '1 second';
    v_drift_s       := EXTRACT(EPOCH FROM (p_device_read_at - v_nominal_end))::bigint;

    -- Corrige a deriva distribuindo-a linearmente sobre a série.
    -- A parte do desvio que é apenas "tempo desde a última amostra"
    -- (< 1 intervalo) não é deriva; só corrigimos o excedente.
    v_first_at     := v_nominal_start;
    v_eff_interval := v_session.interval_seconds;

    IF p_correct_drift AND v_n > 1
       AND abs(v_drift_s) > v_session.interval_seconds THEN
        v_eff_interval := v_session.interval_seconds
                          + (v_drift_s - v_session.interval_seconds)::double precision / (v_n - 1);
    END IF;

    -- Também alinhamos a origem pelo desvio conhecido do relógio do celular
    -- que fez o START (positivo = celular adiantado → tudo veio adiantado).
    IF v_session.device_clock_skew_ms IS NOT NULL THEN
        v_first_at := v_first_at - (v_session.device_clock_skew_ms / 1000.0) * interval '1 second';
    END IF;

    -- 3. evidência bruta + cadeia de hash -----------------------------
    v_payload_hash := digest(
        convert_to(p_raw_sdk_response::text || COALESCE(p_raw_memory_hex, ''), 'UTF8'), 'sha256');

    SELECT chain_sha256 INTO v_prev_hash
      FROM tag_read WHERE session_id = p_session_id
     ORDER BY server_received_at DESC LIMIT 1;

    v_chain_hash := digest(COALESCE(v_prev_hash, '\x'::bytea) || v_payload_hash, 'sha256');

    INSERT INTO tag_read (
        session_id, tag_id, ingest_key, read_purpose,
        device_id, read_by, device_read_at, device_clock_skew_ms,
        raw_sdk_response, raw_memory_hex, sdk_version, decoder_version,
        tag_status_code, reported_planned_count, reported_sample_count,
        reported_interval_s, reported_delay_min,
        voltage_v, instant_temp_c,
        battery_ok, rtc_drift_seconds, timestamps_corrected,
        payload_sha256, prev_read_sha256, chain_sha256
    ) VALUES (
        p_session_id, v_session.tag_id, p_ingest_key, p_read_purpose,
        p_device_id, p_read_by, p_device_read_at, p_device_skew_ms,
        p_raw_sdk_response, p_raw_memory_hex, p_sdk_version, current_decoder_version(),
        p_raw_sdk_response->>0,
        NULLIF(p_raw_sdk_response->>2,'')::integer,
        NULLIF(p_raw_sdk_response->>3,'')::integer,
        NULLIF(p_raw_sdk_response->>5,'')::integer,
        NULLIF(p_raw_sdk_response->>4,'')::integer,
        p_voltage_v, p_instant_temp_c,
        (p_voltage_v IS NULL OR p_voltage_v >= 1.30),
        v_drift_s,
        (v_eff_interval <> v_session.interval_seconds),
        v_payload_hash, v_prev_hash, v_chain_hash
    ) RETURNING id INTO v_read_id;

    -- 4. série + agregados --------------------------------------------
    IF v_n > 0 THEN
        FOR r IN
            SELECT * FROM detect_excursions(
                p_temps, v_first_at, round(v_eff_interval)::integer,
                v_session.configured_min_c, v_session.configured_max_c,
                v_profile.grace_seconds)
        LOOP
            IF r.kind = 'abaixo_limite' THEN
                v_tor_below := v_tor_below + r.duration_seconds;
            ELSE
                v_tor_above := v_tor_above + r.duration_seconds;
            END IF;
            v_longest := GREATEST(v_longest, r.duration_seconds);

            INSERT INTO excursion (
                session_id, tag_read_id, kind, grade,
                started_at, ended_at, duration_seconds, sample_count,
                peak_c, limit_c, first_sample_index, rule_version
            ) VALUES (
                p_session_id, v_read_id, r.kind,
                grade_excursion(r.duration_seconds, r.peak_c, r.limit_c, v_profile),
                r.started_at, r.ended_at, r.duration_seconds, r.sample_count,
                r.peak_c, r.limit_c, r.first_sample_index, current_rule_version()
            );
        END LOOP;

        INSERT INTO measurement_series (
            tag_read_id, session_id, first_sample_at, interval_seconds,
            sample_count, temperatures_c, field_flags,
            min_c, max_c, avg_c, mkt_c,
            tor_below_seconds, tor_above_seconds, longest_excursion_s, rule_version
        )
        SELECT v_read_id, p_session_id, v_first_at, round(v_eff_interval)::integer,
               v_n, p_temps, p_field_flags,
               (SELECT min(x) FROM unnest(p_temps) x),
               (SELECT max(x) FROM unnest(p_temps) x),
               (SELECT avg(x)::real FROM unnest(p_temps) x),
               mkt_celsius(p_temps),
               v_tor_below, v_tor_above, v_longest, current_rule_version();
    END IF;

    -- 5. estado da etiqueta -------------------------------------------
    UPDATE tag
       SET last_voltage_v = COALESCE(p_voltage_v, last_voltage_v),
           last_voltage_at = CASE WHEN p_voltage_v IS NOT NULL
                                  THEN p_device_read_at ELSE last_voltage_at END,
           state = CASE WHEN p_read_purpose = 'destino' THEN 'lida'::tag_state
                        ELSE state END
     WHERE id = v_session.tag_id;

    INSERT INTO audit_log (actor_user_id, device_id, action, entity, entity_id, after)
    VALUES (p_read_by, p_device_id, 'read.ingest', 'tag_read', v_read_id,
            jsonb_build_object('samples', v_n, 'drift_s', v_drift_s,
                               'tor_below_s', v_tor_below, 'tor_above_s', v_tor_above));

    RETURN v_read_id;
END; $$;

-- ---------------------------------------------------------------------
-- Verificação de integridade da cadeia de evidência
-- ---------------------------------------------------------------------
-- Recalcula o encadeamento de hashes de uma sessão. É isto que se roda
-- na frente do auditor.

CREATE OR REPLACE FUNCTION verify_chain(p_session_id uuid)
RETURNS TABLE (tag_read_id uuid, seq integer, ok boolean, detail text)
LANGUAGE plpgsql STABLE AS $$
DECLARE
    r        record;
    pos      integer := 0;
    expected bytea := NULL;
    calc     bytea;
BEGIN
    FOR r IN
        SELECT * FROM tag_read
         WHERE session_id = p_session_id
         ORDER BY server_received_at
    LOOP
        pos := pos + 1;
        calc := digest(
            convert_to(r.raw_sdk_response::text || COALESCE(r.raw_memory_hex,''), 'UTF8'),
            'sha256');

        tag_read_id := r.id;
        seq         := pos;

        IF calc <> r.payload_sha256 THEN
            ok := false; detail := 'payload_sha256 não confere com o payload armazenado';
        ELSIF r.prev_read_sha256 IS DISTINCT FROM expected THEN
            ok := false; detail := 'elo quebrado: prev_read_sha256 inesperado';
        ELSIF digest(COALESCE(expected, '\x'::bytea) || calc, 'sha256') <> r.chain_sha256 THEN
            ok := false; detail := 'chain_sha256 não confere';
        ELSE
            ok := true;  detail := 'ok';
        END IF;

        expected := r.chain_sha256;
        RETURN NEXT;
    END LOOP;
END; $$;

-- ---------------------------------------------------------------------
-- Resumo de laudo por remessa
-- ---------------------------------------------------------------------

CREATE OR REPLACE FUNCTION report_summary(p_shipment_id uuid)
RETURNS jsonb
LANGUAGE sql STABLE AS $$
SELECT jsonb_build_object(
    'shipment_code',   s.code,
    'thermal_profile', jsonb_build_object('code', tp.code, 'min_c', tp.min_c, 'max_c', tp.max_c),
    'rule_version',    current_rule_version(),
    'decoder_version', current_decoder_version(),
    'generated_at',    now(),
    'volumes', (
      SELECT jsonb_agg(jsonb_build_object(
          'volume',            v.sequence_no,
          'tag_serial',        t.serial,
          'nfc_uid',           t.nfc_uid,
          'calibration_cert',  b.calibration_cert_ref,
          'monitored_from',    ser.first_sample_at,
          'monitored_to',      ser.first_sample_at
                                 + ((ser.sample_count - 1) * ser.interval_seconds) * interval '1 second',
          'interval_seconds',  ser.interval_seconds,
          'samples',           ser.sample_count,
          'min_c',             ser.min_c,
          'max_c',             ser.max_c,
          'avg_c',             ser.avg_c,
          'mkt_c',             ser.mkt_c,
          'tor_below_seconds', ser.tor_below_seconds,
          'tor_above_seconds', ser.tor_above_seconds,
          'longest_excursion_seconds', ser.longest_excursion_s,
          'rtc_drift_seconds', rd.rtc_drift_seconds,
          'timestamps_corrected', rd.timestamps_corrected,
          'excursions', (
             SELECT COALESCE(jsonb_agg(jsonb_build_object(
                 'kind', e.kind, 'grade', e.grade,
                 'from', e.started_at, 'to', e.ended_at,
                 'duration_seconds', e.duration_seconds,
                 'peak_c', e.peak_c, 'limit_c', e.limit_c) ORDER BY e.started_at), '[]'::jsonb)
             FROM excursion e
             WHERE e.session_id = ms.id AND e.superseded_by IS NULL)
      ) ORDER BY v.sequence_no)
      FROM volume v
      JOIN tag_assignment ta ON ta.volume_id = v.id
      JOIN tag t             ON t.id = ta.tag_id
      JOIN tag_batch b       ON b.id = t.batch_id
      JOIN monitoring_session ms ON ms.tag_assignment_id = ta.id
      LEFT JOIN LATERAL (SELECT * FROM tag_read r
                          WHERE r.session_id = ms.id
                          ORDER BY r.device_read_at DESC LIMIT 1) rd ON true
      LEFT JOIN measurement_series ser ON ser.tag_read_id = rd.id
      WHERE v.shipment_id = s.id AND v.monitored
    ),
    'custody', (
      SELECT COALESCE(jsonb_agg(jsonb_build_object(
          'to', ce.to_party, 'occurred_at', ce.occurred_at,
          'recorded_at', ce.recorded_at, 'source', ce.source) ORDER BY ce.occurred_at), '[]'::jsonb)
      FROM custody_event ce WHERE ce.shipment_id = s.id
    )
)
FROM shipment s
JOIN thermal_profile tp ON tp.id = s.thermal_profile_id
WHERE s.id = p_shipment_id;
$$;
