package com.pagoclaro.reconciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class SampleDataTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void cargaLosCuatroPagosDeEjemplo() {
        Integer pagos = jdbc.queryForObject("SELECT COUNT(*) FROM PAYMENT", Integer.class);
        assertEquals(4, pagos);
    }

    @Test
    void bx17TieneElDebitoNetoDuplicado() {
        BigDecimal neto = jdbc.queryForObject("""
                SELECT SUM(CASE WHEN l.entry_type = 'DEBIT' THEN l.amount ELSE -l.amount END)
                FROM LEDGER_ENTRY l
                JOIN PAYMENT p ON p.id = l.payment_id
                WHERE p.external_ref = 'PAY-BX17'
                """, BigDecimal.class);

        assertEquals(0, new BigDecimal("178000").compareTo(neto));
    }
}