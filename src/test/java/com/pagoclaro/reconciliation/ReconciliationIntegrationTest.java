package com.pagoclaro.reconciliation;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import com.pagoclaro.reconciliation.domain.Decision;
import com.pagoclaro.reconciliation.service.PaymentNotFoundException;
import com.pagoclaro.reconciliation.service.ReconciliationService;

@SpringBootTest
@Sql(scripts = {"/reset.sql", "/data.sql"})
@Sql(scripts = {"/reset.sql", "/data.sql"}, executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class ReconciliationIntegrationTest {

    @Autowired
    ReconciliationService service;

    @Autowired
    JdbcTemplate jdbc;

    int countReversals(String ref) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM LEDGER_ENTRY l
                JOIN PAYMENT p ON p.id = l.payment_id
                WHERE p.external_ref = ? AND l.entry_type = 'REVERSAL'
                """, Integer.class, ref);
    }

    int countAudits(String ref) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM SUPPORT_AUDIT a
                JOIN PAYMENT p ON p.id = a.payment_id
                WHERE p.external_ref = ?
                """, Integer.class, ref);
    }

    String statusOf(String ref) {
        return jdbc.queryForObject(
                "SELECT status FROM PAYMENT WHERE external_ref = ?", String.class, ref);
    }

    @Test
    void repetirLaConciliacionNoDuplicaEfectos() {
        service.reconcile("PAY-BX17");
        service.reconcile("PAY-BX17");
        service.reconcile("PAY-BX17");

        assertEquals(1, countReversals("PAY-BX17"));
        assertEquals(1, countAudits("PAY-BX17"));
    }

    @Test
    void revisionManualRepetidaNoDuplicaAuditoriaNiCambiaDatos() {
        service.reconcile("PAY-CQ22");
        service.reconcile("PAY-CQ22");

        assertEquals(1, countAudits("PAY-CQ22"));
        assertEquals(0, countReversals("PAY-CQ22"));
        assertEquals("PENDING", statusOf("PAY-CQ22"));
    }

    @Test
    void ax91PendienteQuedaConfirmado() {
        service.reconcile("PAY-AX91");

        assertEquals("CONFIRMED", statusOf("PAY-AX91"));
        assertEquals(0, countReversals("PAY-AX91"));
    }

    @Test
    void dosPeticionesAlMismoTiempoCreanUnSoloReverso() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);

        Callable<Decision> task = () -> {
            start.await();  // los dos hilos esperan aquí...
            return service.reconcile("PAY-BX17");
        };

        Future<Decision> first = pool.submit(task);
        Future<Decision> second = pool.submit(task);
        start.countDown();  // ...y arrancan al mismo tiempo

        first.get();
        second.get();
        pool.shutdown();

        assertEquals(1, countReversals("PAY-BX17"));
    }

    @Test
    void pagoInexistenteLanzaError() {
        assertThrows(PaymentNotFoundException.class, () -> service.reconcile("PAY-NOPE"));
    }
}