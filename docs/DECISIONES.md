# Decisiones técnicas

## 1. Ante la duda, revisión manual

**Contexto:** el enunciado pide no adivinar cuando la evidencia es
contradictoria o insuficiente.

**Alternativas:** corregir todo lo posible (crear débitos faltantes, rechazar
pagos declinados) o corregir solo patrones reconocidos con certeza.

**Decisión:** solo se corrigen dos casos: confirmar un pago PENDING con una
única aprobación y débito correcto, y reversar débitos duplicados por el monto
exacto. Todo lo demás va a `MANUAL_REVIEW`.

**Trade-off:** más casos llegan a revisión humana. Es preferible a una
corrección automática equivocada sobre dinero.

**Verificación:** `ReconciliationRulesTest` cubre cada regla.

---

## 2. Concurrencia con `SELECT ... FOR UPDATE`

**Contexto:** dos solicitudes simultáneas podrían ver el mismo débito
duplicado y crear dos reversos.

**Alternativas:**
- **Bloqueo optimista con `row_version`:** solo protege si cada conciliación
  incrementa la versión, incluso cuando solo inserta en el ledger, y obliga a
  reintentar ante conflicto.
- **Bloqueo pesimista:** la segunda solicitud espera a que termine la primera.

**Decisión:** pesimista. La transacción es corta, la contención sobre un mismo
pago es rara y funciona igual en Java y en PL/SQL.

**Riesgo:** si la transacción se alargara, otras operaciones sobre ese pago
esperarían.

**Verificación:** `dosPeticionesAlMismoTiempoCreanUnSoloReverso` lanza dos
hilos a la vez; uno corrige y el otro obtiene `NO_ACTION`.

---

## 3. Idempotencia por estado

**Contexto:** la misma conciliación puede repetirse por error o por reintento.

**Alternativas:** una tabla de claves de idempotencia por solicitud, o
recalcular desde el estado actual.

**Decisión:** por estado. Un débito ya reversado deja de contar como activo,
así que la siguiente ejecución responde `NO_ACTION`. En `MANUAL_REVIEW` se
revisa si ya existe la auditoría antes de insertarla.

**Trade-off:** no distingue entre la misma solicitud repetida y una nueva
sobre un pago ya resuelto; para este caso el efecto correcto es el mismo.

**Verificación:** `repetirLaConciliacionNoDuplicaEfectos` y
`revisionManualRepetidaNoDuplicaAuditoriaNiCambiaDatos`.

---

## 4. El PL/SQL hace su propio COMMIT

**Alternativas:** COMMIT dentro del procedimiento, o dejarlo al llamador.

**Decisión:** dentro, con `ROLLBACK` y `RAISE` ante cualquier error. Soporte
lo ejecuta de forma aislada y así es atómico por sí mismo.

**Riesgo:** no debe llamarse dentro de otra transacción, porque confirmaría
también ese trabajo. Está documentado en el encabezado del script.

---

## Otras decisiones
- **Reglas separadas de la base de datos:** `ReconciliationRules` no toca la BD,
  así se prueba en milisegundos.
- **Índice único en `related_entry_id`:** red de seguridad; validado con ORA-00001.
- **Se conserva el débito más antiguo:** coincide con el log (el 5003 vino del reintento).
- **JdbcTemplate en vez de JPA:** se necesita `FOR UPDATE` explícito y SQL visible.
- **H2 en modo Oracle para tests:** no ejecuta PL/SQL; por eso se validó aparte en Oracle Free 23.
- **API sin IDs internos ni montos:** el detalle queda en `SUPPORT_AUDIT`.