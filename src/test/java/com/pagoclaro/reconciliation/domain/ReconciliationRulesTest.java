package com.pagoclaro.reconciliation.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

class ReconciliationRulesTest {

    ReconciliationRules rules = new ReconciliationRules();

    // Atajos para crear líneas del ledger
    LedgerEntry debit(long id, long amount) {
        return new LedgerEntry(id, "DEBIT", BigDecimal.valueOf(amount), null);
    }

    LedgerEntry reversal(long id, long amount, long relatedId) {
        return new LedgerEntry(id, "REVERSAL", BigDecimal.valueOf(amount), relatedId);
    }

    @Test
    void ax91PendienteConAprobacionSeConfirma() {
        PaymentInfo info = new PaymentInfo("PENDING", BigDecimal.valueOf(125000), 1, 0,
                List.of(debit(5001, 125000)));

        Decision decision = rules.decide(info);

        assertEquals(Result.RECONCILED, decision.result());
        assertTrue(decision.confirmPayment());
        assertTrue(decision.debitsToReverse().isEmpty());
    }

    @Test
    void bx17DebitoDuplicadoSeReversaElMasReciente() {
        PaymentInfo info = new PaymentInfo("CONFIRMED", BigDecimal.valueOf(89000), 2, 0,
                List.of(debit(5002, 89000), debit(5003, 89000)));

        Decision decision = rules.decide(info);

        assertEquals(Result.RECONCILED, decision.result());
        assertEquals(List.of(5003L), decision.debitsToReverse());
    }

    @Test
    void bx17YaCorregidoNoHaceNada() {
        PaymentInfo info = new PaymentInfo("CONFIRMED", BigDecimal.valueOf(89000), 2, 0,
                List.of(debit(5002, 89000), debit(5003, 89000), reversal(6000, 89000, 5003)));

        Decision decision = rules.decide(info);

        assertEquals(Result.NO_ACTION, decision.result());
    }

    @Test
    void cq22EvidenciaContradictoriaVaARevisionManual() {
        PaymentInfo info = new PaymentInfo("PENDING", BigDecimal.valueOf(54000), 1, 1, List.of());

        Decision decision = rules.decide(info);

        assertEquals(Result.MANUAL_REVIEW, decision.result());
        assertEquals("CONTRADICTORY_PROVIDER_EVENTS", decision.reason());
    }

    @Test
    void dk44PagoSanoNoHaceNada() {
        PaymentInfo info = new PaymentInfo("CONFIRMED", BigDecimal.valueOf(31000), 1, 0,
                List.of(debit(5004, 31000)));

        Decision decision = rules.decide(info);

        assertEquals(Result.NO_ACTION, decision.result());
    }

    // Casos de borde

    @Test
    void aprobadoSinDebitoNoInventaMovimientos() {
        PaymentInfo info = new PaymentInfo("PENDING", BigDecimal.valueOf(50000), 1, 0, List.of());

        Decision decision = rules.decide(info);

        assertEquals(Result.MANUAL_REVIEW, decision.result());
        assertEquals("APPROVED_WITHOUT_DEBIT", decision.reason());
    }

    @Test
    void debitoConMontoDistintoVaARevisionManual() {
        PaymentInfo info = new PaymentInfo("CONFIRMED", BigDecimal.valueOf(89000), 1, 0,
                List.of(debit(7001, 50000)));

        Decision decision = rules.decide(info);

        assertEquals(Result.MANUAL_REVIEW, decision.result());
        assertEquals("UNEXPECTED_DEBIT_AMOUNT", decision.reason());
    }
}
