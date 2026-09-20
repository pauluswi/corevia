# Corevia — Idempotency

## 1. Purpose

This document defines how Corevia prevents duplicate processing of financial requests.

Idempotency is critical for banking and payment systems because clients, gateways, mobile applications, or upstream services may retry the same request when:

* a response is lost;
* a network timeout occurs;
* a client crashes;
* an API gateway retries;
* a user submits the same operation twice;
* a downstream system temporarily becomes unavailable.

The fundamental principle is:

> **The same logical financial request must not create multiple financial transactions.**

Corevia therefore uses an explicit **idempotency key** and persists the relationship between the key and the resulting transaction.

---

# 2. The Problem

Consider the following scenario:

```text id="b6g4wy"
Client
  |
  | POST /transfers
  v
Corevia
  |
  v
T24
  |
  | transfer SUCCESS
  |
  X response lost
  |
Client receives timeout
```

The client does not know whether the transfer succeeded.

Without idempotency:

```text id="q4c2q7"
Client
  |
  | retry
  v
Corevia
  |
  v
T24
  |
  v
SECOND TRANSFER
```

The result could be:

```text id="i4x8b8"
Transfer #1 = SUCCESS
Transfer #2 = SUCCESS
```

This creates a duplicate financial transaction.

With idempotency:

```text id="g1f5u9"
Request #1
Idempotency-Key = ABC-123
        |
        v
Transaction = TXN-001
        |
        v
UNKNOWN

Request #2
Idempotency-Key = ABC-123
        |
        v
same transaction
        |
        v
TXN-001
```

The second request does not create a new financial operation.

---

# 3. Idempotency Key

The client must provide an idempotency key for operations that can create financial side effects.

Example:

```http id="q0t2h3"
POST /api/v1/transfers
Idempotency-Key: 8c7e9d2a-3f8a-4d7b-ae1f-123456789abc
```

The key represents the **logical request**, not the financial transaction itself.

Important distinction:

| Identifier         | Purpose                                    |
| ------------------ | ------------------------------------------ |
| `Idempotency-Key`  | Identifies the logical client request      |
| `TransactionId`    | Identifies Corevia's financial transaction |
| `CorrelationId`    | Identifies one technical request/trace     |
| T24 Transaction ID | Identifies the transaction in T24          |

These identifiers must not be treated as interchangeable.

---

# 4. Relationship Between Identifiers

Example:

```text id="uh2m5w"
Client Request
      |
      | Idempotency-Key
      | "ABC-123"
      v
Corevia
      |
      | TransactionId
      | "TXN-001"
      v
T24
      |
      | T24 Transaction ID
      | "T24-98765"
```

A single logical request can therefore have several identifiers across system boundaries.

Example persistence:

```text id="3k1u8z"
Idempotency-Key     = ABC-123
TransactionId       = TXN-001
CorrelationId       = CORR-456
T24TransactionId    = T24-98765
```

---

# 5. Idempotency Scope

For Corevia, idempotency is primarily required for **financially state-changing operations**.

### Required

```text id="wkw2jw"
POST /api/v1/transfers
```

### Generally unnecessary

```text id="8y3j5x"
GET /api/v1/customers/{customerId}
GET /api/v1/accounts/{accountId}
GET /api/v1/transfers/{transactionId}
```

GET operations should already be safe and do not create financial side effects.

---

# 6. Idempotency Lifecycle

The high-level flow is:

```text id="m4f0kg"
             Request
                |
                v
       Validate Idempotency-Key
                |
                v
       Check existing key
          /           \
        found        not found
         |              |
         v              v
   evaluate state     create record
         |              |
         |              v
         |          create transaction
         |              |
         |              v
         |             T24
         |              |
         +--------------+
                |
                v
             response
```

The idempotency record must be persisted before the operation can be safely exposed as a reusable request.

---

# 7. First Request

Suppose the client sends:

```http id="m2blj8"
POST /api/v1/transfers
Idempotency-Key: ABC-123
```

Corevia creates:

```text id="yqf5sx"
Idempotency-Key = ABC-123
TransactionId  = TXN-001
Status         = RECEIVED
```

