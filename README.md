# PagoClaro – Conciliación segura de pagos (Incidente N3)

Operación de soporte N3 para conciliar un pago por su referencia externa.
Corrige solo los casos que puede decidir con seguridad y envía a revisión
manual todo lo demás. Repetir la operación no duplica efectos.

## Requisitos
- Java 17 o superior
- Docker (opcional, solo para validar los scripts de Oracle)

## Cómo ejecutar

```bash
# Windows: .\mvnw.cmd spring-boot:run
./mvnw spring-boot:run
```

Usa H2 en memoria con `MODE=Oracle`. Al arrancar, `schema.sql` crea las
tablas del enunciado y `data.sql` carga los 4 pagos de muestra.

```bash
curl -X POST http://localhost:8080/support/payments/PAY-BX17/reconciliation
```

## Cómo probar

```bash
./mvnw test
```

15 tests:
- **ReconciliationRulesTest (7):** reglas de decisión sin base de datos.
  Los 4 casos de muestra, BX17 ya corregido, aprobado sin débito y débito
  con monto distinto.
- **ReconciliationIntegrationTest (5):** con base de datos. Repetir la
  conciliación 3 veces crea 1 solo reverso y 1 sola auditoría; la revisión
  manual repetida no duplica auditoría; dos peticiones simultáneas crean
  1 solo reverso; AX91 queda CONFIRMED; una referencia inexistente lanza error.
- **SampleDataTest (2)** y **ReconciliationApplicationTests (1).**

## API

`POST /support/payments/{externalRef}/reconciliation` (POST porque puede modificar datos)

| Código | Cuándo |
|---|---|
| 200 | Conciliación ejecutada (ver `result`) |
| 400 | Referencia con formato inválido |
| 404 | El pago no existe |

```json
{
  "paymentRef": "PAY-BX17",
  "result": "RECONCILED",
  "reason": "DUPLICATE_DEBIT_REVERSED",
  "paymentConfirmed": false,
  "reversalsCreated": 1
}
```

`result` puede ser `RECONCILED`, `NO_ACTION` o `MANUAL_REVIEW`. La respuesta
no expone IDs internos ni montos; el detalle queda en `SUPPORT_AUDIT`.

## Estructura

```
src/main/java/com/pagoclaro/reconciliation/
├── api/          Endpoint y respuesta
├── domain/       Reglas de decisión (sin base de datos)
├── persistence/  Acceso a datos con JdbcTemplate y FOR UPDATE
└── service/      Transacción: bloquear → leer → decidir → aplicar → auditar
db/oracle/
├── 01_detect_inconsistent_payments.sql   Consulta de detección (24h)
├── 02_reconcile_payment.sql              Procedimiento PL/SQL
└── 03_indexes.sql                        Índices y red de seguridad
docs/
├── DECISIONES.md
└── NOTA_OPERATIVA.md
```

## Validación en Oracle

Los scripts se ejecutaron en **Oracle Free 23** con Docker:

```powershell
docker run -d --name oracle-pagoclaro -p 1521:1521 -e ORACLE_PASSWORD=oracle `
  -e APP_USER=pagoclaro -e APP_USER_PASSWORD=pagoclaro gvenzl/oracle-free:slim

