package com.pagoclaro.reconciliation.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pagoclaro.reconciliation.domain.Decision;
import com.pagoclaro.reconciliation.domain.PaymentInfo;
import com.pagoclaro.reconciliation.domain.ReconciliationRules;
import com.pagoclaro.reconciliation.domain.Result;
import com.pagoclaro.reconciliation.persistence.PaymentRepository;

@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final PaymentRepository repository;
    private final ReconciliationRules rules = new ReconciliationRules();

    public ReconciliationService(PaymentRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Decision reconcile(String externalRef) {

        // 1. Bloquear el pago, si otra petición llega, espera aquí
        Long paymentId = repository.findIdAndLock(externalRef);
        if (paymentId == null) {
            throw new PaymentNotFoundException(externalRef);
        }

        PaymentInfo info = repository.loadInfo(paymentId);


        Decision decision = rules.decide(info);

        if (decision.result() == Result.RECONCILED) {
            if (decision.confirmPayment()) {
                repository.confirmPayment(paymentId);
            }
            for (Long debitId : decision.debitsToReverse()) {
                repository.addReversal(paymentId, debitId, info.amount());
            }
            String detail = decision.reason()
                    + " confirmed=" + decision.confirmPayment()
                    + " reversedDebits=" + decision.debitsToReverse();
            repository.addAudit(paymentId, "RECONCILED", detail);
        }

        if (decision.result() == Result.MANUAL_REVIEW) {
            if (!repository.auditExists(paymentId, "MANUAL_REVIEW", decision.reason())) {
                repository.addAudit(paymentId, "MANUAL_REVIEW", decision.reason());
            }
        }

        log.info("reconciliation paymentRef={} result={} reason={}",
                externalRef, decision.result(), decision.reason());

        return decision;
    }
}