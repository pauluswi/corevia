# T24 Integration Middleware

## 1. Overview

### 1.1 Purpose

The **T24 Integration Middleware** is a production-oriented banking integration showcase designed to demonstrate how a modern middleware service can integrate digital banking channels with **Temenos Transact (formerly T24), targeting the R25 release family**.

The project focuses on enterprise integration patterns commonly required in banking environments:

* API orchestration
* Core-banking integration
* Request/response transformation
* Transaction idempotency
* Resilience and fault handling
* Event-driven integration
* Auditability
* Observability
* API security
* Contract and integration testing

The project does **not** attempt to reproduce the complete Temenos Transact product.

Instead, a **Mock Transact R25 Core** will simulate a limited set of core-banking capabilities and interfaces.

---

## 2. Goals

The project shall demonstrate the ability to design and implement a middleware layer that:

1. Provides clean REST APIs to digital channels.
2. Integrates with a Temenos Transact-like core-banking system.
3. Separates channel-facing APIs from core-banking-specific protocols and data models.
4. Transforms external JSON requests into Transact-compatible requests.
5. Handles transient failures and timeouts safely.
6. Prevents duplicate financial transactions.
7. Publishes business events after successful transactions.
8. Maintains an auditable transaction trail.
9. Provides production-oriented observability.
10. Can be deployed as a containerized application.
11. Can be tested without requiring a licensed Transact environment.
12. Demonstrates architecture decisions suitable for enterprise banking systems.

---

## 3. Non-Goals

The following are explicitly outside the scope of this project:

* Reimplementing Temenos Transact.
* Reproducing the complete T24 data model.
* Implementing every Transact API.
* Building a real banking ledger.
* Connecting to a production Temenos environment.
* Implementing real payment-network connectivity.
* Implementing real customer authentication.
* Implementing regulatory reporting.
* Implementing a complete banking mobile application.
* Supporting real customer financial data.

The Mock Transact Core exists only to demonstrate integration behavior.

---

# 4. Target Architecture

The initial architecture shall follow this model:

```text
                    Digital Channel
                          |
                          | REST / JSON
                          v
              +--------------------------+
              | T24 Integration          |
              | Middleware               |
              |                          |
              | API Layer                |
              | Validation               |
              | Orchestration            |
              | Idempotency              |
              | Mapping                  |
              | Resilience               |
              | Audit                    |
              +------------+-------------+
                           |
                      T24 Adapter
                           |
                           v
              +--------------------------+
              | Mock Transact R25 Core   |
              |                          |
              | Customer                 |
              | Account                  |
              | Funds Transfer           |
              | Arrangement              |
              +--------------------------+

                           |
                           v

                         Kafka
                           |
             +-------------+-------------+
             |                           |
             v                           v
        Audit/Event                  Notification
```

The middleware shall isolate the external API contract from the Transact-specific integration contract.

---

# 5. Functional Requirements

## FR-001: Customer Inquiry

The middleware shall provide an API for retrieving customer information.

### Endpoint

```http
GET /api/v1/customers/{customerId}
```

### Expected behavior

The middleware shall:

1. Validate the customer identifier.
2. Call the Transact adapter.
3. Retrieve customer information.
4. Transform the Transact response into the middleware API model.
5. Return a standardized JSON response.

---

# FR-002: Account Inquiry

The middleware shall provide an API for retrieving account information.

### Endpoint

```http
GET /api/v1/accounts/{accountId}
```

### Response shall include

* Account ID
* Customer ID
* Currency
* Available balance
* Current balance
* Account status
* Account type

The middleware shall not expose internal Transact-specific fields directly.

---

# FR-003: Funds Transfer

The middleware shall support an internal funds-transfer operation.

### Endpoint

```http
POST /api/v1/transfers
```

### Example request

```json
{
  "sourceAccount": "1000012345",
  "destinationAccount": "2000098765",
  "amount": 1500000,
  "currency": "IDR",
  "reference": "PAYROLL-202609"
}
```

### Functional flow

