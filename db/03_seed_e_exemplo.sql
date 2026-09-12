-- =====================================================================
-- ThermoTrace — seed de perfis térmicos + exemplo ponta a ponta
--
-- Rode depois de 01_schema.sql e 02_funcoes_auditoria.sql.
-- O exemplo simula uma remessa real com uma excursão de 50 minutos e
-- mostra as consultas que você usará na frente do auditor.
-- =====================================================================

SET search_path = tt, public;

-- ---------------------------------------------------------------------
-- Perfis térmicos (corrige o defeito D7: nunca mais string livre)
-- ---------------------------------------------------------------------
INSERT INTO thermal_profile (code, label, min_c, max_c, alert_min_c, alert_max_c,
                             grace_seconds, max_tor_seconds, mkt_limit_c)
VALUES
 ('REFRIG_2_8',   '2 a 8 °C (refrigerado)',      2.0,  8.0,  2.5,  7.5,  600,  3*3600, 8.0),
 ('AMBIENTE_15_25','15 a 25 °C (ambiente controlado)', 15.0, 25.0, 16.0, 24.0, 1800, 8*3600, 25.0),
 ('CONGELADO_M25_M15','-25 a -15 °C (congelado)', -25.0, -15.0, -24.0, -16.0, 300, 1*3600, -15.0),
 ('CONGELADO_M80','-80 a -60 °C (ultracongelado)', -80.0, -60.0, NULL, NULL, 0, 1800, NULL)
ON CONFLICT (code, version) DO NOTHING;

COMMENT ON TABLE thermal_profile IS
 'grace_seconds: excursões mais curtas que isso são ruído operacional (abrir a caixa, '
 'transferência de doca) e não geram ocorrência. max_tor_seconds: tempo acumulado fora '
 'de faixa que eleva a gravidade para "acao". Ambos devem ser acordados com o RT do cliente.';

-- =====================================================================
-- EXEMPLO COMPLETO
-- =====================================================================
DO $$
DECLARE
    v_shipper   uuid; v_carrier uuid; v_user uuid; v_device uuid;
    v_batch     uuid; v_tag uuid; v_profile uuid;
    v_shipment  uuid; v_volume uuid; v_assign uuid; v_session uuid;
    v_read      uuid;
    v_start     bigint := extract(epoch from (now() - interval '30 hours'))::bigint;
    v_temps     real[];
    i           integer;
