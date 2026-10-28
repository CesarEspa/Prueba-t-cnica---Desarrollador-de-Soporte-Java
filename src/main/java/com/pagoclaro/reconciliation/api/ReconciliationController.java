package com.pagoclaro.reconciliation.api;


import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pagoclaro.reconciliation.domain.Decision;
import com.pagoclaro.reconciliation.service.PaymentNotFoundException;
import com.pagoclaro.reconciliation.service.ReconciliationService;

@RestController
@RequestMapping("/support/payments")
public class ReconciliationController {

    private final ReconciliationService service;

    public ReconciliationController(ReconciliationService service) {
        this.service = service;
    }

    @PostMapping("/{externalRef}/reconciliation")
    public ResponseEntity<?> reconcile(@PathVariable String externalRef) {

        // Validar la referencia: solo letras, números y guiones, máximo 40
        if (!externalRef.matches("[A-Za-z0-9-]{1,40}")) {
            return ResponseEntity.badRequest().body(Map.of("error", "INVALID_PAYMENT_REF"));
        }

        Decision decision = service.reconcile(externalRef);

        ReconciliationResponse response = new ReconciliationResponse(
                externalRef,
                decision.result().name(),
                decision.reason(),
                decision.confirmPayment(),
                decision.debitsToReverse().size());

        return ResponseEntity.ok(response);
    }

    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<Map<String, String>> paymentNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "PAYMENT_NOT_FOUND"));
    }
}