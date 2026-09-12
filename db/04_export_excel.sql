-- =====================================================================
-- ThermoTrace — visões de exportação para Excel
--
-- Cada visão é um plano de planilha: colunas achatadas, nomes em português,
-- nada de JSON ou array. O exportador (`tools/exportar_excel.py`) só faz
-- SELECT e escreve — toda a regra fica aqui, no banco.
--
-- Por que exportar do BANCO e não do celular:
--
--   O app de referência do fabricante gera planilha no aparelho — e as duas
--   plataformas geram formatos DIFERENTES: o Android usa `jxl` (.xls
--   BIFF8, formato de 1997, biblioteca sem manutenção) e o iOS usa
--   libxlsxwriter (.xlsx). Duas planilhas incompatíveis para o mesmo dado,
--   nenhuma delas reproduzível depois.
--
--   Exportando do banco: um formato só, qualquer pessoa gera a mesma
--   planilha a partir da mesma evidência, e o arquivo pode ser regerado
--   anos depois — que é exatamente o que uma auditoria pede.
-- =====================================================================

SET search_path = tt, public;

-- ---------------------------------------------------------------------
-- Aba "Resumo" — uma linha por volume monitorado
-- ---------------------------------------------------------------------
CREATE OR REPLACE VIEW v_export_resumo AS
SELECT
    s.id                                            AS _shipment_id,
    s.code                                          AS "Remessa",
    co_emb.trade_name                               AS "Embarcador",
    co_tra.trade_name                               AS "Transportadora",
    s.consignee_name                                AS "Destinatário",
    string_agg(DISTINCT d.doc_type || ' ' || COALESCE(d.doc_number,''), ' | ')
                                                    AS "Documentos",
    v.sequence_no                                   AS "Volume",
    COALESCE(v.external_code, '—')                  AS "Identificação da caixa",
    t.serial                                        AS "Etiqueta",
    t.nfc_uid                                       AS "UID NFC",
    tb.calibration_cert_ref                         AS "Certificado de calibração",
    tb.calibration_valid_until                      AS "Calibração válida até",
    tp.label                                        AS "Perfil térmico",
    ms.configured_min_c                             AS "Limite mínimo (°C)",
    ms.configured_max_c                             AS "Limite máximo (°C)",
    ser.interval_seconds / 60                       AS "Intervalo (min)",
    ser.sample_count                                AS "Registros",
    ser.first_sample_at                             AS "Início do monitoramento",
    ser.first_sample_at
      + ((ser.sample_count - 1) * ser.interval_seconds) * interval '1 second'
                                                    AS "Fim do monitoramento",
    ser.min_c                                       AS "Mínima (°C)",
    ser.max_c                                       AS "Máxima (°C)",
    round(ser.avg_c::numeric, 2)                    AS "Média (°C)",
    round(ser.mkt_c::numeric, 2)                    AS "MKT (°C)",
    round((ser.tor_below_seconds / 60.0)::numeric, 1) AS "Tempo abaixo (min)",
    round((ser.tor_above_seconds / 60.0)::numeric, 1) AS "Tempo acima (min)",
    round((ser.longest_excursion_s / 60.0)::numeric, 1) AS "Maior excursão contínua (min)",
    (SELECT count(*) FROM excursion e
      WHERE e.session_id = ms.id AND e.superseded_by IS NULL) AS "Excursões",
    CASE
      WHEN ser.tag_read_id IS NULL THEN 'Sem leitura'
      WHEN EXISTS (SELECT 1 FROM excursion e
                    WHERE e.session_id = ms.id AND e.superseded_by IS NULL
                      AND e.grade IN ('acao','critica')) THEN 'Excursão relevante'
      WHEN EXISTS (SELECT 1 FROM excursion e
                    WHERE e.session_id = ms.id AND e.superseded_by IS NULL) THEN 'Alerta'
      ELSE 'Conforme'
    END                                             AS "Resultado",
    rd.rtc_drift_seconds                            AS "Deriva do relógio (s)",
    CASE WHEN rd.timestamps_corrected THEN 'Sim' ELSE 'Não' END
                                                    AS "Horários corrigidos",
    ms.id                                           AS _session_id,
    rd.id                                           AS _tag_read_id