Processing continues:

```text id="2m5x9p"
RECEIVED
   |
   v
VALIDATING
   |
   v
SUBMITTED
   |
   v
SUCCESS
```

The response contains:

```json id="93p6fu"
{
  "transactionId": "TXN-001",
  "status": "SUCCESS"
}
```

---

# 8. Repeated Request After Success

The client sends the same request again:

```http id="f9v5kq"
POST /api/v1/transfers
Idempotency-Key: ABC-123
```

Corevia finds:

```text id="5h5npg"
ABC-123 -> TXN-001 -> SUCCESS
```

It must **not** call T24 again.

Instead, it returns the existing transaction result.

```text id="7qg0up"
Client
  |
  | ABC-123
  v
Corevia
  |
  | existing key
  v
TXN-001 = SUCCESS
  |
  v
return existing result
```

No second financial transaction is created.

---

# 9. Repeated Request During Processing

Consider:

```text id="d5d8ah"
Request #1
ABC-123
   |
   v
TXN-001
   |
   v
SUBMITTED
```

Before Request #1 finishes, Request #2 arrives:

```text id="r2p1kj"
Request #2
ABC-123
```

Corevia finds that the key is already associated with:

```text id="0v8v1e"
TXN-001
Status = SUBMITTED
```

The second request must not create another transaction.

Depending on the API contract, Corevia can return:

```http id="3v0k3d"
HTTP 202 Accepted
```

with:

```json id="zq2f3a"
{
  "transactionId": "TXN-001",
  "status": "SUBMITTED"
}
```

The client can subsequently query the transaction.

---

# 10. Repeated Request With UNKNOWN

This is one of the most important scenarios.

Original request:

```text id="4h5g8r"
ABC-123
   |
   v
TXN-001
   |
   v
T24 transfer
   |
   X timeout
   |
   v
UNKNOWN
```

Client retries:

```text id="8x9xpk"
ABC-123
```

Corevia finds:

```text id="zj4e6n"
ABC-123 -> TXN-001 -> UNKNOWN
```

Corevia must **not** submit another transfer.

Instead:

```text id="f4k9rx"
ABC-123
   |
   v
TXN-001
   |
   v
UNKNOWN
```

The client is directed to the existing transaction.

The transaction may later become:

```text id="l9j3k2"
UNKNOWN
   |
   +----> SUCCESS
   |
   +----> FAILED
```

---

# 11. Idempotency State Model

The idempotency record follows the transaction lifecycle.

Conceptually:

```text id="z1q5x9"
NEW
 |
 v
PROCESSING
 |
 +------> COMPLETED
 |
 +------> FAILED
 |
 +------> UNKNOWN
```

However, the idempotency record should not be considered the authoritative financial state.

The transaction state remains the authoritative Corevia processing state.

Therefore:

```text id="t5m0j6"
Idempotency Record
       |
       +--> Idempotency-Key
       |
       +--> TransactionId
                |
                v
           Transaction
                |
                v
             Status
```

---

# 12. Request Fingerprint

An idempotency key alone is not sufficient.

A client could accidentally reuse:

```text id="w0d2aq"
Idempotency-Key: ABC-123
```

for a different request.

Example:

First request:

```json id="8s3l8r"
{
  "sourceAccount": "1000012345",
  "destinationAccount": "2000098765",
  "amount": 1500000,
  "currency": "IDR"
}
```

Second request:

```json id="x7k5z1"
{
  "sourceAccount": "1000012345",
  "destinationAccount": "3000099999",
  "amount": 5000000,
  "currency": "IDR"
}
```

Both use:

```text id="1t6c9p"
Idempotency-Key: ABC-123
```

This must be rejected.

Corevia should therefore store a **request fingerprint**.

Example:

```text id="5g7z9q"
Idempotency-Key
Request Fingerprint
TransactionId
Status
CreatedAt
ExpiresAt
```

---

# 13. Request Fingerprint Algorithm

The fingerprint should be generated from the canonicalized request.

Example conceptual representation:

```text id="r5l4vn"
sourceAccount=1000012345
destinationAccount=2000098765
amount=1500000
currency=IDR
reference=PAYROLL-202609
```

Then:

```text id="h0h3o7"
SHA-256(canonicalRequest)
```

produces:

```text id="u8t5z9"
requestFingerprint =
7b7f0c...
```

The exact canonicalization strategy should be deterministic.

Field ordering must not cause different fingerprints for semantically identical requests.

---

# 14. Idempotency-Key Reuse Rules

Corevia should enforce:

```text id="x0w3u8"
Same key + same request
        |
        v
return existing transaction
```

But:

```text id="c5s2t1"
Same key + different request
        |
        v
409 Conflict
```

Example:

```json id="n4u0sq"
{
  "code": "IDEMPOTENCY_KEY_REUSE",
  "message": "The idempotency key has already been used with a different request.",
  "transactionId": "TXN-001"
}
```

---

# 15. Database Model

A dedicated idempotency table is recommended.

Example:

```sql id="s9u1da"
CREATE TABLE idempotency_record (
    id BIGSERIAL PRIMARY KEY,
    idempotency_key VARCHAR(255) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    transaction_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    response_status INTEGER,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP
);
```

A unique constraint is required:

```sql id="9a7v8m"
CREATE UNIQUE INDEX ux_idempotency_key
ON idempotency_record(idempotency_key);
```

This protects against concurrent requests.

---

# 16. Why Database Uniqueness Matters

Application-level checking alone is not sufficient.

Unsafe implementation:

```text id="7j3v0e"
Thread A:
check key -> not found

Thread B:
check key -> not found

Thread A:
create transaction

Thread B:
create transaction
```

Both threads can pass the check.

A database uniqueness constraint provides an additional concurrency guarantee:

```text id="6k2p5q"
Thread A ----+
             |
             v
       UNIQUE constraint
             ^
             |
Thread B ----+
```

Only one idempotency record can successfully claim the key.

---

# 17. Concurrent Requests

Consider two requests arriving almost simultaneously:

```text id="k7n3f5"
Request A                 Request B
    |                         |
    | ABC-123                 | ABC-123
    v                         v
+---------+               +---------+
| Corevia |               | Corevia |
+----+----+               +----+----+
     |                         |
     +-----------+-------------+
                 |
                 v
        PostgreSQL UNIQUE
          idempotency_key
```

One request creates the record.

The other detects the existing key and uses the existing transaction.

This is essential for preventing duplicate transfers under concurrency.

---

# 18. Transaction Creation Ordering

The creation sequence must be designed carefully.

Recommended conceptual flow:

```text id="x8c1g7"
1. Validate request
       |
       v
2. Validate Idempotency-Key
       |
       v
3. Calculate request fingerprint
       |
       v
4. Create idempotency record
       |
       v
5. Create Corevia transaction
       |
       v
6. Submit to T24
       |
       v
7. Update transaction state
       |
       v
8. Store final/uncertain result
       |
       v
9. Return response
```

Steps 4 and 5 must be designed as a consistent database operation.

---

# 19. Do Not Hold a Database Transaction Across T24

Corevia should not keep a PostgreSQL database transaction open while waiting for T24.

Avoid:

```text id="q8c5k1"
BEGIN DB TRANSACTION
   |
   v
Create transaction
   |
   v
Call T24
   |
   | wait 5 seconds
   |
   v
Update transaction
   |
   v
COMMIT
```

This can create:

* long-held database connections;
* lock contention;
* connection pool exhaustion;
* poor scalability.

Instead, use short database transactions around individual state transitions.

---

# 20. Idempotency and T24

Corevia's idempotency protects the middleware boundary.

However, Corevia cannot assume that T24 automatically provides exactly-once semantics.

The architecture therefore distinguishes:

```text id="5q0q7c"
Corevia idempotency
        +
T24 transaction semantics
        +
status inquiry
        +
reconciliation
```

This layered approach is important because exactly-once behavior across independent systems is difficult to guarantee.

---

# 21. UNKNOWN and Idempotency

The most important interaction is:

```text id="m4y6i8"
Idempotency-Key
       |
       v
TransactionId
       |
       v
UNKNOWN
```

