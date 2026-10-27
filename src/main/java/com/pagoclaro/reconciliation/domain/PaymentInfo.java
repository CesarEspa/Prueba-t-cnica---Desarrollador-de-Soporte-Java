package com.pagoclaro.reconciliation.domain;

import java.math.BigDecimal;
import java.util.List;

// Toda la información de un pago: su estado, los eventos del banco y el ledger
public record PaymentInfo(
        String status,
        BigDecimal amount,
        int approvedCount,
        int declinedCount,
        List<LedgerEntry> entries) {

    // Débitos suman, reversos restan
    public BigDecimal netDebit() {
        BigDecimal total = BigDecimal.ZERO;
        for (LedgerEntry entry : entries) {
            if (entry.isDebit()) {
                total = total.add(entry.amount());
            } else {
                total = total.subtract(entry.amount());
            }
        }
        return total;
    }
}