BEGIN
    -- Empresas e usuário --------------------------------------------------
    INSERT INTO company (legal_name, trade_name, tax_id)
      VALUES ('Distribuidora Exemplo LTDA','DistExemplo','12345678000190') RETURNING id INTO v_shipper;
    INSERT INTO company (legal_name, trade_name, tax_id)
      VALUES ('AtivaCargo Transportes LTDA','AtivaCargo','98765432000110') RETURNING id INTO v_carrier;

    INSERT INTO app_user (email, full_name)
      VALUES ('operador@distexemplo.com.br','Operador de Expedição') RETURNING id INTO v_user;
    INSERT INTO membership VALUES (v_user, v_shipper, 'embarcador');

    INSERT INTO device (company_id, install_id, model, os_version, app_version, nfc_stack)
      VALUES (v_shipper,'inst-0001','Moto G84','Android 14','0.2.0','nfc_v') RETURNING id INTO v_device;

    -- Lote de etiquetas COM certificado de calibração ---------------------
    INSERT INTO tag_batch (supplier, hardware_model, ic_part_number, ic_manufacturer,
                           quantity, calibration_cert_ref, calibration_points_c,
                           calibration_uncertainty_c, calibration_valid_until)
      VALUES ('Fornecedor NFC','MI8654TE','FM13DT160','Shanghai Fudan Microelectronics',
              10000,'CERT-2026-0042', ARRAY[0.0,5.0,25.0]::numeric[], 0.5,
              current_date + interval '1 year')
      RETURNING id INTO v_batch;

    -- Uma etiqueta do arquivo CSV que a fábrica entrega com o lote --------
    INSERT INTO tag (batch_id, serial, qr_payload, nfc_uid, uhf_epc, uhf_tid, owner_company_id)
      VALUES (v_batch,'TT-A7K9P2X4','TT-A7K9P2X4','04A83B7291500', 'E28011700000020D', 'E2003412',
              v_shipper)
      RETURNING id INTO v_tag;

    SELECT id INTO v_profile FROM thermal_profile WHERE code = 'REFRIG_2_8';

    -- Remessa, documento, volume ------------------------------------------
    INSERT INTO shipment (code, shipper_company_id, carrier_company_id, consignee_name,
                          thermal_profile_id, planned_pickup_at, planned_delivery_at,
                          cargo_description, status, created_by)
      VALUES ('REM-2026-00184', v_shipper, v_carrier, 'Hospital Exemplo',
              v_profile, now() - interval '31 hours', now() + interval '2 hours',
              'Medicamento termolábil', 'em_transporte', v_user)
      RETURNING id INTO v_shipment;

    INSERT INTO shipment_document (shipment_id, doc_type, doc_number, doc_key,
                                   scanned_raw, scanned_symbology, scanned_at)
      VALUES (v_shipment,'NFE','000123456',
              '35260812345678000190550010001234561000000017',
              '35260812345678000190550010001234561000000017','QR_CODE', now() - interval '31 hours');

    INSERT INTO volume (shipment_id, sequence_no, external_code, monitored)
      VALUES (v_shipment, 1, 'CX-001', true) RETURNING id INTO v_volume;

    -- Vínculo com prova de identidade física (QR + UID conferidos) --------
    INSERT INTO tag_assignment (volume_id, tag_id, assigned_by, assigned_device_id,
                                qr_scanned, uid_matched_qr)
      VALUES (v_volume, v_tag, v_user, v_device, true, true) RETURNING id INTO v_assign;

    -- Sessão de monitoramento: note os TRÊS tempos + o skew do celular ----
    INSERT INTO monitoring_session (
        tag_id, tag_assignment_id, shipment_id, thermal_profile_id,
        tag_start_epoch, device_start_at, server_start_at, device_clock_skew_ms, clock_reference,
        delay_minutes, interval_seconds, planned_count,
        configured_min_c, configured_max_c, storage_mode,
        activation_verified, activation_verified_at,
        activated_by, activated_device_id, voltage_at_start_v)
      VALUES (
        v_tag, v_assign, v_shipment, v_profile,
        v_start, to_timestamp(v_start), to_timestamp(v_start), 2400, 'ntp',  -- celular 2,4 s adiantado
        0, 600, 288,
        2.0, 8.0, 3,
        true, to_timestamp(v_start) + interval '40 seconds',
        v_user, v_device, 1.52)
      RETURNING id INTO v_session;

    UPDATE tag SET state='monitorando', activation_cycles = activation_cycles + 1 WHERE id=v_tag;

    -- Série simulada: 180 pontos a cada 10 min = 30 h.
    -- Pontos 100..104 sobem para ~11 °C → excursão de 50 min acima do limite.
    v_temps := ARRAY[]::real[];
    FOR i IN 1..180 LOOP
        IF i BETWEEN 100 AND 104 THEN
            v_temps := v_temps || (10.5 + (i - 100) * 0.4)::real;
        ELSE
            v_temps := v_temps || (4.0 + sin(i::double precision / 7) * 1.2)::real;
        END IF;
    END LOOP;

    -- Ingestão idempotente (rode duas vezes: a segunda é no-op) -----------
    v_read := ingest_tag_read(
        p_ingest_key       => 'dev:inst-0001:sess:1:read:1',
        p_session_id       => v_session,
        p_nfc_uid          => '04A83B7291500',
        p_read_purpose     => 'destino',
        p_device_id        => v_device,
        p_read_by          => v_user,
        p_device_read_at   => now(),
        p_device_skew_ms   => 2400,
        p_raw_sdk_response => jsonb_build_array('3', v_start::text, '288', '180', '0', '600',
                                                '2.75','11.1','2.0','8.0','0','5'),
        p_temps            => v_temps,
        p_field_flags      => NULL,
        p_voltage_v        => 1.47,
        p_instant_temp_c   => 4.25,
        p_sdk_version      => 'fmsh-nfcinstruct-1.0.0',
        p_raw_memory_hex   => NULL);

    RAISE NOTICE 'Leitura ingerida: %', v_read;

    -- Custódia com os três tempos separados -------------------------------
    INSERT INTO custody_event (shipment_id, from_party, to_party, from_company_id, to_company_id,
                               occurred_at, device_at, source, volumes_confirmed, recorded_by, device_id)
      VALUES (v_shipment,'embarcador','transportadora', v_shipper, v_carrier,
              now() - interval '30 hours', now() - interval '30 hours',
              'confirmado_tempo_real', 1, v_user, v_device);

    -- Entrega lançada retroativamente (spec §11) --------------------------
    INSERT INTO custody_event (shipment_id, from_party, to_party, from_company_id, to_company_id,
                               occurred_at, device_at, source, volumes_confirmed, receiver_name, recorded_by)
      VALUES (v_shipment,'transportadora','destinatario', v_carrier, NULL,
              now() - interval '2 hours',        -- quando de fato aconteceu
              NULL,
              'informado_posteriormente',        -- lançado depois: a auditoria precisa saber
              1, 'Recebedor do Hospital', v_user);
END $$;

-- =====================================================================
-- CONSULTAS DE AUDITORIA
-- =====================================================================

-- 1. Panorama por volume
SELECT shipment_code, tag_serial, sample_count, min_c, max_c, mkt_c,
       tor_total_seconds, excursion_count, rtc_drift_seconds
FROM v_volume_status;

-- 2. Excursões detectadas, com a versão da regra que as julgou
SELECT e.kind, e.grade, e.started_at, e.ended_at,
       e.duration_seconds/60 AS minutos, e.peak_c, e.limit_c, e.rule_version
FROM excursion e
JOIN monitoring_session ms ON ms.id = e.session_id
WHERE e.superseded_by IS NULL
ORDER BY e.started_at;

-- 3. Integridade da cadeia de evidência (é isto que se roda na auditoria)
SELECT * FROM verify_chain((SELECT id FROM monitoring_session LIMIT 1));

-- 4. Série completa com timestamps — export CSV/Excel
SELECT sample_index, measured_at, temperature_c
FROM expand_series((SELECT id FROM tag_read ORDER BY server_received_at DESC LIMIT 1))
LIMIT 20;

-- 5. Laudo em JSON, pronto para virar PDF
SELECT jsonb_pretty(report_summary((SELECT id FROM shipment LIMIT 1)));

-- 6. Prova de que a evidência é imutável (deve FALHAR)
-- UPDATE tag_read SET voltage_v = 9.99;
-- ERRO: Tabela tag_read é append-only (evidência de auditoria).

-- 7. Distinção auditável entre o que foi medido e o que foi declarado
SELECT to_party, occurred_at AS "quando aconteceu",
       recorded_at AS "quando foi lançado",
       recorded_at - occurred_at AS atraso_lancamento, source
FROM custody_event ORDER BY occurred_at;