FROM shipment s
JOIN company co_emb            ON co_emb.id = s.shipper_company_id
LEFT JOIN company co_tra       ON co_tra.id = s.carrier_company_id
LEFT JOIN shipment_document d  ON d.shipment_id = s.id
JOIN thermal_profile tp        ON tp.id = s.thermal_profile_id
JOIN volume v                  ON v.shipment_id = s.id AND v.monitored
JOIN tag_assignment ta         ON ta.volume_id = v.id
JOIN tag t                     ON t.id = ta.tag_id
JOIN tag_batch tb              ON tb.id = t.batch_id
JOIN monitoring_session ms     ON ms.tag_assignment_id = ta.id
LEFT JOIN LATERAL (
    SELECT * FROM tag_read r WHERE r.session_id = ms.id
     ORDER BY r.device_read_at DESC LIMIT 1
) rd ON true
LEFT JOIN measurement_series ser ON ser.tag_read_id = rd.id
GROUP BY s.id, s.code, co_emb.trade_name, co_tra.trade_name, s.consignee_name,
         v.sequence_no, v.external_code, t.serial, t.nfc_uid,
         tb.calibration_cert_ref, tb.calibration_valid_until,
         tp.label, ms.id, ser.tag_read_id, ser.interval_seconds, ser.sample_count,
         ser.first_sample_at, ser.min_c, ser.max_c, ser.avg_c, ser.mkt_c,
         ser.tor_below_seconds, ser.tor_above_seconds, ser.longest_excursion_s,
         rd.id, rd.rtc_drift_seconds, rd.timestamps_corrected;

-- ---------------------------------------------------------------------
-- Aba "Medições" — a série completa, uma linha por ponto
-- ---------------------------------------------------------------------
-- Esta é a aba que o auditor abre primeiro. Cada linha diz também SE
-- aquele ponto estava fora da faixa — sem exigir fórmula no Excel.
CREATE OR REPLACE VIEW v_export_medicoes AS
SELECT
    s.id                                    AS _shipment_id,
    s.code                                  AS "Remessa",
    v.sequence_no                           AS "Volume",
    t.serial                                AS "Etiqueta",
    x.sample_index + 1                      AS "Nº",
    x.measured_at AT TIME ZONE 'America/Sao_Paulo' AS "Data/hora",
    round(x.temperature_c::numeric, 2)      AS "Temperatura (°C)",
    ms.configured_min_c                     AS "Mínimo (°C)",
    ms.configured_max_c                     AS "Máximo (°C)",
    CASE
      WHEN x.temperature_c > ms.configured_max_c THEN 'Acima'
      WHEN x.temperature_c < ms.configured_min_c THEN 'Abaixo'
      ELSE 'Na faixa'
    END                                     AS "Situação",
    ms.id                                   AS _session_id
FROM measurement_series ser
JOIN monitoring_session ms ON ms.id = ser.session_id
JOIN tag_assignment ta     ON ta.id = ms.tag_assignment_id
JOIN tag t                 ON t.id = ta.tag_id
JOIN volume v              ON v.id = ta.volume_id
JOIN shipment s            ON s.id = ms.shipment_id
CROSS JOIN LATERAL expand_series(ser.tag_read_id) x;

-- ---------------------------------------------------------------------
-- Aba "Excursões"
-- ---------------------------------------------------------------------
CREATE OR REPLACE VIEW v_export_excursoes AS
SELECT
    s.id                                    AS _shipment_id,
    s.code                                  AS "Remessa",
    v.sequence_no                           AS "Volume",
    t.serial                                AS "Etiqueta",
    CASE e.kind WHEN 'acima_limite' THEN 'Acima do limite'
                ELSE 'Abaixo do limite' END AS "Tipo",
    CASE e.grade WHEN 'alerta' THEN 'Alerta'
                 WHEN 'acao'   THEN 'Requer ação'
                 ELSE 'Crítica' END         AS "Gravidade",
    e.started_at AT TIME ZONE 'America/Sao_Paulo' AS "Início",
    e.ended_at   AT TIME ZONE 'America/Sao_Paulo' AS "Fim",
    round((e.duration_seconds / 60.0)::numeric, 1) AS "Duração (min)",
    e.sample_count                          AS "Registros",
    round(e.peak_c::numeric, 2)             AS "Pico (°C)",
    round(e.limit_c::numeric, 2)            AS "Limite violado (°C)",
    round((abs(e.peak_c - e.limit_c))::numeric, 2) AS "Desvio (°C)",
    e.rule_version                          AS "Versão da regra",
    e.evaluated_at                          AS "Avaliado em"
