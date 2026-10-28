-- =====================================================================
-- Cambios opcionales al esquema. Ninguno es obligatorio para que la
-- solución funcione; agregan seguridad y velocidad.
-- =====================================================================

-- 1. RED DE SEGURIDAD: un débito solo puede tener UN reverso.
--    Aunque el código fallara, Oracle rechaza un segundo reverso del
--    mismo débito. Funciona porque los DEBIT tienen related_entry_id
--    NULL, y Oracle no guarda en el índice las filas con valor NULL.
CREATE UNIQUE INDEX ux_ledger_one_reversal ON LEDGER_ENTRY (related_entry_id);

-- 2. Búsquedas por pago. Oracle NO crea índices automáticamente para
--    las llaves foráneas; sin ellos, cada conciliación recorre la
--    tabla completa.
CREATE INDEX ix_provider_event_payment ON PROVIDER_EVENT (payment_id);
CREATE INDEX ix_ledger_entry_payment   ON LEDGER_ENTRY (payment_id);
CREATE INDEX ix_support_audit_payment  ON SUPPORT_AUDIT (payment_id, action);

-- 3. Consulta de detección de las últimas 24 horas
CREATE INDEX ix_payment_updated_at         ON PAYMENT (updated_at);
CREATE INDEX ix_provider_event_received_at ON PROVIDER_EVENT (received_at);
CREATE INDEX ix_ledger_entry_created_at    ON LEDGER_ENTRY (created_at);