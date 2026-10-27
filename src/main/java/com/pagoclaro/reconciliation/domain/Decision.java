package com.pagoclaro.reconciliation.domain;

import java.util.List;

public record Decision(
        Result result,
        String reason,
        boolean confirmPayment,
        List<Long> debitsToReverse) {

    public static Decision noAction(String reason) {
        return new Decision(Result.NO_ACTION, reason, false, List.of());
    }

    public static Decision manualReview(String reason) {
        return new Decision(Result.MANUAL_REVIEW, reason, false, List.of());
    }

    public static Decision fix(String reason, boolean confirmPayment, List<Long> debitsToReverse) {
        return new Decision(Result.RECONCILED, reason, confirmPayment, debitsToReverse);
    }
}