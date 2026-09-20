# Corevia — Demo Scenarios

## 1. Purpose

This document defines the end-to-end demonstration scenarios for **Corevia**, a banking integration middleware that connects digital channels to a simulated T24 / Temenos Transact core banking system.

The scenarios demonstrate Corevia's most important architectural and banking capabilities:

* REST API integration
* T24 adapter pattern
* transaction lifecycle management
* idempotency
* safe handling of ambiguous transaction outcomes
* business vs. technical error handling
* Kafka event publishing
* transactional outbox
* security
* resilience and circuit breaking
* observability
* reconciliation-oriented transaction investigation

> **Important:** Corevia uses a simulated/mock T24 environment for portfolio purposes. It does not claim access to or integration with a licensed Temenos Transact installation.

---

# 2. Demo Environment

The recommended local environment contains:

```text
                         ┌──────────────────────┐
                         │      API Client      │
                         │ curl / Postman       │
                         └──────────┬───────────┘
                                    │
                                    ▼
                         ┌──────────────────────┐
                         │       Corevia        │
                         │   Spring Boot API    │
                         └───────┬───────┬──────┘
                                 │       │
                       ┌─────────┘       └─────────┐
                       ▼                           ▼
              ┌────────────────┐          ┌────────────────┐
              │  PostgreSQL    │          │    Kafka       │
              │ operational    │          │ transaction    │
              │ state/outbox   │          │ events         │
              └────────────────┘          └───────┬────────┘
                                                   │
                                                   ▼
                                           ┌───────────────┐
                                           │   Consumers   │
                                           └───────────────┘

                         ┌──────────────────────┐
                         │      Mock T24        │
                         │ Transact simulation  │
                         └──────────────────────┘

                         ┌──────────────────────┐
                         │ Prometheus / Grafana │
                         │ Observability        │
                         └──────────────────────┘
```

Recommended local components:

| Component     | Purpose                       |
| ------------- | ----------------------------- |
| Corevia       | Main middleware               |
| Mock T24      | Simulated core banking system |
| PostgreSQL    | Operational transaction state |
| Kafka         | Transaction lifecycle events  |
| Prometheus    | Metrics                       |
| Grafana       | Dashboards                    |
| OpenTelemetry | Distributed tracing           |
| Postman/curl  | API demonstration             |

---

# 3. Demo Preparation

Start the local environment:

```bash
docker compose up -d
```

Verify the services:

```bash
docker compose ps
```

Verify Corevia:

```bash
curl http://localhost:8080/api/v1/health
```

Expected response:

```json
{
  "status": "UP"
}
```

Verify Mock T24:

```bash
curl http://localhost:8081/health
```

Expected:

```json
{
  "status": "UP"
}
```

---

# 4. Test Accounts

The Mock T24 environment should contain deterministic accounts.

| Account      | Customer |        Balance | Status  |
| ------------ | -------- | -------------: | ------- |
| `1000012345` | CUST-001 | IDR 10,000,000 | ACTIVE  |
| `1000012346` | CUST-002 |  IDR 1,000,000 | ACTIVE  |
| `2000098765` | CUST-003 |  IDR 5,000,000 | ACTIVE  |
| `2000098766` | CUST-004 |          IDR 0 | BLOCKED |

These accounts make the demo reproducible.

---

# 5. Scenario 1 — Successful Transfer

## Objective

Demonstrate the normal end-to-end transaction flow.

```text
Client
  │
  │ POST /transfers
  ▼
Corevia
  │
  ├── validate request
  ├── create transaction
  ├── persist state
  │
  ▼
T24 Adapter
  │
  ▼
Mock T24
  │
  │ SUCCESS
  ▼
Corevia
  │
  ├── SUCCESS
  ├── create event
  └── publish/outbox
  │
  ▼
Kafka
```

### Request

```bash
curl -X POST http://localhost:8080/api/v1/transfers \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -H "Idempotency-Key: demo-success-001" \
  -H "X-Correlation-Id: demo-success-001" \
  -d '{
    "sourceAccount": "1000012345",
    "destinationAccount": "2000098765",
    "amount": 1500000,
    "currency": "IDR",
    "reference": "DEMO-SUCCESS-001"
  }'
```

