# Corevia

### Banking Integration Middleware for T24 / Temenos Transact

**Corevia** is a production-oriented banking integration middleware designed to connect digital banking channels with a **T24 / Temenos Transact-style core banking system**.

It demonstrates how a modern Java-based middleware layer can provide secure APIs, transaction orchestration, idempotency, resilience, event publishing, observability, and controlled integration with a core banking system.

> **Portfolio project:** Corevia does not require or include a licensed Temenos Transact environment. The core banking system is represented by a Mock Transact service for development and demonstration purposes.

---

## 1. Why Corevia?

Digital banking applications should not communicate directly with a core banking system for every business operation.

A dedicated integration layer can provide:

* API abstraction
* business orchestration
* protocol and data transformation
* security
* idempotency
* transaction state management
* resilience against core-banking failures
* auditability
* observability
* event-driven integration

Corevia demonstrates these concerns in a realistic banking integration scenario.

```text
┌──────────────────────┐
│   Digital Channels   │
│                      │
│ Mobile / Web / API   │
└──────────┬───────────┘
           │ REST / JSON
           ▼
┌──────────────────────┐
│       Corevia        │
│                      │
│ Banking Middleware   │
│                      │
│ • API                │
│ • Orchestration      │
│ • Idempotency        │
│ • Resilience         │
│ • Audit              │
│ • Events             │
└──────────┬───────────┘
           │
           │ Core Banking Adapter
           ▼
┌──────────────────────┐
│  T24 / Transact      │
│                      │
│ System of Record     │
│                      │
│ Accounts / Balances  │
│ Core Transactions    │
└──────────────────────┘
```

---

# 2. Key Banking Scenario

The primary use case is a **funds transfer**.

A digital channel submits:

```http
POST /api/v1/transfers
Idempotency-Key: 7c8f2d10-...
```

```json
{
  "sourceAccount": "1000012345",
  "destinationAccount": "2000098765",
  "amount": 1500000,
  "currency": "IDR",
  "reference": "PAYROLL-202609"
}
```

Corevia validates the request, creates the middleware transaction state, invokes the core-banking adapter, and returns the appropriate result.

The important part is not the happy path.

Corevia explicitly handles situations where the outcome of a transaction is uncertain.

```text
                Transfer Request
                       │
                       ▼
                 ┌───────────┐
                 │ Corevia   │
                 └─────┬─────┘
                       │
                       ▼
                 ┌───────────┐
                 │ Validate  │
                 └─────┬─────┘
                       │
                       ▼
                 ┌───────────┐
                 │ Idempotency│
                 └─────┬─────┘
                       │
                       ▼
                 ┌───────────┐
                 │ T24 Call  │
                 └─────┬─────┘
                       │
             ┌─────────┼─────────┐
             │         │         │
             ▼         ▼         ▼
          SUCCESS    FAILED    TIMEOUT
                                  │
                                  ▼
                              UNKNOWN
                                  │
                                  ▼
                          Status Inquiry
```

A timeout is **not automatically interpreted as a failed financial transaction**.

This distinction is essential in payment and banking systems.

---

# 3. Core Features

## Banking APIs

* Customer inquiry
* Account inquiry
* Funds transfer
* Transaction status inquiry
* Health endpoint

## Core Banking Integration

* T24 / Temenos Transact adapter
* Request/response mapping
* Core-banking error mapping
* Configurable timeout
* Mock Transact environment

## Transaction Reliability

* Persistent idempotency
* Duplicate request protection
* Explicit `UNKNOWN` transaction state
* Transaction status inquiry
* Controlled retry
* Circuit breaker
* Failure classification

## Event-Driven Integration

Kafka events for important business events such as:

```text
TransferSubmitted
TransferSucceeded
TransferFailed
TransferUnknown
```

## Observability

* Structured logging
* Correlation IDs
* Application metrics
* Health checks
* Prometheus integration
* Optional Grafana dashboards

## Security

* JWT-based API authentication
* Authorization
* Secure configuration
* Secrets externalization
* Service-to-service security considerations