FROM excursion e
JOIN monitoring_session ms ON ms.id = e.session_id
JOIN tag_assignment ta     ON ta.id = ms.tag_assignment_id
JOIN tag t                 ON t.id = ta.tag_id
JOIN volume v              ON v.id = ta.volume_id
JOIN shipment s            ON s.id = ms.shipment_id
WHERE e.superseded_by IS NULL;

-- ---------------------------------------------------------------------
-- Aba "Custódia"
-- ---------------------------------------------------------------------
CREATE OR REPLACE VIEW v_export_custodia AS
SELECT
    s.id                                    AS _shipment_id,
    s.code                                  AS "Remessa",
    initcap(ce.to_party::text)              AS "Passou para",
    COALESCE(c_to.trade_name, s.consignee_name) AS "Empresa",
    ce.occurred_at AT TIME ZONE 'America/Sao_Paulo' AS "Quando aconteceu",
    ce.recorded_at AT TIME ZONE 'America/Sao_Paulo' AS "Quando foi lançado",
    round((EXTRACT(EPOCH FROM (ce.recorded_at - ce.occurred_at)) / 60)::numeric, 0)
                                            AS "Atraso do lançamento (min)",
    CASE ce.source
      WHEN 'automatico_nfc'          THEN 'Automático (NFC)'
      WHEN 'confirmado_tempo_real'   THEN 'Confirmado em tempo real'
      ELSE 'Informado posteriormente'
    END                                     AS "Origem do registro",
    ce.volumes_confirmed                    AS "Volumes",
    ce.receiver_name                        AS "Recebedor",
    u.full_name                             AS "Registrado por"
FROM custody_event ce
JOIN shipment s        ON s.id = ce.shipment_id
LEFT JOIN company c_to ON c_to.id = ce.to_company_id
LEFT JOIN app_user u   ON u.id = ce.recorded_by;

-- ---------------------------------------------------------------------
-- Aba "Auditoria" — a aba que sustenta as outras
-- ---------------------------------------------------------------------
-- Sem isto a planilha é só um gráfico bonito. Aqui está de onde o número
-- veio, quem leu, com qual aparelho, com qual decodificador, e o hash que
-- prova que o dado bruto não foi tocado.
CREATE OR REPLACE VIEW v_export_auditoria AS
SELECT
    s.id                                    AS _shipment_id,
    s.code                                  AS "Remessa",
    v.sequence_no                           AS "Volume",
    t.serial                                AS "Etiqueta",
    t.nfc_uid                               AS "UID NFC",
    CASE r.read_purpose WHEN 'destino' THEN 'Leitura final'
                        WHEN 'checkpoint' THEN 'Checkpoint'
                        ELSE r.read_purpose END AS "Tipo de leitura",
    r.device_read_at AT TIME ZONE 'America/Sao_Paulo'   AS "Lido em (aparelho)",
    r.server_received_at AT TIME ZONE 'America/Sao_Paulo' AS "Recebido pelo servidor",
    r.device_clock_skew_ms                  AS "Desvio do relógio (ms)",
    ms.time_base::text                      AS "Base de tempo",
    ms.activation_os::text                  AS "Ativado por",
    CASE WHEN ms.activation_verified THEN 'Sim' ELSE 'NÃO' END AS "Ativação confirmada",
    ta.qr_scanned                           AS "QR conferido",
    ta.uid_matched_qr                       AS "UID confere com o QR",
    r.rtc_drift_seconds                     AS "Deriva do relógio (s)",
    r.voltage_v                             AS "Bateria (V)",
    dv.model                                AS "Aparelho",
    dv.os_version                           AS "Sistema",
    dv.app_version                          AS "Versão do app",
    r.sdk_version                           AS "SDK da etiqueta",
    r.decoder_version                       AS "Decodificador",
    ser.rule_version                        AS "Versão da regra",
    encode(r.payload_sha256, 'hex')         AS "SHA-256 do dado bruto",
    encode(r.chain_sha256, 'hex')           AS "SHA-256 encadeado"
FROM tag_read r
JOIN monitoring_session ms ON ms.id = r.session_id
JOIN tag_assignment ta     ON ta.id = ms.tag_assignment_id
JOIN tag t                 ON t.id = ta.tag_id
JOIN volume v              ON v.id = ta.volume_id
JOIN shipment s            ON s.id = ms.shipment_id
LEFT JOIN device dv        ON dv.id = r.device_id
LEFT JOIN measurement_series ser ON ser.tag_read_id = r.id;