### Expected response

```json
{
  "transactionId": "TXN-...",
  "status": "SUCCESS",
  "sourceAccount": "1000012345",
  "destinationAccount": "2000098765",
  "amount": 1500000,
  "currency": "IDR"
}
```

### Expected lifecycle

```text
RECEIVED
   ↓
VALIDATING
   ↓
SUBMITTED
   ↓
SUCCESS
```

### Expected evidence

Database:

```text
transaction.status = SUCCESS
t24_transaction_id != null
```

Kafka:

```text
TransferSubmitted
TransferSucceeded
```

Metrics:

```text
corevia_transfer_received_total
corevia_transfer_success_total
```

Trace:

```text
HTTP
 └── Corevia service
      └── T24 request
           └── Mock T24
      └── PostgreSQL
      └── Kafka
```

### Interview explanation

> "This demonstrates the normal synchronous transaction path. Corevia owns the middleware transaction lifecycle, while T24 remains the system of record for the actual financial transaction."

---

# 6. Scenario 2 — Insufficient Funds

## Objective

Demonstrate a business failure.

Use an amount larger than the source account balance:

```bash
curl -X POST http://localhost:8080/api/v1/transfers \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -H "Idempotency-Key: demo-insufficient-001" \
  -d '{
    "sourceAccount": "1000012345",
    "destinationAccount": "2000098765",
    "amount": 50000000,
    "currency": "IDR",
    "reference": "DEMO-INSUFFICIENT-001"
  }'
```

Expected result:

```json
{
  "status": "FAILED",
  "errorCode": "INSUFFICIENT_FUNDS"
}
```

Lifecycle:

```text
RECEIVED
   ↓
VALIDATING
   ↓
SUBMITTED
   ↓
FAILED
```

Important distinction:

```text
Business failure
≠
Technical failure
```

The T24 system responded and explicitly rejected the transaction.

### Interview explanation

> "Because T24 definitively rejected the transaction, Corevia can safely classify it as FAILED. This is different from a timeout where we don't know whether T24 processed the transaction."

---

# 7. Scenario 3 — Duplicate Request / Idempotency

## Objective

Demonstrate protection against duplicate financial transactions.

Send the same request twice with the same idempotency key:

```bash
curl -X POST http://localhost:8080/api/v1/transfers \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -H "Idempotency-Key: demo-idempotency-001" \
  -d '{
    "sourceAccount": "1000012345",
    "destinationAccount": "2000098765",
    "amount": 100000,
    "currency": "IDR",
    "reference": "DEMO-IDEMPOTENCY"
  }'
```

Repeat the exact same request.

Expected behavior:

```text
Request 1
   ↓
Create transaction TXN-001
   ↓
Process T24
   ↓
SUCCESS

Request 2
   ↓
Same Idempotency-Key
   ↓
Same request fingerprint
   ↓
Return existing transaction
```

The second request must **not** create another T24 transfer.

### Database evidence

```text
idempotency_key
        │
        ▼
transaction_id
        │
        ▼
TXN-001
```

There should be only one transaction.

### Interview explanation

> "Idempotency is mandatory for financial APIs because clients can retry after network failures. The idempotency key allows Corevia to recognize the same logical operation and prevent duplicate processing."

---

# 8. Scenario 4 — Same Idempotency Key, Different Payload

## Objective

Demonstrate request fingerprint protection.

First request:

```text
Idempotency-Key: demo-conflict-001
Amount: 100000
```

Second request:

```text
Idempotency-Key: demo-conflict-001
Amount: 200000
```

The request must be rejected.

Expected error:

```json
{
  "errorCode": "IDEMPOTENCY_KEY_REUSED",
  "message": "Idempotency key was previously used with a different request."
}
```

Recommended HTTP status:

```text
409 Conflict
```

### Why?

The idempotency key represents one logical operation.

