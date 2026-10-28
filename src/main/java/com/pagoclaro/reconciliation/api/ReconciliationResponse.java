package com.pagoclaro.reconciliation.api;

public record ReconciliationResponse(
        String paymentRef,
        String result,
        String reason,
        boolean paymentConfirmed,
        int reversalsCreated) {
}