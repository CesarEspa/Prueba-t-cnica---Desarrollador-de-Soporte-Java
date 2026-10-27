package com.pagoclaro.reconciliation.domain;

import java.math.BigDecimal;

// clase que solo guarda datos
public record LedgerEntry(long id, String type, BigDecimal amount, Long relatedEntryId) {

    public boolean isDebit() {
        return type.equals("DEBIT");
    }
}