Therefore:

```text
same key
+
same fingerprint
=
same operation
```

but:

```text
same key
+
different fingerprint
=
conflict
```

### Interview explanation

> "I don't only check the idempotency key. I also calculate a canonical request fingerprint. This prevents a client from accidentally reusing the same key for a different financial operation."

---

# 9. Scenario 5 — T24 Timeout → UNKNOWN → SUCCESS

## Objective

Demonstrate the most important banking resilience scenario.

Configure Mock T24 to simulate:

```text
Transfer processing
    ↓
T24 processes transaction
    ↓
response is delayed/lost
    ↓
Corevia timeout
```

The key problem:

```text
Did T24 process the transaction?
        │
        ├── YES
        │
        └── NO
```

Corevia cannot safely assume either answer.

### Request

```bash
curl -X POST http://localhost:8080/api/v1/transfers \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -H "Idempotency-Key: demo-unknown-success-001" \
  -d '{
    "sourceAccount": "1000012345",
    "destinationAccount": "2000098765",
    "amount": 250000,
    "currency": "IDR",
    "reference": "DEMO-UNKNOWN-SUCCESS"
  }'
```

Expected response:

```json
{
  "transactionId": "TXN-...",
  "status": "UNKNOWN"
}
```

Lifecycle:

```text
RECEIVED
   ↓
VALIDATING
   ↓
SUBMITTED
   ↓
UNKNOWN
```

### Critical rule

Do **not** immediately resend the transfer.

```text
UNKNOWN
  │
  └── ❌ blind retry
```

Instead:

```text
UNKNOWN
   │
   ▼
Status Inquiry
   │
   ▼
T24
```

Suppose T24 returns:

```json
{
  "transactionId": "T24-123456",
  "status": "SUCCESS"
}
```

Corevia updates:

```text
UNKNOWN
   ↓
SUCCESS
```

### Expected events

```text
TransferSubmitted
TransferUnknown
TransferSucceeded
```

### Interview explanation

> "This is the key banking scenario. A timeout does not necessarily mean failure. The request may have reached T24 and the response may simply have been lost. Therefore I persist UNKNOWN and resolve it using transaction-status inquiry or reconciliation rather than blindly retrying."

---

# 10. Scenario 6 — T24 Timeout → UNKNOWN → FAILED

This scenario is similar to Scenario 5, but T24 did not complete the transaction.

Flow:

```text
Client
  ↓
Corevia
  ↓
T24
  ↓
response lost
  ↓
UNKNOWN
```

Then:

```text
Status Inquiry
      ↓
T24
      ↓
FAILED
```

Corevia:

```text
UNKNOWN
   ↓
FAILED
```

Expected event sequence:

```text
TransferSubmitted
TransferUnknown
TransferFailed
```

### Interview explanation

> "UNKNOWN is not a final business result. It is a temporary state representing uncertainty about the external side effect. Once the transaction status is known, Corevia transitions to SUCCESS or FAILED."

---

# 11. Scenario 7 — Kafka Outage and Outbox Recovery

## Objective

Demonstrate that Kafka availability does not determine the financial transaction result.

Simulate Kafka failure:

```bash
docker compose stop kafka
```

Perform a successful transfer.

The expected behavior is:

```text
T24
 │
 └── SUCCESS
       ↓
Corevia transaction
       ↓
SUCCESS
       ↓
PostgreSQL
       ↓
Outbox Event
       ↓
Kafka unavailable
```

The transaction should remain:

```text
SUCCESS
```

The event remains unpublished:

```text
outbox_event.published_at = NULL
```

When Kafka becomes available:

```bash
docker compose start kafka
```

The outbox publisher retries.

Expected:

```text
outbox_event.published_at != NULL
```

### Key architectural principle

```text
Financial transaction
        ≠
Event publication
```

Kafka failure must not roll back a completed T24 transaction.

### Interview explanation

> "I use the transactional outbox pattern so the business state and event intent are committed together. Kafka can be temporarily unavailable without losing the event or changing the financial transaction result."

