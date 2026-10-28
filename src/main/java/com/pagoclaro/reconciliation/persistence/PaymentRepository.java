package com.pagoclaro.reconciliation.persistence;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.pagoclaro.reconciliation.domain.LedgerEntry;
import com.pagoclaro.reconciliation.domain.PaymentInfo;

@Repository
public class PaymentRepository {

    private final JdbcTemplate jdbc;

    public PaymentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- LEER ----------

    // Busca el pago y lo BLOQUEA hasta que termine la transacción.
    // Si otra petición llega al mismo tiempo, espera aquí.
    public Long findIdAndLock(String externalRef) {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM PAYMENT WHERE external_ref = ? FOR UPDATE",
                Long.class, externalRef);

        if (ids.isEmpty()) {
            return null;
        }
        return ids.get(0);
    }

    // Arma la "foto" completa del pago para el cerebro
    public PaymentInfo loadInfo(long paymentId) {
        Map<String, Object> payment = jdbc.queryForMap( 
                "SELECT status, amount FROM PAYMENT WHERE id = ?", paymentId);

        Integer approved = jdbc.queryForObject(
                "SELECT COUNT(*) FROM PROVIDER_EVENT WHERE payment_id = ? AND provider_status = 'APPROVED'",
                Integer.class, paymentId);

        Integer declined = jdbc.queryForObject(
                "SELECT COUNT(*) FROM PROVIDER_EVENT WHERE payment_id = ? AND provider_status = 'DECLINED'",
                Integer.class, paymentId);

        List<LedgerEntry> entries = jdbc.query(
                "SELECT id, entry_type, amount, related_entry_id FROM LEDGER_ENTRY WHERE payment_id = ? ORDER BY id",
                (rs, rowNum) -> {
                    long related = rs.getLong("related_entry_id");
                    Long relatedId = rs.wasNull() ? null : related;
                    return new LedgerEntry(
                            rs.getLong("id"),
                            rs.getString("entry_type"),
                            rs.getBigDecimal("amount"),
                            relatedId);
                },
                paymentId);

        return new PaymentInfo(
                (String) payment.get("status"),
                (BigDecimal) payment.get("amount"),
                approved,
                declined,
                entries);
    }

    // ---------- ESCRIBIR ----------

    // Solo cambia a CONFIRMED si todavía está PENDING (doble seguridad)
    public void confirmPayment(long paymentId) {
        jdbc.update("""
                UPDATE PAYMENT
                SET status = 'CONFIRMED',
                    updated_at = CURRENT_TIMESTAMP,
                    row_version = row_version + 1
                WHERE id = ? AND status = 'PENDING'
                """, paymentId);
    }

    // Agrega un reverso que apunta al débito que corrige. Nunca se borra nada.
    public void addReversal(long paymentId, long debitId, BigDecimal amount) {
        jdbc.update("""
                INSERT INTO LEDGER_ENTRY (id, payment_id, entry_type, amount, related_entry_id, created_at)
                VALUES (LEDGER_ENTRY_SEQ.NEXTVAL, ?, 'REVERSAL', ?, ?, CURRENT_TIMESTAMP)
                """, paymentId, amount, debitId);
    }

    // Revisa si ya existe un registro de auditoría igual (para no duplicar)
    public boolean auditExists(long paymentId, String action, String reason) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM SUPPORT_AUDIT WHERE payment_id = ? AND action = ? AND reason = ?",
                Integer.class, paymentId, action, reason);
        return count > 0;
    }

    public void addAudit(long paymentId, String action, String reason) {
        jdbc.update("""
                INSERT INTO SUPPORT_AUDIT (id, payment_id, action, reason, created_at)
                VALUES (SUPPORT_AUDIT_SEQ.NEXTVAL, ?, ?, ?, CURRENT_TIMESTAMP)
                """, paymentId, action, reason);
    }
}