If the client retries:

```text id="e5z7x1"
same Idempotency-Key
       |
       v
same TransactionId
       |
       v
UNKNOWN
```

Corevia does not create:

```text id="3w7v2q"
TransactionId = TXN-002
```

for the same logical request.

Instead:

```text id="5n4g0c"
ABC-123
   |
   v
TXN-001
   |
   v
UNKNOWN
```

remains the single transaction.

---

# 22. Status Inquiry

An `UNKNOWN` transaction can be resolved through:

```http id="p3m7q8"
GET /api/v1/transfers/{transactionId}
```

Corevia can use the stored T24 transaction identifier when available:

```text id="v2g8k4"
Corevia Transaction
TXN-001
      |
      v
T24 Transaction
T24-98765
```

Status inquiry:

```text id="q9m5c3"
Corevia
   |
   | status(T24-98765)
   v
T24
```

Possible outcomes:

```text id="k3j8r2"
SUCCESS
FAILED
still UNKNOWN
```

---

# 23. Idempotency and Response Replay

A mature idempotency implementation can store sufficient information to replay the original API response.

Example:

```text id="w6s1y4"
Idempotency Record

Key:
ABC-123

Transaction:
TXN-001

Response Status:
200

Response Body:
{
  "transactionId": "TXN-001",
  "status": "SUCCESS"
}
```

A duplicate request can therefore receive the same logical response without invoking T24.

However, Corevia should avoid unnecessarily storing large response payloads.

For Corevia, storing the transaction reference and reconstructing the response from current transaction state is a reasonable approach.

---

# 24. Idempotency Retention

Idempotency records do not necessarily need to live forever.

Example:

```text id="p8v3y0"
created_at = 2026-09-20 10:00
expires_at = 2026-09-27 10:00
```

The retention period must be based on:

* business requirements;
* client retry behavior;
* transaction lifecycle;
* reconciliation requirements;
* audit requirements;
* regulatory requirements.

Important:

> **Idempotency retention and transaction/audit retention are different concerns.**

Deleting an idempotency record must not delete the financial transaction history.

---

# 25. Expiration

After the idempotency record expires, the same key may become reusable depending on the API contract.

However, reuse must be carefully considered for financial operations.

A safer strategy is to require clients to generate a new idempotency key for a new logical transaction.

Therefore:

```text id="9x5v7k"
Old request
ABC-123
   |
   v
TXN-001
```

A new transfer should use:

```text id="w1h6k8"
XYZ-789
   |
   v
TXN-002
```

rather than reusing `ABC-123`.

---

# 26. API Contract

For transfer creation:

```http id="a6d9k2"
POST /api/v1/transfers
Idempotency-Key: <unique-key>
Content-Type: application/json
```

Example:

```json id="p2g7n4"
{
  "sourceAccount": "1000012345",
  "destinationAccount": "2000098765",
  "amount": 1500000,
  "currency": "IDR",
  "reference": "PAYROLL-202609"
}
```

The API should reject requests without an idempotency key:

```http id="y3k5v8"
HTTP/1.1 400 Bad Request
```

with:

```json id="r1z4m6"
{
  "code": "MISSING_IDEMPOTENCY_KEY",
  "message": "Idempotency-Key is required for transfer requests."
}
```

---

# 27. Duplicate Request Responses

Recommended behavior:

| Existing State                | Duplicate Request Behavior             |
| ----------------------------- | -------------------------------------- |
| `RECEIVED`                    | Return existing transaction/reference  |
| `VALIDATING`                  | Return existing transaction/reference  |
| `SUBMITTED`                   | Return existing transaction/reference  |
| `SUCCESS`                     | Replay existing successful result      |
| `FAILED`                      | Return existing failure                |
| `UNKNOWN`                     | Return existing unresolved transaction |
| Different request fingerprint | `409 Conflict`                         |

The duplicate request must never create a second financial transaction.

---

# 28. Failure During Idempotency Record Creation

Suppose Corevia receives:

```text id="s3q6m8"
ABC-123
```

but PostgreSQL becomes unavailable.