---

# 12. Scenario 8 — Authentication Failure

## Objective

Demonstrate API security.

Call the transfer endpoint without a JWT:

```bash
curl -X POST http://localhost:8080/api/v1/transfers \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-security-001"
```

Expected:

```text
401 Unauthorized
```

No transaction should be created.

Expected database result:

```text
transaction count unchanged
```

---

# 13. Scenario 9 — Authorization Failure

Use a valid token without the required scope.

For example:

```text
Token scopes:
account:read
```

Attempt:

```text
POST /api/v1/transfers
```

Expected:

```text
403 Forbidden
```

Reason:

```text
transfer:write
```

is required.

### Interview explanation

> "Authentication establishes who the caller is. Authorization determines what that caller is allowed to do. I keep those concerns separate."

---

# 14. Scenario 10 — T24 Dependency Degradation

## Objective

Demonstrate circuit breaker and dependency protection.

Configure Mock T24 to return repeated failures:

```text
HTTP 500
HTTP 500
HTTP 500
HTTP 500
...
```

Corevia records:

```text
T24 errors ↑
T24 latency ↑
```

The circuit breaker eventually transitions:

```text
CLOSED
   ↓
OPEN
```

Subsequent requests fail fast rather than continuously overwhelming T24.

After the configured recovery period:

```text
OPEN
  ↓
HALF_OPEN
  ↓
CLOSED
```

if successful calls resume.

### Important distinction

Circuit breaking is a **dependency protection mechanism**.

It does not replace:

* transaction state management
* idempotency
* UNKNOWN handling
* reconciliation

### Interview explanation

> "The circuit breaker protects Corevia and T24 from cascading failures, but I don't use it as a substitute for financial transaction semantics."

---

# 15. Scenario 11 — Account Inquiry

## Objective

Demonstrate a read-only integration.

Request:

```bash
curl \
  -H "Authorization: Bearer <token>" \
  http://localhost:8080/api/v1/accounts/1000012345
```

Expected:

```json
{
  "accountId": "1000012345",
  "customerId": "CUST-001",
  "currency": "IDR",
  "status": "ACTIVE",
  "balance": 10000000
}
```

Flow:

```text
API
 ↓
Corevia
 ↓
CoreBankingGateway
 ↓
T24Adapter
 ↓
T24
```

The balance comes from T24.

Corevia does not maintain an independent authoritative balance.

### Interview explanation

> "For account information, Corevia acts as an integration layer. T24 remains the source of truth for account and balance information."

---

# 16. Demo Scenario Matrix

| #  | Scenario                    | Expected State           | Main Concept         |
| -- | --------------------------- | ------------------------ | -------------------- |
| 1  | Successful transfer         | SUCCESS                  | Normal flow          |
| 2  | Insufficient funds          | FAILED                   | Business error       |
| 3  | Duplicate request           | SUCCESS                  | Idempotency          |
| 4  | Same key, different payload | Conflict                 | Fingerprint          |
| 5  | Timeout → status SUCCESS    | UNKNOWN → SUCCESS        | Ambiguous outcome    |
| 6  | Timeout → status FAILED     | UNKNOWN → FAILED         | Reconciliation       |
| 7  | Kafka outage                | SUCCESS + pending outbox | Transactional outbox |
| 8  | Missing JWT                 | 401                      | Authentication       |
| 9  | Missing scope               | 403                      | Authorization        |
| 10 | T24 degradation             | Circuit OPEN             | Resilience           |
| 11 | Account inquiry             | 200                      | Read integration     |

---

# 17. Observability During the Demo

The demo should not rely only on API responses.

Show three views simultaneously:

```text
┌─────────────────┐
│ Postman / curl  │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Corevia API     │
└────────┬────────┘
         │
    ┌────┴────┐
    ▼         ▼
 Grafana     Logs/Trace
```

For a transfer, show:

### Logs

```text
correlationId=...
transactionId=...
operation=TRANSFER
dependency=T24
outcome=SUCCESS
```

