package com.pagoclaro.reconciliation.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// El cerebro: mira la información de un pago y decide qué hacer.
// No toca la base de datos, solo decide.
public class ReconciliationRules {

    public Decision decide(PaymentInfo info) {

        // Regla 1: el banco dijo "aprobado" y "rechazado" para el mismo pago
        if (info.approvedCount() > 0 && info.declinedCount() > 0) {
            return Decision.manualReview("CONTRADICTORY_PROVIDER_EVENTS");
        }

        // Regla 2: no hay ninguna respuesta del banco
        if (info.approvedCount() == 0 && info.declinedCount() == 0) {
            return Decision.manualReview("NO_PROVIDER_EVENTS");
        }

        // Regla 3: solo hay rechazos
        if (info.approvedCount() == 0) {
            boolean isRejected = info.status().equals("REJECTED");
            boolean hasNoMoney = info.netDebit().compareTo(BigDecimal.ZERO) == 0;
            if (isRejected && hasNoMoney) {
                return Decision.noAction("ALREADY_CONSISTENT");
            }
            return Decision.manualReview("DECLINED_PAYMENT_NEEDS_REVIEW");
        }

        // Desde aquí el banco solo dijo "aprobado"

        // Regla 4: un pago REJECTED no se cambia automáticamente
        if (info.status().equals("REJECTED")) {
            return Decision.manualReview("REJECTED_BUT_PROVIDER_APPROVED");
        }

        // Débitos que ya tienen un reverso
        List<Long> reversedIds = new ArrayList<>();
        for (LedgerEntry entry : info.entries()) {
            if (!entry.isDebit()) {
                reversedIds.add(entry.relatedEntryId());
            }
        }

        // Débitos activos (sin reverso)
        List<Long> activeDebits = new ArrayList<>();
        for (LedgerEntry entry : info.entries()) {
            if (entry.isDebit() && !reversedIds.contains(entry.id())) {
                // Regla 5: cada débito debe ser por el monto exacto del pago
                if (entry.amount().compareTo(info.amount()) != 0) {
                    return Decision.manualReview("UNEXPECTED_DEBIT_AMOUNT");
                }
                activeDebits.add(entry.id());
            }
        }

        // Regla 6: aprobado pero sin débito. No inventamos movimientos.
        if (activeDebits.isEmpty()) {
            return Decision.manualReview("APPROVED_WITHOUT_DEBIT");
        }

        // Regla 7: el débito neto debe cuadrar con los débitos activos
        BigDecimal expectedNet = info.amount().multiply(BigDecimal.valueOf(activeDebits.size()));
        if (info.netDebit().compareTo(expectedNet) != 0) {
            return Decision.manualReview("LEDGER_DOES_NOT_ADD_UP");
        }

        // Se conserva el débito más antiguo (id menor) y se reversan los demás
        Collections.sort(activeDebits);
        List<Long> toReverse = new ArrayList<>(activeDebits.subList(1, activeDebits.size()));

        boolean needsConfirm = info.status().equals("PENDING");

        if (!needsConfirm && toReverse.isEmpty()) {
            return Decision.noAction("ALREADY_CONSISTENT");
        }

        String reason;
        if (needsConfirm && !toReverse.isEmpty()) {
            reason = "STATUS_CONFIRMED_AND_DUPLICATE_REVERSED";
        } else if (needsConfirm) {
            reason = "STATUS_CONFIRMED";
        } else {
            reason = "DUPLICATE_DEBIT_REVERSED";
        }
        return Decision.fix(reason, needsConfirm, toReverse);
    }
}