Get-Content src\main\resources\schema.sql | docker exec -i oracle-pagoclaro sqlplus -s pagoclaro/pagoclaro@FREEPDB1
Get-Content src\main\resources\data.sql   | docker exec -i oracle-pagoclaro sqlplus -s pagoclaro/pagoclaro@FREEPDB1
Get-Content db\oracle\01_detect_inconsistent_payments.sql | docker exec -i oracle-pagoclaro sqlplus -s pagoclaro/pagoclaro@FREEPDB1
Get-Content db\oracle\02_reconcile_payment.sql | docker exec -i oracle-pagoclaro sqlplus -s pagoclaro/pagoclaro@FREEPDB1
Get-Content db\oracle\03_indexes.sql | docker exec -i oracle-pagoclaro sqlplus -s pagoclaro/pagoclaro@FREEPDB1
```

Resultados:
- **Detección:** AX91 (`STATUS_MISMATCH`), BX17 (`NET_DEBIT_MISMATCH`, 178000 vs 89000)
  y CQ22 (`CONTRADICTORY_PROVIDER_EVENTS`). DK44 no aparece.
- **Procedimiento:** BX17 `RECONCILED` la primera vez y `NO_ACTION` la segunda;
  CQ22 `MANUAL_REVIEW`; AX91 `RECONCILED`; referencia inexistente `NOT_FOUND`.
- **Índice único:** un segundo reverso manual del débito 5003 fue rechazado con `ORA-00001`.

## Diagnóstico del incidente

### Hechos observados (logs de PAY-BX17)
- El intento 1 de aprobación hizo timeout a los 1800 ms y se programó un reintento.
- Se recibió el evento EVT-B2 (APPROVED) y luego se creó el débito 5003.
- Después de crear el débito se registró "paymentStatus already CONFIRMED during retry".
- La conciliación detectó débito neto 178000 vs 89000 esperado (exactamente el doble).

### Hipótesis de causa
- El timeout fue del lado nuestro: el proveedor sí aprobó el primer intento.
- El reintento generó un segundo evento APPROVED con otro ID, por eso la
  restricción UNIQUE de `provider_event_id` no lo bloqueó.
- El débito se crea por cada evento aprobado y el estado se valida después.
  Falta garantizar un solo débito por pago.

### Validaciones que haría en producción
- Confirmar con el proveedor si hubo uno o dos cobros reales al cliente.
- Verificar si EVT-B1 y EVT-B2 corresponden a la misma transacción.
- Ejecutar la consulta de detección para encontrar otros pagos afectados.

### Corrección propuesta
- **Inmediata:** esta operación de conciliación.
- **Causa raíz (fuera de alcance):** validar el estado antes de crear el débito.

## Supuestos
1. Un DEBIT siempre corresponde al monto total del pago (no hay pagos parciales).
2. Dos eventos APPROVED del mismo pago son una sola aprobación notificada dos
   veces. **Riesgo:** si el proveedor cobró dos veces, el reverso interno no
   devuelve el dinero al cliente.
3. Al reversar un duplicado se conserva el débito más antiguo (id menor).
4. Todos los montos están en la misma moneda.
5. REJECTED es un estado final: no se cambia automáticamente.
6. El enunciado no indica cómo se generan los IDs; se usan secuencias de Oracle
   (`LEDGER_ENTRY_SEQ` inicia en 6000 para no chocar con los datos de muestra).

## Tiempo y alcance
El límite de 2 horas fue ajustado, pero se entregaron todos los puntos del
criterio de completitud. Lo que no alcancé a profundizar está en *Pendientes*.

## Pendientes
- **Causa raíz:** corregir el flujo de pagos para que no cree débitos duplicados.
- **Proveedor:** confirmar si BX17 tuvo un cobro real duplicado.
- **Seguridad:** el endpoint no tiene autenticación; debe restringirse al rol de soporte N3.
- **Tests de Oracle automáticos:** la validación del PL/SQL fue manual;
  el siguiente paso sería Testcontainers.

## Uso de IA

Usé **Claude (Anthropic)** como asistente durante la prueba: para analizar el
incidente, proponer la estructura por capas y generar borradores de código, SQL
y documentación. No compartí credenciales ni datos sensibles.

### Cómo validé lo que generó
| Parte | Validación |
|---|---|
| Diagnóstico y supuestos | Contrasté cada hecho con el log y la tabla de datos del enunciado |
| Reglas de decisión | 7 tests unitarios con los 4 casos y casos de borde |
| Persistencia, servicio y API | 5 tests de integración y prueba manual con `curl` (`RECONCILED` y luego `NO_ACTION`) |
| SQL y PL/SQL | La IA no podía ejecutarlos; los validé yo en Oracle Free 23 con Docker (resultados arriba) |
| Documentación | La revisé contra el código y los resultados reales |

### Qué ajusté o rechacé
- Pedí que explicara las modificaciones que hace al codigo que mande y en que parte le ajusto
  básico (`Result`, `PaymentInfo`, `LedgerEntry`) y un `for` en lugar de `stream().reduce()`.
- Recorté la documentación de decisiones a lo esencial.
- Rechace mejoras u adaptaciones que no estaban en el documento.
- Cambié solicite que en algunas modificaciones que hiciera comentariara las cosas para poder explicarlas mejor.
- Corregí errores de estructura en mi entorno: clases fuera de su paquete,
  `reset.sql` fuera de `src/test/resources` y el puerto 8080 ocupado.

Escribí y ejecuté el código en mi entorno, y puedo explicar cada regla de
decisión, el bloqueo con `FOR UPDATE`, la idempotencia por estado y el
índice único sobre `related_entry_id`.

## Documentación adicional
- [Decisiones técnicas](docs/DECISIONES.md)
- [Nota operativa para soporte](docs/NOTA_OPERATIVA.md)