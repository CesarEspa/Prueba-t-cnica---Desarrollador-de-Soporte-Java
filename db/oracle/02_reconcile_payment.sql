
CREATE OR REPLACE PROCEDURE reconcile_payment (
    p_external_ref IN  PAYMENT.external_ref%TYPE,
    p_result       OUT VARCHAR2,
    p_reason       OUT VARCHAR2
) AS
    v_payment_id   PAYMENT.id%TYPE;
    v_status       PAYMENT.status%TYPE;
    v_amount       PAYMENT.amount%TYPE;
    v_approved     NUMBER;
    v_declined     NUMBER;
    v_net_debit    NUMBER;
    v_active       NUMBER;
    v_bad_amount   NUMBER;
    v_keep_id      LEDGER_ENTRY.id%TYPE;
    v_reversed     NUMBER := 0;
    v_confirm      BOOLEAN := FALSE;
    v_audit_count  NUMBER;
BEGIN
    -- 1. Bloquear el pago
    BEGIN
        SELECT id, status, amount
          INTO v_payment_id, v_status, v_amount
          FROM PAYMENT
         WHERE external_ref = p_external_ref
           FOR UPDATE;
    EXCEPTION
        WHEN NO_DATA_FOUND THEN
            p_result := 'NOT_FOUND';
            p_reason := 'PAYMENT_NOT_FOUND';
            RETURN;
    END;

    -- 2. Leer la evidencia
    SELECT NVL(SUM(CASE WHEN provider_status = 'APPROVED' THEN 1 ELSE 0 END), 0),
           NVL(SUM(CASE WHEN provider_status = 'DECLINED' THEN 1 ELSE 0 END), 0)
      INTO v_approved, v_declined
      FROM PROVIDER_EVENT
     WHERE payment_id = v_payment_id;

    SELECT NVL(SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE -amount END), 0)
      INTO v_net_debit
      FROM LEDGER_ENTRY
     WHERE payment_id = v_payment_id;

    -- Débitos activos (sin reverso)
    SELECT COUNT(*),
           NVL(SUM(CASE WHEN d.amount <> v_amount THEN 1 ELSE 0 END), 0),
           MIN(d.id)
      INTO v_active, v_bad_amount, v_keep_id
      FROM LEDGER_ENTRY d
     WHERE d.payment_id = v_payment_id
       AND d.entry_type = 'DEBIT'
       AND NOT EXISTS (SELECT 1 FROM LEDGER_ENTRY r
                        WHERE r.related_entry_id = d.id
                          AND r.entry_type = 'REVERSAL');

    -- 3. Decidir (mismas reglas que ReconciliationRules.java)
    p_result := 'MANUAL_REVIEW';

    IF v_approved > 0 AND v_declined > 0 THEN
        p_reason := 'CONTRADICTORY_PROVIDER_EVENTS';
    ELSIF v_approved = 0 AND v_declined = 0 THEN
        p_reason := 'NO_PROVIDER_EVENTS';
    ELSIF v_approved = 0 THEN
        IF v_status = 'REJECTED' AND v_net_debit = 0 THEN
            p_result := 'NO_ACTION';
            p_reason := 'ALREADY_CONSISTENT';
        ELSE
            p_reason := 'DECLINED_PAYMENT_NEEDS_REVIEW';
        END IF;
    ELSIF v_status = 'REJECTED' THEN
        p_reason := 'REJECTED_BUT_PROVIDER_APPROVED';
    ELSIF v_bad_amount > 0 THEN
        p_reason := 'UNEXPECTED_DEBIT_AMOUNT';
    ELSIF v_active = 0 THEN
        p_reason := 'APPROVED_WITHOUT_DEBIT';
    ELSIF v_net_debit <> v_amount * v_active THEN
        p_reason := 'LEDGER_DOES_NOT_ADD_UP';
    ELSE
        v_confirm := (v_status = 'PENDING');

        IF NOT v_confirm AND v_active = 1 THEN
            p_result := 'NO_ACTION';
            p_reason := 'ALREADY_CONSISTENT';
        ELSE
            p_result := 'RECONCILED';
            IF v_confirm AND v_active > 1 THEN
                p_reason := 'STATUS_CONFIRMED_AND_DUPLICATE_REVERSED';
            ELSIF v_confirm THEN
                p_reason := 'STATUS_CONFIRMED';
            ELSE
                p_reason := 'DUPLICATE_DEBIT_REVERSED';
            END IF;
        END IF;
    END IF;

    -- 4. Aplicar
    IF p_result = 'RECONCILED' THEN
        IF v_confirm THEN
            UPDATE PAYMENT
               SET status = 'CONFIRMED',
                   updated_at = SYSTIMESTAMP,
                   row_version = row_version + 1
             WHERE id = v_payment_id
               AND status = 'PENDING';
        END IF;

        -- Reversar todos los débitos activos excepto el más antiguo
        INSERT INTO LEDGER_ENTRY (id, payment_id, entry_type, amount, related_entry_id, created_at)
        SELECT LEDGER_ENTRY_SEQ.NEXTVAL, v_payment_id, 'REVERSAL', d.amount, d.id, SYSTIMESTAMP
          FROM LEDGER_ENTRY d
         WHERE d.payment_id = v_payment_id
           AND d.entry_type = 'DEBIT'
           AND d.id <> v_keep_id
           AND NOT EXISTS (SELECT 1 FROM LEDGER_ENTRY r
                            WHERE r.related_entry_id = d.id
                              AND r.entry_type = 'REVERSAL');
        v_reversed := SQL%ROWCOUNT;

        INSERT INTO SUPPORT_AUDIT (id, payment_id, action, reason, created_at)
        VALUES (SUPPORT_AUDIT_SEQ.NEXTVAL, v_payment_id, 'RECONCILED',
                p_reason || ' confirmed=' || CASE WHEN v_confirm THEN 'true' ELSE 'false' END
                         || ' reversedDebits=' || v_reversed,
                SYSTIMESTAMP);

    ELSIF p_result = 'MANUAL_REVIEW' THEN
        SELECT COUNT(*)
          INTO v_audit_count
          FROM SUPPORT_AUDIT
         WHERE payment_id = v_payment_id
           AND action = 'MANUAL_REVIEW'
           AND reason = p_reason;

        IF v_audit_count = 0 THEN
            INSERT INTO SUPPORT_AUDIT (id, payment_id, action, reason, created_at)
            VALUES (SUPPORT_AUDIT_SEQ.NEXTVAL, v_payment_id, 'MANUAL_REVIEW', p_reason, SYSTIMESTAMP);
        END IF;
    END IF;

    COMMIT;

EXCEPTION
    WHEN OTHERS THEN
        ROLLBACK;
        RAISE;
END reconcile_payment;
/
