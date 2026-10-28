
WITH recent_payments AS (
    SELECT id AS payment_id FROM PAYMENT
     WHERE updated_at >= SYSTIMESTAMP - INTERVAL '24' HOUR
    UNION
    SELECT payment_id FROM PROVIDER_EVENT
     WHERE received_at >= SYSTIMESTAMP - INTERVAL '24' HOUR
    UNION
    SELECT payment_id FROM LEDGER_ENTRY
     WHERE created_at >= SYSTIMESTAMP - INTERVAL '24' HOUR
),
ledger_totals AS (
    -- Débitos suman, reversos restan
    SELECT payment_id,
           SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE -amount END) AS net_debit
      FROM LEDGER_ENTRY
     GROUP BY payment_id
),
event_totals AS (
    SELECT payment_id,
           SUM(CASE WHEN provider_status = 'APPROVED' THEN 1 ELSE 0 END) AS approved,
           SUM(CASE WHEN provider_status = 'DECLINED' THEN 1 ELSE 0 END) AS declined
      FROM PROVIDER_EVENT
     GROUP BY payment_id
),
summary AS (
    SELECT p.external_ref,
           p.status,
           p.amount,
           NVL(l.net_debit, 0) AS net_debit,
           NVL(e.approved, 0)  AS approved,
           NVL(e.declined, 0)  AS declined,
           -- Se espera un débito neto igual al monto si el pago está
           -- confirmado o si el proveedor solo aprobó; en otro caso, 0.
           CASE
               WHEN p.status = 'CONFIRMED'
                 OR (NVL(e.approved, 0) > 0 AND NVL(e.declined, 0) = 0)
               THEN p.amount
               ELSE 0
           END AS expected_net_debit
      FROM PAYMENT p
      JOIN recent_payments r ON r.payment_id = p.id
      LEFT JOIN ledger_totals l ON l.payment_id = p.id
      LEFT JOIN event_totals  e ON e.payment_id = p.id
)
SELECT external_ref,
       status,
       amount,
       net_debit,
       expected_net_debit,
       approved,
       declined,
       CASE
           WHEN approved > 0 AND declined > 0       THEN 'CONTRADICTORY_PROVIDER_EVENTS'
           WHEN net_debit <> expected_net_debit     THEN 'NET_DEBIT_MISMATCH'
           ELSE                                          'STATUS_MISMATCH'
       END AS issue
  FROM summary
 WHERE (approved > 0 AND declined > 0)
    OR net_debit <> expected_net_debit
    OR (status = 'PENDING'   AND approved + declined > 0)
    OR (status = 'CONFIRMED' AND approved = 0)
    OR (status = 'REJECTED'  AND approved > 0)
 ORDER BY external_ref;