---

# 4. Architecture

Corevia uses **Hexagonal Architecture (Ports & Adapters)**.

```text
                         ┌─────────────────────┐
                         │   REST API          │
                         │   Controllers       │
                         └──────────┬──────────┘
                                    │
                                    ▼
                         ┌─────────────────────┐
                         │ Application Layer   │
                         │                     │
                         │ Use Cases / Services│
                         └──────────┬──────────┘
                                    │
                         ┌──────────▼──────────┐
                         │      Domain         │
                         │                     │
                         │ Customer            │
                         │ Account             │
                         │ Transfer            │
                         │ Transaction         │
                         └──────────┬──────────┘
                                    │
                         ┌──────────▼──────────┐
                         │       Ports         │
                         └───────┬───────┬─────┘
                                 │       │
                  ┌──────────────┘       └──────────────┐
                  ▼                                     ▼
        ┌──────────────────┐                  ┌──────────────────┐
        │ T24 Adapter      │                  │ PostgreSQL       │
        │                  │                  │                  │
        │ Core Banking     │                  │ Operational      │
        │ Integration      │                  │ State            │
        └────────┬─────────┘                  └──────────────────┘
                 │
                 ▼
        ┌──────────────────┐
        │ T24 / Transact   │
        │                  │
        │ System of Record │
        └──────────────────┘
```

The domain and application layers do not depend directly on T24, PostgreSQL, Kafka, or HTTP.

Infrastructure implementations are connected through ports and adapters.

---

# 5. System of Record

Corevia deliberately does **not** become the authoritative ledger.

The responsibility is separated as follows:

| Responsibility               | System         |
| ---------------------------- | -------------- |
| Customer/core account data   | T24 / Transact |
| Account balance              | T24 / Transact |
| Core financial transaction   | T24 / Transact |
| Middleware transaction state | Corevia        |
| Idempotency state            | Corevia        |
| Integration audit            | Corevia        |
| Correlation information      | Corevia        |
| Business events              | Kafka          |

This keeps the middleware operationally useful without creating a competing source of truth for core banking data.

---

# 6. Transaction State Model

Corevia maintains an explicit transaction lifecycle:

```text
RECEIVED
   │
   ▼
VALIDATING
   │
   ▼
SUBMITTED
   │
   ├──────────────► SUCCESS
   │
   ├──────────────► FAILED
   │
   └──────────────► UNKNOWN
                         │
                         ▼
                   STATUS_INQUIRY
                         │
                    ┌────┴────┐
                    ▼         ▼
                 SUCCESS     FAILED
```

### Why `UNKNOWN`?

Consider this scenario:

1. Corevia sends a transfer request to T24.
2. T24 processes the transaction.
3. The network connection fails before Corevia receives the response.
4. Corevia cannot safely determine whether the transaction succeeded.

Treating this as `FAILED` could cause an unsafe retry and potentially create a duplicate financial transaction.

Therefore:

```text
Timeout ≠ Failure
```

The transaction enters `UNKNOWN` and can subsequently be resolved through a transaction-status inquiry.

---

# 7. Idempotency

Financial transaction APIs must protect against duplicate requests.

Corevia requires an `Idempotency-Key` for transfer requests.

Example:

```http
POST /api/v1/transfers
Idempotency-Key: abc-123
```

If the same request is received again:

```text
Request #1
   │
   ▼
Idempotency-Key abc-123
   │
   ▼
Execute transaction
   │
   ▼
SUCCESS
```

A duplicate request:

```text
Request #2
   │
   ▼
Idempotency-Key abc-123
   │
   ▼
Existing transaction found
   │
   ▼
Return previous result
```

No second call to T24 is required.

If the same key is reused with different transaction data, Corevia rejects the request.

---

# 8. Technology Stack