Corevia should not submit the financial transaction to T24 if it cannot safely persist the idempotency state.

Otherwise:

```text id="g7r1v4"
T24 transaction succeeds
        |
        v
Corevia cannot persist idempotency
        |
        v
client retries
        |
        v
potential duplicate
```

Therefore:

> **For state-changing operations, durable idempotency state should be established before submission to the external financial system.**

---

# 29. Failure After Idempotency Record Creation

Consider:

```text id="c8m2y5"
Idempotency record created
        |
        v
Transaction created
        |
        v
Corevia crashes
        |
        X
T24 not called
```

When the client retries:

```text id="v6q4p9"
ABC-123
```

Corevia finds the existing transaction and can determine whether processing needs to continue.

The transaction state machine must therefore support recovery from intermediate states.

---

# 30. Crash Recovery

Example:

```text id="n5x2r7"
TXN-001
Status = SUBMITTED
```

Corevia crashes.

After restart, Corevia must not assume:

```text id="c3j8w1"
SUBMITTED = FAILED
```

Instead, it should use the transaction state and integration metadata to determine the next safe action.

For example:

```text id="p7d4s2"
SUBMITTED
   |
   v
status inquiry
   |
   +---- SUCCESS
   |
   +---- FAILED
   |
   +---- UNKNOWN
```

This avoids creating another transfer simply because Corevia restarted.

---

# 31. Idempotency Service

The application layer can expose an internal port:

```java id="z5v2m8"
public interface IdempotencyStore {

    Optional<IdempotencyRecord> find(String key);

    IdempotencyRecord create(
        String key,
        String requestFingerprint,
        TransactionId transactionId
    );

    void updateStatus(
        String key,
        TransactionStatus status
    );
}
```

The PostgreSQL implementation belongs in infrastructure:

```text id="g4m8q1"
application
    |
    v
IdempotencyStore
    |
    v
PostgresIdempotencyStore
    |
    v
PostgreSQL
```

This preserves the Hexagonal Architecture boundary.

---

# 32. Concurrency Test

Corevia should explicitly test concurrent requests.

Example:

```text id="d7k1v3"
100 concurrent requests
        |
        |
        v
same Idempotency-Key
        |
        v
Corevia
        |
        v
PostgreSQL
```

Expected result:

```text id="x9m4b6"
100 API requests
        |
        v
1 Corevia transaction
        |
        v
1 T24 transfer
```

Not:

```text id="q5z8c2"
100 API requests
        |
        v
100 T24 transfers
```

---

# 33. Testing Matrix

| Scenario                        | Expected Result                       |
| ------------------------------- | ------------------------------------- |
| First request                   | New transaction                       |
| Same request after SUCCESS      | Existing transaction returned         |
| Same request while processing   | Existing transaction returned         |
| Same request after FAILED       | Existing failure returned             |
| Same request after UNKNOWN      | Existing UNKNOWN transaction returned |
| Same key + different payload    | `409 Conflict`                        |
| Missing key                     | `400 Bad Request`                     |
| Concurrent identical requests   | One transaction                       |
| Database failure before T24     | T24 not called                        |
| Corevia crash after submission  | No blind retry                        |
| T24 timeout                     | Transaction may become `UNKNOWN`      |
| Status inquiry resolves SUCCESS | `SUCCESS`                             |
| Status inquiry resolves FAILED  | `FAILED`                              |

---

# 34. Observability

Idempotency events should be observable.

Recommended metrics:

```text id="f5m1r8"
corevia_idempotency_requests_total
corevia_idempotency_replays_total
corevia_idempotency_conflicts_total
corevia_idempotency_unknown_total
corevia_idempotency_creation_errors_total
```

Useful logs:

```json id="k8p3q6"
{
  "event": "idempotency_replay",
  "idempotencyKeyHash": "7b7f0c...",
  "transactionId": "TXN-001",
  "correlationId": "CORR-456",
  "status": "SUCCESS"
}
```

The raw idempotency key does not necessarily need to be logged.

---

# 35. Security Considerations

Idempotency keys should be treated as request metadata, not authentication credentials.

They should not contain:

* account numbers;
* customer information;
* passwords;
* access tokens;
* personally identifiable information.

Prefer:

```text id="h4n7p2"
UUID
```

or another opaque random identifier.

Example:

```text id="m7k3x9"
8c7e9d2a-3f8a-4d7b-ae1f-123456789abc
```

rather than:

```text id="r6v2c1"
USER-08123456789-TRANSFER-1500000
```

---

# 36. What Idempotency Does Not Solve

Idempotency is important, but it does not solve every distributed-system problem.

It does not by itself guarantee:

* exactly-once delivery;
* exactly-once execution in T24;
* successful transaction completion;
* immediate transaction confirmation;
* consistency across all external systems;
* recovery from every integration failure.

Corevia therefore combines idempotency with:

```text id="z3m7q5"
Idempotency
     +
Transaction State Machine
     +
UNKNOWN State
     +
Status Inquiry
     +
Retry Policy
     +
Reconciliation
```

---

# 37. End-to-End Example

### Step 1 — Initial request

```http id="u6r2m9"
POST /api/v1/transfers
Idempotency-Key: ABC-123
```

Corevia creates:

```text id="x8k4p1"
TransactionId = TXN-001
Status = RECEIVED
```

---

### Step 2 — Submit to T24

```text id="w5n7c3"
TXN-001
   |
   v
SUBMITTED
   |
   v
T24
```

---

### Step 3 — Response timeout

```text id="b9q2m4"
T24
 |
 | transaction may have completed
 |
 X timeout
 |
 v
Corevia
```

Corevia sets:

```text id="j6s3v8"
TXN-001 = UNKNOWN
```

---

### Step 4 — Client retries

```http id="n2x5k7"
POST /api/v1/transfers
Idempotency-Key: ABC-123
```

Corevia finds:

```text id="f4p8q1"
ABC-123
   |
   v
TXN-001
   |
   v
UNKNOWN
```

No new T24 transfer is created.

---

### Step 5 — Status inquiry

```text id="y7m1c5"
Corevia
   |
   | transaction status
   v
T24
   |
   v
SUCCESS
```

Corevia updates:

```text id="q3r9v6"
TXN-001 = SUCCESS
```

---

### Step 6 — Client retries again

```text id="p5k8d2"
ABC-123
   |
   v
TXN-001 = SUCCESS
```

Corevia returns the existing successful transaction.

Result:

```text id="x4n7m1"
One logical request
        |
        v
One Corevia transaction
        |
        v
One T24 financial transaction
```

---

# 38. Architectural Principle

Corevia does not attempt to solve distributed transactions by pretending that the entire system is one atomic database transaction.

Instead:

```text id="c7m2v9"
Client
  |
  v
Corevia
  |
  +-- durable idempotency
  |
  +-- transaction state
  |
  +-- audit
  |
  v
T24
  |
  +-- financial system of record
```

Corevia provides **safe coordination around the financial system of record**.

This is more realistic for banking integration architecture than attempting to make PostgreSQL and T24 participate in one distributed ACID transaction.

---

# 39. Summary

Corevia's idempotency model can be summarized as:

```text id="v8m4q2"
                 Idempotency-Key
                        |
                        v
               +----------------+
               | Existing key?  |
               +-------+--------+
                       |
             +---------+---------+
             |                   |
            NO                  YES
             |                   |
             v                   v
       Create transaction   Check fingerprint
             |              /            \
             v           same           different
            T24            |                |
             |             v                v
             v       Return existing     409 Conflict
          result
```

For uncertain financial operations:

```text id="r5c9k1"
Transfer
   |
   v
Timeout
   |
   v
UNKNOWN
   |
   | same Idempotency-Key
   v
same TransactionId
   |
   v
status inquiry
   |
   +---- SUCCESS
   |
   +---- FAILED
```

The central rule is:

> **Idempotency prevents a retry from becoming a second financial transaction.**

Combined with the `UNKNOWN` state and status inquiry mechanism, it gives Corevia a safe strategy for handling one of the most difficult problems in distributed banking integrations: **the response is lost, but the financial operation may already have happened.**
