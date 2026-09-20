# Corevia — Data Model

## 1. Purpose

This document defines the Corevia persistence model, database ownership boundaries, relationships, indexes, constraints, and data lifecycle.

Corevia uses PostgreSQL for operational middleware data.

The database is **not** intended to replace the T24 core banking database or create a competing financial ledger.

The fundamental ownership principle is:

> **T24 owns financial truth; Corevia owns integration and transaction-processing state.**

---

# 2. Data Ownership

Corevia and T24 have clearly separated responsibilities.

| Data                          | System of Record |
| ----------------------------- | ---------------- |
| Customer master               | T24              |
| Account master                | T24              |
| Account balance               | T24              |
| Core financial transaction    | T24              |
| Corevia transaction lifecycle | Corevia          |
| Idempotency records           | Corevia          |
| Request fingerprint           | Corevia          |
| Correlation metadata          | Corevia          |
| T24 integration metadata      | Corevia          |
| Outbox events                 | Corevia          |
| Operational audit             | Corevia          |

Conceptually:

```text id="8bq2p7"
                 T24
                  |
        +---------+---------+
        |                   |
        v                   v
    Customer             Account
    master               master
        |                   |
        |                   |
        +--------+----------+
                 |
                 v
          Financial Truth


              Corevia
                 |
      +----------+-----------+
      |          |            |
      v          v            v
 Transaction  Idempotency   Outbox
 State        Records       Events
      |
      v
 Integration Metadata
```

Corevia may cache or reference T24 data for operational purposes, but it must not silently become authoritative for that data.

---

# 3. Database Technology

Initial database:

```text id="y4m8z1"
PostgreSQL
```

Recommended characteristics:

* PostgreSQL 16+;
* UTF-8;
* UTC timestamps;
* transactional consistency;
* JSONB where flexible integration payloads are required;
* indexed transaction identifiers;
* database-level uniqueness constraints;
* foreign keys where appropriate.

The application should access PostgreSQL through the application/infrastructure boundary rather than allowing controllers to directly manipulate database entities.

---

# 4. Core Tables

The initial Corevia database contains:

```text id="r7x2m4"
transaction
idempotency_record
outbox_event
audit_record
```

Optional supporting tables may later include:

```text id="q5k9n3"
integration_attempt
reconciliation_record
processed_event
```

The initial implementation should avoid unnecessary tables until there is a concrete use case.

---

# 5. Entity Relationship Overview

Conceptually:

```text id="w8p3k6"
+-----------------------+
| idempotency_record    |
+-----------+-----------+
            |
            | transaction_id
            v
+-----------------------+
| transaction           |
+-----------+-----------+
            |
            +-------------------+
            |                   |
            | transaction_id    | transaction_id
            v                   v
+-------------------+   +-------------------+
| outbox_event      |   | audit_record      |
+-------------------+   +-------------------+
```

The `transaction` is the central Corevia processing record.

---

# 6. Transaction Table

The transaction table represents the lifecycle of a financial operation as observed and coordinated by Corevia.

It does **not** represent the T24 ledger itself.

Recommended conceptual schema:

```sql id="m4v9q2"
CREATE TABLE transaction (
    id BIGSERIAL PRIMARY KEY,
    transaction_id VARCHAR(64) NOT NULL,
    source_account VARCHAR(64) NOT NULL,
    destination_account VARCHAR(64) NOT NULL,
    amount NUMERIC(19, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    reference VARCHAR(140) NOT NULL,
    status VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    correlation_id VARCHAR(100) NOT NULL,
    t24_transaction_id VARCHAR(100),
    error_code VARCHAR(100),
    error_message VARCHAR(500),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    submitted_at TIMESTAMP,
    completed_at TIMESTAMP
);
```

The exact column types may be adjusted during implementation.

---

# 7. Transaction ID

`transaction_id` is Corevia's stable identifier for the financial operation.

Example:

```text id="p7c4m1"
TXN-20260920-000001
```

It should be:

* unique;
* immutable;
* safe to expose through the API;
* independent from the T24 transaction identifier.

Constraint:

```sql id="z3n8q5"
CREATE UNIQUE INDEX ux_transaction_transaction_id
ON transaction(transaction_id);
```

---

# 8. T24 Transaction ID

T24 may return its own transaction identifier.

Example:

```text id="v6m2r8"
Corevia Transaction ID
TXN-20260920-000001

T24 Transaction ID
T24-987654
```

Corevia should store the relationship:

```text id="j1s7k3"
transaction.t24_transaction_id
```

but must not replace the Corevia transaction ID with the T24 identifier.

This allows Corevia to maintain its own stable API and operational identity while retaining the external-system reference.

---

# 9. Account References

Corevia stores account identifiers required to process and trace a transfer:

```text id="c5h9x2"
source_account
destination_account
```

These are references to T24-owned accounts.

Corevia does not maintain an independent account master.

Therefore, Corevia should not maintain:

```text id="e2y7q9"
account_balance
available_balance
ledger_balance
```

as authoritative financial fields.

Those values belong to T24.

---

# 10. Money Representation

Monetary values must use fixed-precision decimal storage.

Recommended:

```sql id="m6v1p8"
amount NUMERIC(19, 4)
```

Java:

```java id="g9r3x5"
BigDecimal
```

Never use:

```java id="e7q2k4"
double
float
```

for financial amounts.

Example:

```text id="t2c8m6"
amount = 1500000.00
currency = IDR
```

The database representation and Java representation must preserve monetary precision.

---

# 11. Currency

Currency should use an ISO 4217-style three-character code.

Example:

```text id="y8n4q1"
IDR
USD
EUR
SGD
```

Recommended constraint:

```sql id="h3m7w5"
CHECK (char_length(currency) = 3)
```

The application domain should additionally validate supported currencies.

---

# 12. Transaction Status

The transaction table uses:

```text id="k9q2r4"
RECEIVED
VALIDATING
SUBMITTED
SUCCESS
FAILED
UNKNOWN
```

The valid lifecycle is:

```text id="x6p1m8"
RECEIVED
   |
   v
VALIDATING
   |
   v
SUBMITTED
   |
   +------> SUCCESS
   |
   +------> FAILED
   |
   +------> UNKNOWN
                |
                +------> SUCCESS
                |
                +------> FAILED
```

Terminal states:

```text id="n5v7c3"
SUCCESS
FAILED
```

`UNKNOWN` is unresolved rather than terminal.

---

# 13. State Transition Integrity

Application logic must prevent invalid transitions.

For example:

```text id="f8q2y6"
SUCCESS -> SUBMITTED
```

must not be allowed.

Likewise:

```text id="s4m9k1"
FAILED -> SUCCESS
```

should not occur as a normal transaction transition.

State transition rules should be implemented in the domain/application layer rather than relying solely on controllers.

---

# 14. Idempotency Record

The idempotency table associates a client request with a Corevia transaction.

Recommended schema:

```sql id="q7m3v9"
CREATE TABLE idempotency_record (
    id BIGSERIAL PRIMARY KEY,
    idempotency_key VARCHAR(255) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    transaction_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP
);
```

Unique constraint:

```sql id="r2x8k4"
CREATE UNIQUE INDEX ux_idempotency_key
ON idempotency_record(idempotency_key);
```

---

# 15. Idempotency Relationship

The relationship is:

```text id="w4p6z2"
Idempotency-Key
      |
      v
Idempotency Record
      |
      | transaction_id
      v
Transaction
```

Example:

```text id="d8m3q7"
ABC-123
   |
   v
TXN-001
   |
   v
UNKNOWN
```

A retry using `ABC-123` must resolve to `TXN-001`.

It must not create:

```text id="x1c5n8"
TXN-002
```

---

# 16. Request Fingerprint

The request fingerprint detects reuse of an idempotency key with different request data.

Example:

```text id="a4r8m2"
Idempotency-Key:
ABC-123

Fingerprint:
SHA-256(canonical request)
```

Recommended storage:

```text id="g7k3v5"
VARCHAR(64)
```

for a SHA-256 hexadecimal representation.

Comparison:

```text id="u5q9n1"
same key
+
same fingerprint
=
duplicate request
```

But:

```text id="z2m6c8"
same key
+
different fingerprint
=
409 Conflict
```

---

# 17. Outbox Event

The outbox table provides reliable coordination between PostgreSQL and Kafka.

Recommended schema:

```sql id="p9w4s7"
CREATE TABLE outbox_event (
    id BIGSERIAL PRIMARY KEY,
    event_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    event_version INTEGER NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    correlation_id VARCHAR(100),
    payload JSONB NOT NULL,
    created_at TIMESTAMP NOT NULL,
    published_at TIMESTAMP,
    retry_count INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(500)
);
```

Unique constraint:

```sql id="c6x2m9"
CREATE UNIQUE INDEX ux_outbox_event_id
ON outbox_event(event_id);
```

---

# 18. Outbox Aggregate

For transfer lifecycle events:

```text id="s7n1q5"
aggregate_type = "TRANSFER"
aggregate_id   = transactionId
```

Example:

```text id="b8m3r6"
aggregate_type = TRANSFER
aggregate_id   = TXN-001
event_type     = TransferSucceeded
```

This allows the outbox to associate events with the business operation that produced them.

---

# 19. Outbox Publication State

The simplest publication model is:

```text id="j3v8k2"
published_at IS NULL
```

means:

```text id="c4m6p9"
not yet confirmed as published
```

while:

```text id="n7x2q5"
published_at IS NOT NULL
```

means:

```text id="y5r1m8"
publication completed
```

The publisher can query:

```sql id="a9k4v7"
SELECT *
FROM outbox_event
WHERE published_at IS NULL
ORDER BY created_at;
```

---

# 20. Audit Record

Corevia should maintain operational audit information separately from the transaction table.

Example:

```sql id="f3m8q1"
CREATE TABLE audit_record (
    id BIGSERIAL PRIMARY KEY,
    transaction_id VARCHAR(64),
    correlation_id VARCHAR(100),
    event_type VARCHAR(100) NOT NULL,
    actor VARCHAR(100),
    details JSONB,
    created_at TIMESTAMP NOT NULL
);
```

Potential audit events:

```text id="r6p2x8"
TRANSFER_CREATED
TRANSFER_VALIDATED
TRANSFER_SUBMITTED
TRANSFER_SUCCEEDED
TRANSFER_FAILED
TRANSFER_UNKNOWN
STATUS_INQUIRY
RECONCILIATION_ATTEMPT
```

The audit model should be aligned with the project's security and compliance requirements.

---

# 21. Audit vs Transaction

These serve different purposes.

### Transaction

Answers:

> What is the current state of this financial operation?

Example:

```text id="s8m4k2"
TXN-001 = SUCCESS
```

### Audit

Answers:

> What happened during the processing lifecycle?

Example:

```text id="j5v9q3"
10:00:01 RECEIVED
10:00:01 VALIDATING
10:00:02 SUBMITTED
10:00:04 SUCCESS
```

Therefore:

```text id="w3c7n6"
Transaction = current state
Audit       = historical trail
```

---

# 22. Optional Integration Attempt Table

If detailed T24 communication history is required, Corevia may introduce:

```sql id="q8r5m1"
CREATE TABLE integration_attempt (
    id BIGSERIAL PRIMARY KEY,
    transaction_id VARCHAR(64) NOT NULL,
    dependency VARCHAR(100) NOT NULL,
    operation VARCHAR(100) NOT NULL,
    attempt_number INTEGER NOT NULL,
    started_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    outcome VARCHAR(50),
    error_code VARCHAR(100),
    latency_ms BIGINT
);
```

This can provide detailed operational visibility without polluting the main transaction table.

Example:

```text id="n6k2x4"
TXN-001
 |
 +-- T24 TRANSFER attempt 1 -> TIMEOUT
 |
 +-- T24 STATUS attempt 1  -> SUCCESS
```

This table is optional for the initial POC.

---

# 23. Optional Reconciliation Table

For a more production-oriented implementation, unresolved transactions can be tracked separately.

```sql id="v1m7q9"
CREATE TABLE reconciliation_record (
    id BIGSERIAL PRIMARY KEY,
    transaction_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    last_checked_at TIMESTAMP,
    next_check_at TIMESTAMP,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    resolution VARCHAR(32),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
```

Example:

```text id="p4c8y2"
TXN-001
Status = OPEN
Attempt = 3
Resolution = NULL
```

After successful resolution:

```text id="k7r3m5"
TXN-001
Status = RESOLVED
Resolution = SUCCESS
```

---

# 24. Processed Event Table

Consumers that need idempotent event processing can use:

```sql id="m9x4q7"
CREATE TABLE processed_event (
    event_id VARCHAR(100) PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    processed_at TIMESTAMP NOT NULL
);
```

This is primarily relevant to services consuming Corevia Kafka events.

It does not necessarily belong in Corevia's main transaction database if consumers are deployed as separate services.

---

# 25. Index Strategy

Indexes should support the most important operational queries.

Recommended:

```sql id="z5k1r8"
CREATE UNIQUE INDEX ux_transaction_id
ON transaction(transaction_id);

CREATE INDEX ix_transaction_status
ON transaction(status);

CREATE INDEX ix_transaction_created_at
ON transaction(created_at);

CREATE INDEX ix_transaction_correlation_id
ON transaction(correlation_id);

CREATE UNIQUE INDEX ux_idempotency_key
ON idempotency_record(idempotency_key);

CREATE INDEX ix_outbox_unpublished
ON outbox_event(created_at)
WHERE published_at IS NULL;

CREATE INDEX ix_outbox_aggregate
ON outbox_event(aggregate_id);

CREATE INDEX ix_audit_transaction
ON audit_record(transaction_id);
```

Indexes should be validated against actual query patterns after implementation.

---

# 26. Unique Constraints

The database should enforce important invariants.

At minimum:

```text id="e2w6m4"
transaction.transaction_id
        UNIQUE

idempotency_record.idempotency_key
        UNIQUE

outbox_event.event_id
        UNIQUE
```

These constraints provide protection even if application-level concurrency handling fails.

---

# 27. Foreign Keys

A possible relationship is:

```text id="q6m8x1"
idempotency_record.transaction_id
        |
        v
transaction.transaction_id
```

This can be enforced using a foreign key.

However, the exact physical relationship should be evaluated carefully because transaction retention, archival, and operational lifecycle may differ from idempotency retention.

For a portfolio POC, referential integrity is recommended.

---

# 28. UUID vs BIGSERIAL

Internal database primary keys can use:

```text id="y4q8m2"
BIGSERIAL
```

while externally exposed identifiers use:

```text id="c7n3r9"
transactionId
```

This provides a clean separation between:

```text id="a8v5k1"
internal database identity
```

and:

```text id="p2m6x4"
external business identity
```

Alternatively, UUID can be used for primary keys.

The choice should be consistent throughout the implementation.

---

# 29. Timestamps

All timestamps should be stored consistently in UTC.

Recommended:

```text id="n8k3q6"
created_at
updated_at
submitted_at
completed_at
occurred_at
published_at
```

The API may convert timestamps to ISO 8601 format.

Example:

```text id="f5r2m7"
2026-09-20T08:30:12Z
```

Avoid storing local server time.

---

# 30. Soft Delete

Financial transaction records should generally not be physically deleted as part of normal application operations.

Avoid:

```text id="s3m8q2"
DELETE FROM transaction
```

for ordinary business processing.

Transaction and audit retention should be controlled through explicit retention/archival policies.

Idempotency records and operational data may have different retention requirements.

---