### Metrics

```text
Transfer Success
Transfer Failed
Transfer Unknown

T24 Request Latency
T24 Timeout Count

Outbox Pending
Kafka Consumer Lag
```

### Trace

```text
HTTP POST /transfers
   │
   ├── PostgreSQL
   │
   ├── T24 transfer
   │
   ├── PostgreSQL
   │
   └── Kafka
```

This demonstrates that observability is part of the architecture rather than an afterthought.

---

# 18. Recommended Demo Order

For a **15–20 minute architecture interview**, use this sequence:

### 1. Successful transfer

Show the normal path.

```text
API → Corevia → T24 → SUCCESS
```

### 2. Idempotency

Repeat the request.

```text
Same key → same transaction
```

### 3. Business failure

Show insufficient funds.

```text
T24 rejection → FAILED
```

### 4. Ambiguous timeout

This is the main architecture discussion.

```text
T24 timeout → UNKNOWN
```

Then resolve:

```text
UNKNOWN → SUCCESS
```

### 5. Kafka outage

Show:

```text
SUCCESS
   +
pending outbox
```

Restore Kafka and demonstrate event publication.

### 6. Security

Show:

```text
401
403
```

### 7. Observability

Finish with:

```text
Grafana
Logs
Trace
PostgreSQL
Kafka
```

---

# 19. Five-Minute Short Demo

If the interviewer only gives five minutes, demonstrate these four scenarios:

```text
1. SUCCESS
      ↓
2. Duplicate request
      ↓
3. T24 timeout → UNKNOWN
      ↓
4. UNKNOWN → SUCCESS
```

The key narrative is:

```text
Normal transaction
      ↓
Duplicate protection
      ↓
Distributed failure
      ↓
Safe recovery
```

This gives a compact demonstration of the most important banking middleware concepts.

---

# 20. Architecture Talking Points

During the demo, emphasize the following decisions.

## T24 remains the system of record

```text
Corevia
   │
   └── integration/orchestration
            │
            ▼
           T24
            │
            └── financial truth
```

Corevia does not become a second core banking ledger.

---

## Idempotency protects against duplicate requests

```text
Client retry
    ↓
same Idempotency-Key
    ↓
same transaction
```

---

## UNKNOWN protects against ambiguous side effects

```text
Timeout
   ↓
Do we know whether T24 processed it?
   ↓
NO
   ↓
UNKNOWN
   ↓
Status inquiry / reconciliation
```

---

## Outbox protects event delivery

```text
Business state
      +
Event intent
      ↓
same DB transaction
      ↓
Outbox
      ↓
Kafka
```

---

## Observability connects the entire transaction

```text
Correlation ID
      │
      ├── API logs
      ├── T24 calls
      ├── PostgreSQL
      ├── Kafka
      └── distributed trace
```

# 21. Final Narrative

A concise way to present Corevia is:

> **"Corevia is a banking integration middleware that sits between digital channels and a core banking system. The interesting part is not simply calling T24. The design focuses on financial transaction safety under distributed-system failures."**

Then demonstrate:

```text
                    ┌───────────────┐
                    │ Digital       │
                    │ Channel       │
                    └───────┬───────┘
                            │
                            ▼
                    ┌───────────────┐
                    │    Corevia    │
                    │               │
                    │ Idempotency   │
                    │ State Machine  │
                    │ Security      │
                    │ Resilience    │
                    │ Observability │
                    └───────┬───────┘
                            │
                            ▼
                    ┌───────────────┐
                    │ T24 /         │
                    │ Transact      │
                    │               │
                    │ System of     │
                    │ Record        │
                    └───────────────┘
```

The strongest demonstration is not merely:

```text
"Request succeeded."
```

It is:

```text
"What happens when the network fails
after the transaction may already have
reached the core banking system?"
```

Corevia answers that problem with:

```text
Idempotency
     +
Explicit UNKNOWN state
     +
Status inquiry
     +
Reconciliation
     +
Transactional outbox
     +
Observability
```

That is the central architectural story of the project.