| Area                | Technology                           |
| ------------------- | ------------------------------------ |
| Language            | Java 25                              |
| Framework           | Spring Boot                          |
| Build               | Maven                                |
| API                 | REST / JSON                          |
| API Documentation   | OpenAPI                              |
| Database            | PostgreSQL                           |
| Messaging           | Apache Kafka                         |
| Containerization    | Docker                               |
| Testing             | JUnit 5                              |
| Integration Testing | Testcontainers                       |
| Observability       | Micrometer / Prometheus              |
| Security            | Spring Security / JWT                |
| Core Banking        | T24 / Temenos Transact-style adapter |
| Architecture        | Hexagonal / Ports & Adapters         |
| Deployment          | Docker Compose                       |
| Future Deployment   | Kubernetes / AWS EKS                 |

---

# 9. API Overview

### Customer

```http
GET /api/v1/customers/{customerId}
```

### Account

```http
GET /api/v1/accounts/{accountId}
```

### Transfer

```http
POST /api/v1/transfers
```

### Transaction Status

```http
GET /api/v1/transfers/{transactionId}
```

### Health

```http
GET /api/v1/health
```

Detailed API contracts are documented in:

**[`API-SPEC.md`](API-SPEC.md)**

---

# 10. Repository Structure

```text
corevia/
│
├── README.md
├── REQUIREMENTS.md
├── ARC42.md
│
├── docs/
│   ├── c4/
│   │   └── C4.md
│   │
│   └── adr/
│       ├── ADR-001-hexagonal-architecture.md
│       ├── ADR-002-t24-adapter.md
│       ├── ADR-003-idempotency.md
│       ├── ADR-004-unknown-transaction-state.md
│       ├── ADR-005-kafka-events.md
│       └── ADR-006-t24-as-system-of-record.md
│
└── src/
    ├── main/
    │   └── java/
    │
    └── test/
```

---

# 11. Java Package Structure

```text
com.corevia
│
├── api
│   ├── controller
│   ├── request
│   ├── response
│   └── exception
│
├── application
│   ├── service
│   └── port
│       ├── in
│       └── out
│
├── domain
│   ├── customer
│   ├── account
│   ├── transfer
│   └── transaction
│
├── infrastructure
│   ├── t24
│   │   ├── client
│   │   ├── mapper
│   │   ├── model
│   │   └── adapter
│   │
│   ├── persistence
│   │   ├── postgres
│   │   └── entity
│   │
│   ├── kafka
│   │   ├── producer
│   │   └── event
│   │
│   └── security
│
└── configuration
```

---

# 12. Core Banking Port

The application layer communicates with core banking through an abstraction:

```java
public interface CoreBankingGateway {

    Customer getCustomer(String customerId);

    Account getAccount(String accountId);

    TransferResult transfer(TransferRequest request);

    TransactionStatus getTransactionStatus(
        String transactionId
    );
}
```

The application does not need to know whether the implementation communicates with:

* T24
* another core banking platform
* a mock system
* a test environment

The infrastructure layer provides the appropriate adapter.

---

# 13. Resilience

Corevia is designed around the assumption that external systems can fail.

Failure scenarios include:

* network timeout
* slow T24 response
* HTTP 5xx
* connection failure
* T24 unavailable
* duplicate requests
* partial communication failure
* unknown transaction outcome

The middleware uses:

```text
Timeout
   ↓
Classify failure
   ↓
Is outcome known?
   │
   ├── YES → SUCCESS / FAILED
   │
   └── NO  → UNKNOWN
                 ↓
          Status Inquiry
```

Blindly retrying a financial transaction is intentionally avoided.

---

# 14. Event-Driven Architecture

Corevia publishes business events to Kafka.

Example:

```text
TransferSubmitted
       │
       ▼
   Kafka Topic
       │
       ├────────► Audit Consumer
       │
       ├────────► Notification Consumer
       │
       └────────► Analytics Consumer
```

The application uses an abstraction around event publishing so that the core business logic does not depend directly on Kafka.

A future evolution can introduce the **Transactional Outbox Pattern** to improve consistency between database state and event publication.

---

# 15. Testing Strategy

Corevia uses multiple levels of testing.

