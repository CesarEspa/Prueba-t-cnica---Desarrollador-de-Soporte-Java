-- ESTAS SON LAS TABLAS DE EL DOCUMENTO 
CREATE TABLE PAYMENT (
    id            NUMBER PRIMARY KEY,
    external_ref  VARCHAR2(40) UNIQUE NOT NULL,
    status        VARCHAR2(20) NOT NULL,
    amount        NUMBER(18,2) NOT NULL,
    updated_at    TIMESTAMP NOT NULL,
    row_version   NUMBER DEFAULT 0 NOT NULL
);

CREATE TABLE PROVIDER_EVENT (
    id                 NUMBER PRIMARY KEY,
    payment_id         NUMBER NOT NULL REFERENCES PAYMENT(id),
    provider_event_id  VARCHAR2(60) UNIQUE NOT NULL,
    provider_status    VARCHAR2(20) NOT NULL,
    received_at        TIMESTAMP NOT NULL
);

CREATE TABLE LEDGER_ENTRY (
    id                NUMBER PRIMARY KEY,
    payment_id        NUMBER NOT NULL REFERENCES PAYMENT(id),
    entry_type        VARCHAR2(20) NOT NULL,
    amount            NUMBER(18,2) NOT NULL,
    related_entry_id  NUMBER NULL REFERENCES LEDGER_ENTRY(id),
    created_at        TIMESTAMP NOT NULL
);

CREATE TABLE SUPPORT_AUDIT (
    id          NUMBER PRIMARY KEY,
    payment_id  NUMBER NOT NULL REFERENCES PAYMENT(id),
    action      VARCHAR2(40) NOT NULL,
    reason      VARCHAR2(400) NOT NULL,
    created_at  TIMESTAMP NOT NULL
);

-- Secuencias para generar IDs de los registros nuevos que creará la conciliación inicando en
-- 6000 porque los registros de conciliación anteriores ya tienen IDs asignados.
CREATE SEQUENCE LEDGER_ENTRY_SEQ START WITH 6000;
CREATE SEQUENCE SUPPORT_AUDIT_SEQ START WITH 1;