# 31. Database Transaction Boundaries

Corevia should use short database transactions.

Example:

```text id="w6p4n9"
BEGIN
 |
 +--> create transaction
 |
 +--> create idempotency record
 |
 +--> create audit record
 |
 +--> create outbox event
 |
COMMIT
```

The application should not hold a PostgreSQL transaction open while waiting for T24.

Avoid:

```text id="r1x7m5"
BEGIN
 |
 +--> database update
 |
 +--> HTTP call to T24
 |
 |    waiting...
 |
 +--> database update
 |
COMMIT
```

Instead:

```text id="g8k2v4"
DB transaction
     |
     +--> persist state
     |
     +--> COMMIT

T24 call

DB transaction
     |
     +--> persist result
     |
     +--> outbox
     |
     +--> COMMIT
```

---

# 32. Transaction and Outbox Atomicity

When Corevia receives a confirmed result from T24, the state transition and corresponding event should be persisted atomically.

Example:

```text id="q4m7z2"
BEGIN
   |
   +--> transaction.status = SUCCESS
   |
   +--> insert TransferSucceeded
   |    into outbox_event
   |
COMMIT
```

This ensures:

```text id="x8n3p5"
SUCCESS state
+
event waiting for publication
```

are persisted together.

---

# 33. UNKNOWN Persistence

When a transfer outcome is uncertain:

```text id="m5q9r1"
BEGIN
   |
   +--> transaction.status = UNKNOWN
   |
   +--> error_code = CORE_BANKING_TIMEOUT
   |
   +--> audit record
   |
   +--> TransferUnknown outbox event
   |
COMMIT
```

The database therefore preserves the fact that:

```text id="c7v2k8"
the request was submitted
but the outcome was not confirmed.
```

This information is essential for subsequent status inquiry and reconciliation.

---

# 34. Example Data

### Transaction

```text id="h9m3x7"
transaction_id      = TXN-001
source_account      = 1000012345
destination_account = 2000098765
amount              = 1500000.00
currency            = IDR
reference           = PAYROLL-202609
status              = SUCCESS
idempotency_key     = ABC-123
correlation_id      = CORR-001
t24_transaction_id  = T24-98765
```

### Idempotency

```text id="w4p8n2"
idempotency_key     = ABC-123
request_fingerprint = 7b7f0c...
transaction_id      = TXN-001
status              = SUCCESS
```

### Outbox

```text id="k6r1m9"
event_id            = evt-002
event_type          = TransferSucceeded
event_version       = 1
aggregate_type      = TRANSFER
aggregate_id        = TXN-001
correlation_id      = CORR-001
published_at        = 2026-09-20T08:30:13Z
```

---

# 35. Example UNKNOWN Data

```text id="p8x4q6"
Transaction
--------------------------------
transaction_id      = TXN-002
status              = UNKNOWN
error_code          = CORE_BANKING_TIMEOUT
t24_transaction_id  = NULL
correlation_id      = CORR-002
```

Outbox:

```text id="v3m7k1"
event_type          = TransferUnknown
aggregate_id        = TXN-002
published_at        = NULL
```

The transaction remains unresolved until status inquiry or reconciliation obtains reliable evidence.

---

# 36. Data Flow

The overall data flow is:

```text id="f7q2n8"
                  Client
                    |
                    v
              Corevia API
                    |
                    v
             Application Layer
                    |
          +---------+---------+
          |                   |
          v                   v
   PostgreSQL              T24 Adapter
          |                   |
          |                   v
          |                  T24
          |
          +--> transaction
          |
          +--> idempotency
          |
          +--> audit
          |
          +--> outbox
                    |
                    v
                  Kafka
```

---

# 37. Persistence Boundary

The persistence layer should not leak database entities into the domain.

Bad:

```java id="k3m7p1"
public TransactionEntity processTransfer(...)
```

Preferred:

```java id="s8q4v2"
public Transaction processTransfer(...)
```

The persistence adapter maps:

```text id="z6n1m5"
Domain
  |
  v
Persistence Port
  |
  v
JPA Entity
  |
  v
PostgreSQL
```

This maintains the Hexagonal Architecture established in `ARC42.md` and the ADRs.

---

# 38. Domain Model vs Persistence Model

The domain model represents business concepts:

```text id="r4m8q2"
Transaction
Money
CurrencyCode
TransactionStatus
AccountId
TransactionId
```

The persistence model represents database concerns:

```text id="j7p3x9"
TransactionEntity
IdempotencyRecordEntity
OutboxEventEntity
AuditRecordEntity
```

They should not automatically be the same classes.

This prevents database concerns from leaking into the domain.

---

# 39. JSONB Usage

JSONB is appropriate for flexible integration or audit payloads.

Examples:

```text id="u5k2m7"
outbox_event.payload
audit_record.details
```

However, JSONB should not replace relational modeling for fields that Corevia frequently queries.

Good:

```text id="m3q8x1"
payload = event-specific data
```

Less appropriate:

```text id="n6v4r9"
put transaction status inside JSONB
```

if the application frequently queries:

```sql id="b7k1p5"
WHERE status = 'UNKNOWN'
```

Frequently queried operational fields should remain structured columns.

---

# 40. Database Schema Versioning

Schema changes should be managed through database migrations.

Recommended tooling:

```text id="q9m2v6"
Flyway
```

or an equivalent migration framework.

Example:

```text id="c4x7n1"
V1__initial_schema.sql
V2__add_outbox.sql
V3__add_reconciliation.sql
```

Database schema changes should be version-controlled alongside the application.

---

# 41. Migration Principles

Database migrations should be:

* versioned;
* repeatable where appropriate;
* backward-compatible when required;
* reviewed;
* tested;
* executable automatically in deployment pipelines where appropriate.

Avoid manually modifying production schema without a corresponding migration.

---

# 42. Data Retention

Different data types may have different retention policies.

| Data                 | Retention Consideration                   |
| -------------------- | ----------------------------------------- |
| Transaction          | Long-term / regulatory requirements       |
| Audit                | Long-term / regulatory requirements       |
| Idempotency          | Operational retry window                  |
| Outbox               | Until safely published + retention policy |
| Integration attempts | Operational troubleshooting               |
| Reconciliation       | Until resolved + audit requirements       |

Actual retention periods must be defined according to the institution's regulatory and operational requirements.

The portfolio project should document the policy without claiming a specific regulatory retention period unless explicitly required.

---

# 43. Archival

As transaction volume grows, historical data may need archival.

Possible strategy:

```text id="t7m3q8"
Hot PostgreSQL
      |
      | archival process
      v
Historical Storage
```

The application should not depend on indefinitely retaining every operational record in the primary transaction tables.

Archival must preserve auditability and traceability.

---

# 44. Performance Considerations

The main transaction queries are expected to include:

```text id="m2v8k4"
GET transaction by transactionId
GET transaction by idempotency key
find UNKNOWN transactions
find unpublished outbox events
query transactions by creation time
```

Indexes should therefore prioritize:

```text id="x5n1r7"
transaction_id
idempotency_key
status
created_at
outbox published_at
outbox aggregate_id
```

Large historical datasets may eventually require:

* partitioning;
* archival;
* index maintenance;
* batching;
* pagination.

These should be introduced based on measured requirements rather than prematurely.

---

# 45. Pagination

Operational APIs must avoid loading large transaction sets into memory.

Example future endpoint:

```http id="p4k9m2"
GET /api/v1/transfers?status=UNKNOWN&page=0&size=50
```

The persistence layer should use database-level pagination.

Avoid:

```text id="n7x3q8"
SELECT every transaction
then filter in Java
```

---

# 46. Data Consistency Rules

Corevia must preserve the following invariants:

### Transaction ID uniqueness

```text id="f2m8q5"
one transactionId -> one transaction
```

### Idempotency uniqueness

```text id="z7k3n1"
one idempotency key -> one logical transaction
```