### Unit Tests

Test:

* domain logic
* application services
* transaction state transitions
* idempotency rules
* error classification

### Integration Tests

Test:

* PostgreSQL
* Kafka
* REST APIs
* T24 adapter

### Resilience Tests

Simulate:

```text
T24 success
T24 business rejection
T24 HTTP 500
T24 timeout
T24 slow response
T24 unavailable
duplicate request
unknown transaction
```

Testcontainers is used where appropriate to provide realistic infrastructure dependencies.

---

# 16. Demo Scenarios

The project is designed around several interview/demo scenarios.

### Scenario 1 — Successful Transfer

```text
POST /transfers
       ↓
Validation
       ↓
T24
       ↓
SUCCESS
```

### Scenario 2 — Duplicate Request

```text
POST /transfers
Idempotency-Key: ABC

        ↓

Existing transaction found

        ↓

Return previous result
```

### Scenario 3 — T24 Timeout

```text
POST /transfers
       ↓
T24
       ↓
TIMEOUT
       ↓
UNKNOWN
       ↓
GET /transfers/{id}
       ↓
Resolve final state
```

### Scenario 4 — Core Banking Outage

```text
Corevia
   ↓
T24 unavailable
   ↓
Circuit breaker
   ↓
Fast failure
```

### Scenario 5 — Event Publication

```text
Transfer SUCCESS
       ↓
TransferSucceeded
       ↓
Kafka
       ↓
Downstream consumers
```

---

# 17. Architecture Documentation

Detailed architectural decisions are documented separately.

### Architecture

* [`REQUIREMENTS.md`](REQUIREMENTS.md)
* [`ARC42.md`](ARC42.md)
* [`docs/c4/C4.md`](docs/c4/C4.md)

### Architecture Decision Records

* [`ADR-001 — Hexagonal Architecture`](docs/adr/ADR-001-hexagonal-architecture.md)
* [`ADR-002 — T24 Adapter`](docs/adr/ADR-002-t24-adapter.md)
* [`ADR-003 — Idempotency`](docs/adr/ADR-003-idempotency.md)
* [`ADR-004 — Unknown Transaction State`](docs/adr/ADR-004-unknown-transaction-state.md)
* [`ADR-005 — Kafka Events`](docs/adr/ADR-005-kafka-events.md)
* [`ADR-006 — T24 as System of Record`](docs/adr/ADR-006-t24-as-system-of-record.md)

Additional implementation documentation will cover:

* API specification
* domain model
* T24 integration
* error handling
* idempotency
* events
* data model
* security
* testing
* observability
* demo scenarios

---

# 18. Project Status

```text
Architecture
    ████████████████████ 100%

Requirements
    ████████████████████ 100%

Architecture Decisions
    ████████████████████ 100%

API Specification
    ░░░░░░░░░░░░░░░░░░░░   0%

Domain Model
    ░░░░░░░░░░░░░░░░░░░░   0%

Implementation
    ░░░░░░░░░░░░░░░░░░░░   0%

Testing
    ░░░░░░░░░░░░░░░░░░░░   0%
```

The project is being developed incrementally, starting from architecture and contracts before implementation.

---

# 19. Portfolio Objective

Corevia is intended to demonstrate practical experience in:

* Enterprise integration architecture
* Banking middleware
* Core banking integration
* Java / Spring Boot
* REST API design
* Event-driven architecture
* Distributed systems
* Transaction reliability
* Idempotency
* Failure handling
* Observability
* Security
* Cloud-native deployment
* Architecture documentation

The project intentionally focuses on **architecture and reliability concerns found in financial systems**, rather than implementing a complete banking core.

---

# 20. Disclaimer

Corevia is an educational and portfolio project.

It does not contain proprietary Temenos software, production banking data, production credentials, or a licensed Temenos Transact environment.

The T24 / Temenos Transact integration is represented through a mock implementation designed to demonstrate integration architecture, transaction orchestration, failure handling, and resilience patterns.

---

## License

To be defined.
