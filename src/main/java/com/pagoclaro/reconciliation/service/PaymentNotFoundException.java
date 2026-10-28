package com.pagoclaro.reconciliation.service;

// Se lanza cuando la referencia del pago no existe
public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(String externalRef) {
        super("Payment not found: " + externalRef);
    }
}