```text
Client
  |
  v
Middleware
  |
  +-- Validate request
  |
  +-- Validate idempotency
  |
  +-- Retrieve source account
  |
  +-- Retrieve destination account
  |
  +-- Submit transfer
  |
  +-- Store transaction result
  |
  +-- Publish event
  |
  v
Response
```

---

# 6. Idempotency Requirements

Financial transaction APIs shall support idempotency.

## FR-004: Idempotency Key

The client shall provide:

```http
Idempotency-Key: <unique-key>
```

Example:

```http
POST /api/v1/transfers
Idempotency-Key: PAYROLL-202609-000001
```

### Required behavior

If the same request is received again with the same idempotency key:

* The middleware shall not create another financial transaction.
* The original transaction result shall be returned.
* The client shall receive a deterministic response.

### Example

```text
Request #1
    |
    +--> T24
    |
    +--> SUCCESS
    |
    +--> Store result

Request #2
same Idempotency-Key
    |
    +--> Existing transaction found
    |
    +--> Return previous result
    |
    X--> Do NOT call T24 again
```

---

# 7. T24 Integration Requirements

## FR-005: T24 Adapter

The middleware shall integrate with Transact through a dedicated adapter.

The application layer shall not depend directly on Transact-specific implementation details.

### Required abstraction

```java
public interface CoreBankingGateway {

    Customer getCustomer(String customerId);

    Account getAccount(String accountId);

    TransferResult transfer(TransferRequest request);
}
```

The initial implementation shall be:

```text
T24Adapter
```

A mock implementation shall also be available for automated testing.

---

# 8. Request Mapping

## FR-006: Request Transformation

The middleware shall transform channel-facing API models into Transact-specific models.

Example:

```text
External API

TransferRequest
      |
      v
T24RequestMapper
      |
      v
T24TransferRequest
```

The external API shall not expose T24-specific field names.

---

# 9. Response Mapping

## FR-007: Response Transformation

The middleware shall transform Transact responses into standardized API responses.

Example:

```text
T24 Response
      |
      v
T24ResponseMapper
      |
      v
TransferResult
      |
      v
REST Response
```

---

# 10. Error Handling

## FR-008: Standardized Errors

The middleware shall provide a consistent error response.

Example:

```json
{
  "code": "ACCOUNT_NOT_FOUND",
  "message": "Source account was not found",
  "correlationId": "7e0f3a1c..."
}
```

The middleware shall translate core-banking errors into channel-facing error codes.

T24-specific technical details shall not be unnecessarily exposed to clients.

---

# 11. Timeout and Retry

## FR-009: Timeout

Calls to Transact shall have configurable timeouts.

The system shall distinguish between:

* Connection timeout
* Read timeout
* Business rejection
* Technical failure

---

## FR-010: Retry

The middleware may retry transient technical failures.

Retries shall NOT blindly repeat financial transactions.

For financial operations, retry behavior shall be protected by idempotency and transaction-state verification.

Example:

```text
T24 request
    |
    v
Timeout
    |
    +--> Unknown transaction state
    |
    +--> DO NOT blindly retry
    |
    +--> Query transaction status
```

---

# 12. Resilience

## FR-011: Circuit Breaker

The T24 integration shall support circuit-breaker behavior.

Example states:

```text
CLOSED
   |
   | repeated failures
   v
OPEN
   |
   | recovery period
   v
HALF_OPEN
   |
   | successful request
   v
CLOSED
```

The circuit-breaker configuration shall be externalized.

---

# 13. Transaction State

## FR-012: Transaction Lifecycle

Transfers shall maintain an explicit lifecycle.

Initial states:

```text
RECEIVED
VALIDATING
SUBMITTED
SUCCESS
FAILED
UNKNOWN
```

`UNKNOWN` is important for situations where the middleware cannot determine whether the core-banking transaction completed.

Example:

```text
Middleware
    |
    | submit transfer
    v
T24
    |
    X network timeout
    |
    v
Middleware = UNKNOWN
```

The middleware shall not automatically assume `FAILED`.

---

# 14. Event-Driven Integration

## FR-013: Transaction Events

Successful transactions shall generate an event.

Example:

```text
TransferCompleted
```

Example event:

```json
{
  "eventType": "TransferCompleted",
  "transactionId": "TX-20260919-000001",
  "sourceAccount": "1000012345",
  "destinationAccount": "2000098765",
  "amount": 1500000,
  "currency": "IDR",
  "timestamp": "2026-09-19T09:30:00Z"
}
```

Events shall be published through Kafka.

---

# 15. Audit

## FR-014: Audit Trail

The middleware shall maintain an audit record for significant operations.

At minimum:

* Correlation ID
* Transaction ID
* Operation
* Timestamp
* Request status
* Response status
* Error code
* Processing duration

Sensitive information shall not be written to logs unnecessarily.

---

# 16. Correlation ID

## FR-015: Request Correlation

Every request shall have a correlation ID.

If supplied by the client:

```http
X-Correlation-ID: abc-123
```

the middleware shall preserve it.

If absent, the middleware shall generate one.

The correlation ID shall appear in:

* Application logs
* Error responses
* Audit records
* Distributed traces where applicable

---

# 17. Observability

## FR-016: Metrics

The middleware shall expose application metrics.

Minimum metrics:

```text
http_requests_total
http_request_duration
t24_requests_total
t24_request_duration
t24_errors_total
transfer_success_total
transfer_failed_total
transfer_unknown_total
```

---

## FR-017: Health Checks

The application shall provide health endpoints.

Example:

```http
GET /actuator/health
```

The health model shall distinguish:

* Application availability
* Database availability
* Kafka availability
* T24 availability

---

# 18. Security Requirements

## FR-018: API Authentication

The API shall support authentication.

For the showcase implementation, authentication may use JWT.

The architecture shall allow replacement with an enterprise identity provider.

---

## FR-019: Sensitive Data

The system shall not log:

* Authentication tokens
* Passwords
* Full sensitive customer information
* Sensitive financial credentials

---

# 19. Data Persistence

The middleware shall use PostgreSQL for operational data.

Minimum entities:

```text
CustomerReference
AccountReference
Transfer
IdempotencyRecord
AuditRecord
```

The database shall not attempt to become a replacement for the T24 core ledger.

The core-banking system remains the authoritative source for core account and transaction data.

---

# 20. Mock Transact Core

## FR-020: Mock T24

A dedicated Mock Transact service shall simulate selected T24 capabilities.

It shall support:

```text
Customer Inquiry
Account Inquiry
Funds Transfer
Transaction Status Inquiry
```

The mock shall simulate:

* Successful responses
* Business rejection
* Account not found
* Insufficient funds
* Timeout
* Slow response
* HTTP/technical errors

This allows resilience and failure scenarios to be demonstrated without a licensed Transact installation.

---

# 21. API Requirements

The middleware API shall be documented using OpenAPI.

Initial API:

```text
GET  /api/v1/customers/{customerId}

GET  /api/v1/accounts/{accountId}

POST /api/v1/transfers

GET  /api/v1/transfers/{transactionId}

GET  /api/v1/health
```

API versioning shall use:

```text
/api/v1/...
```

---

# 22. Non-Functional Requirements

## NFR-001: Performance

The middleware should support at least:

* 100 requests/second in local benchmark scenarios
* P95 latency target below 500 ms for account inquiry excluding simulated core delays

Performance results shall be documented rather than presented as production capacity.

---

## NFR-002: Reliability

The system shall demonstrate controlled behavior during:

* T24 timeout
* T24 unavailable
* Kafka unavailable
* Database failure
* Duplicate requests
* Network interruption

---

## NFR-003: Scalability

The middleware shall be stateless where practical so that multiple instances can run concurrently.

```text
              Load Balancer
                    |
          +---------+---------+
          |         |         |
          v         v         v
       MW #1     MW #2     MW #3
          |         |         |
          +---------+---------+
                    |
             Shared Services
```

---

## NFR-004: Configuration

Environment-specific configuration shall not be hardcoded.

Examples:

```text
T24_BASE_URL
T24_CONNECT_TIMEOUT
T24_READ_TIMEOUT
KAFKA_BOOTSTRAP_SERVERS
DATABASE_URL
JWT_ISSUER
```

---

# 23. Testing Requirements

## Test Categories

The project shall include:

### Unit Tests

For:

* Business logic
* Mappers
* Validators
* Error mapping
* Idempotency logic

### Integration Tests

For:

* PostgreSQL
* Kafka
* Mock T24

### Contract Tests

The middleware and Mock T24 shall validate their integration contract.

### Resilience Tests

Tests shall simulate:

* Timeout
* Slow response
* Connection failure
* Duplicate transaction
* Unknown transaction state

---

# 24. Architecture Requirements

The project shall document architectural decisions using ADRs.

Initial ADRs:

```text
ADR-001: Use Hexagonal Architecture
ADR-002: Use Adapter Pattern for T24 Integration
ADR-003: Use Idempotency for Financial Transactions
ADR-004: Use Kafka for Domain Events
ADR-005: Keep T24 as Core-System-of-Record
ADR-006: Handle Unknown Transaction State Explicitly
```

Architecture diagrams shall follow the C4 model:

```text
Context
Container
Component
```

Sequence diagrams shall document important flows.

Minimum sequence diagrams:

```text
Transfer Success
Transfer Timeout
Duplicate Transfer
T24 Unavailable
```

---

# 25. Deployment Requirements

The application shall initially support:

```text
Docker Compose
```

with:

```text
Middleware
Mock T24
PostgreSQL
Kafka
```

Optional future deployment:

```text
Kubernetes
AWS EKS
```

---

# 26. Demo Scenarios

The final project shall provide executable demonstrations for at least the following scenarios.

## Scenario 1 — Account Inquiry

```text
Client
  -> Middleware
  -> T24
  -> Middleware
  -> Client
```

Expected result:

```text
HTTP 200
```

---

## Scenario 2 — Successful Transfer

```text
Client
  -> Middleware
  -> T24
  -> Kafka
  -> Client
```

Expected result:

```text
SUCCESS
```

---

## Scenario 3 — Duplicate Transfer

Two requests use the same:

```text
Idempotency-Key
```

Expected behavior:

```text
Only one transaction is submitted to T24.
```

---

## Scenario 4 — T24 Timeout

Expected behavior:

```text
T24 timeout
    ↓
Transaction = UNKNOWN
    ↓
No blind retry
    ↓
Client receives controlled response
```

---

## Scenario 5 — T24 Unavailable

Expected behavior:

```text
T24 unavailable
    ↓
Circuit breaker opens
    ↓
Subsequent requests fail fast
```

---

## Scenario 6 — Successful Recovery

After T24 becomes available:

```text
OPEN
  ↓
HALF_OPEN
  ↓
successful request
  ↓
CLOSED
```

---

# 27. Definition of Done

The project shall be considered complete when:

* [ ] Middleware starts successfully.
* [ ] Mock Transact starts successfully.
* [ ] PostgreSQL integration works.
* [ ] Kafka integration works.
* [ ] Customer inquiry works.
* [ ] Account inquiry works.
* [ ] Funds transfer works.
* [ ] Idempotency works.
* [ ] Transaction status works.
* [ ] T24 errors are mapped.
* [ ] Timeout handling works.
* [ ] Circuit breaker works.
* [ ] Unknown transaction state is handled.
* [ ] Transfer events are published.
* [ ] Audit records are stored.
* [ ] Correlation IDs are supported.
* [ ] Metrics are available.
* [ ] Health checks are available.
* [ ] OpenAPI documentation is available.
* [ ] Unit tests are implemented.
* [ ] Integration tests are implemented.
* [ ] Docker Compose environment works.
* [ ] C4 diagrams are documented.
* [ ] ADRs are documented.
* [ ] README contains architecture and demo instructions.
* [ ] Failure scenarios can be demonstrated locally.

---

# 28. Portfolio Positioning

This project is intended to demonstrate experience in:

```text
Banking Integration
        +
Temenos T24 / Transact
        +
Java / Spring Boot
        +
Enterprise Middleware
        +
Distributed Systems
        +
Event-Driven Architecture
        +
Payment-Grade Reliability
        +
Cloud-Native Architecture
```

The project should be presented as an **architecture and integration showcase**, not as a replacement for the Temenos Transact platform.