### Event uniqueness

```text id="q4v9m6"
one eventId -> one event
```

### T24 reference

If a T24 transaction ID exists:

```text id="s8x2r7"
Corevia transaction
        |
        v
one known T24 transaction reference
```

### Terminal state

```text id="j5m1p9"
SUCCESS / FAILED
```

must not normally transition back into processing states.

---

# 47. Data Integrity and Financial Safety

The database model intentionally does not attempt to implement the banking ledger.

Corevia should not calculate:

```text id="w6q3m8"
new account balance
```

by applying transfer events to a local balance table unless a separate read-model requirement is explicitly introduced.

The safer architecture is:

```text id="c9r2v7"
T24
 |
 +--> authoritative account balance
 |
 +--> authoritative financial transaction
 |
 v
Corevia
 |
 +--> transaction processing state
 +--> integration metadata
 +--> operational audit
```

---

# 48. Security of Stored Data

Sensitive data stored by Corevia should be minimized.

Potential controls include:

* encryption at rest;
* database access control;
* application-level authorization;
* masked account identifiers;
* secret management;
* restricted database credentials;
* audit logging;
* encrypted backups.

Database credentials must never be committed to Git.

Example configuration:

```yaml id="v8m2q4"
spring:
  datasource:
    url: ${DB_URL}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
```

Actual secrets should come from the deployment environment or secret manager.

---

# 49. Backup and Recovery

Production deployment should consider:

```text id="k3x7m1"
PostgreSQL
   |
   +--> backups
   +--> point-in-time recovery
   +--> replication / HA
```

The exact strategy depends on the deployment environment.

Corevia should preserve enough data to recover:

* transaction state;
* idempotency relationships;
* outbox events;
* audit information.

Recovery must not cause already-submitted financial transfers to be blindly replayed.

---

# 50. Recovery Scenario

Suppose Corevia fails after submitting to T24:

```text id="q8n4v6"
Corevia
   |
   v
T24
   |
   | transfer submitted
   |
Corevia crashes
```

After recovery:

```text id="m5r2x9"
TXN-001 = SUBMITTED
```

Corevia must use status inquiry/reconciliation rather than creating a new transaction.

The persisted transaction state is therefore a critical recovery mechanism.

---

# 51. Recommended Initial Schema

For the first implementation, keep the database focused:

```text id="b7q3m8"
transaction
idempotency_record
outbox_event
audit_record
```

Optional later:

```text id="x1v6n4"
integration_attempt
reconciliation_record
```

This provides enough capability to demonstrate:

* transactional processing;
* idempotency;
* UNKNOWN handling;
* reliable events;
* auditability;
* operational observability.

---

# 52. Summary

The Corevia data model deliberately separates **financial truth** from **middleware state**.

```text id="n8k4q2"
                         T24
                          |
             +------------+------------+
             |                         |
             v                         v
       Account Master            Financial Ledger
       Customer Master           Core Transaction
             |                         |
             +------------+------------+
                          |
                          v
                       Corevia
                          |
       +------------------+------------------+
       |                  |                  |
       v                  v                  v
 Transaction        Idempotency          Outbox
 State              Records              Events
       |
       v
     Audit
```

The key principles are:

```text id="p5m7x1"
1. T24 owns customer, account, balance and financial transaction truth.

2. Corevia owns transaction-processing state.

3. Idempotency records prevent duplicate financial requests.

4. The transaction table represents Corevia's view of processing,
   not a replacement for the T24 ledger.

5. The outbox provides reliable database-to-Kafka publication.

6. Audit records preserve the processing history.

7. UNKNOWN is persisted explicitly and survives application restarts.

8. Database constraints enforce critical uniqueness guarantees.

9. Domain objects remain separate from persistence entities.

10. PostgreSQL is an operational coordination store,
    not a competing core banking ledger.
```

This model gives Corevia a realistic banking-integration persistence architecture while keeping the system deliberately smaller than a full core banking platform.
