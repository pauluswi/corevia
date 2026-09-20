# Corevia Demo Setup

This guide explains how to run Corevia locally with a **fast, lightweight demo environment**.

The default demo intentionally mocks most external systems while keeping PostgreSQL real. This allows the important banking middleware behavior—idempotency, transaction state, `UNKNOWN` outcomes, persistence, and auditability—to remain realistic without requiring a full Kafka/T24/observability stack.

---

## 1. Demo Philosophy

Corevia is designed as production-oriented banking middleware, but running every external dependency locally would make the portfolio demo unnecessarily heavy.

The local demo therefore follows this principle:

> **Mock external behavior, not the architecture.**

The application still uses the same ports/interfaces that would be used in production.

```text
Production

Digital Channel
      |
      v
   Corevia
      |
      +---- CoreBankingGateway ----> T24 / Transact
      |
      +---- EventPublisher --------> Kafka
      |
      +---- Security Provider -----> OIDC / OAuth2
      |
      +---- PostgreSQL
```

In Demo Mode:

```text
Digital Channel
      |
      v
   Corevia
      |
      +---- CoreBankingGateway ----> MockT24Gateway
      |                               (in-process)
      |
      +---- EventPublisher --------> InMemoryEventPublisher
      |                               (in-process)
      |
      +---- Local Security ---------> Mock / Dev Security
      |
      +---- PostgreSQL
```

The important architecture does **not** change.

Only the adapters change.

---

# 2. Runtime Modes

Corevia supports three main local/runtime modes.

| Mode         | PostgreSQL | T24             | Kafka          | Auth       | Observability           | Purpose                 |
| ------------ | ---------- | --------------- | -------------- | ---------- | ----------------------- | ----------------------- |
| `demo`       | Real       | In-process mock | In-memory mock | Local mock | Logs + Actuator         | Fast interview/demo     |
| `local`      | Real       | HTTP mock       | Real Kafka     | Local/mock | Basic                   | Integration development |
| `full`       | Real       | HTTP mock       | Real Kafka     | Mock OIDC  | Prometheus/Grafana/OTel | Architecture showcase   |
| `production` | Real       | Real T24        | Real Kafka     | Real OIDC  | Full stack              | Production deployment   |

### Recommended

For an interview or portfolio demonstration, use:

```text
demo
```

It should start in seconds and require only PostgreSQL.

---

# 3. Demo Architecture

The default demo environment contains:

```text
+------------------------------------------------------+
|                    Corevia                           |
|                                                      |
|  REST API                                             |
|       |                                               |
|       v                                               |
|  Application Layer                                    |
|       |                                               |
|       +---- CoreBankingGateway                        |
|       |          |                                    |
|       |          +--> MockT24Gateway                  |
|       |                                               |
|       +---- EventPublisher                            |
|       |          |                                    |
|       |          +--> InMemoryEventPublisher          |
|       |                                               |
|       +---- PostgreSQL                                |
|                                                      |
+------------------------------------------------------+
```

### External processes

Only PostgreSQL runs outside the application:

```text
Docker
└── PostgreSQL
```

Everything else is mocked inside Corevia.

This avoids:

* starting Kafka
* starting Zookeeper/KRaft infrastructure
* starting a separate Mock T24 service
* starting an OIDC server
* starting Prometheus
* starting Grafana
* starting an OpenTelemetry collector
* maintaining multiple Docker containers

---

# 4. Prerequisites

Install:

* Java 25
* Maven 3.9+
* Docker
* Docker Compose
* Git

Verify:

```bash
java -version
mvn -version
docker --version
docker compose version
```

Expected Java version:

```text
Java 25
```

---

# 5. Start PostgreSQL

PostgreSQL remains a real dependency even in Demo Mode.

This is intentional.

Corevia demonstrates important database behavior including:

* unique idempotency constraints
* transaction persistence
* transaction state transitions
* `UNKNOWN` persistence
* request fingerprints
* audit records
* outbox records
* concurrent request protection
* transactional consistency

Start PostgreSQL:

```bash
docker compose up -d postgres
```

Check:

```bash
docker compose ps
```

Expected:

```text
corevia-postgres    running
```

Example configuration:

```text
Host:     localhost
Port:     5432
Database: corevia
Username: corevia
Password: corevia
```

These credentials are for local development only.

---

# 6. Start Corevia in Demo Mode

Run:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo
```

On Windows:

```bash
mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=demo
```

Alternatively:

```bash
java -jar target/corevia.jar --spring.profiles.active=demo
```

Corevia should start on:

```text
http://localhost:8080
```

---

# 7. Demo Components

## 7.1 Mock T24 Gateway

Demo Mode uses:

```text
MockT24Gateway
```

instead of:

```text
T24Adapter -> T24Client -> T24 / Transact
```

The production abstraction remains:

```java
public interface CoreBankingGateway {

    Customer getCustomer(String customerId);

    Account getAccount(String accountId);

    TransferResult transfer(TransferRequest request);

    TransactionStatus getTransactionStatus(String transactionId);
}
```

Profile selection determines the implementation:

```text
demo
 |
 +--> MockT24Gateway

local/full
 |
 +--> T24Adapter -> T24Client -> Mock T24 HTTP service

production
 |
 +--> T24Adapter -> T24Client -> Real T24 / Transact
```

This demonstrates the value of the Ports & Adapters architecture.

---

# 8. Mock T24 Behavior

The in-process Mock T24 simulates common banking outcomes.

Supported scenarios:

| Scenario             | Result                                         |
| -------------------- | ---------------------------------------------- |
| `SUCCESS`            | Transfer succeeds                              |
| `INSUFFICIENT_FUNDS` | Business failure                               |
| `ACCOUNT_NOT_FOUND`  | Business failure                               |
| `ACCOUNT_BLOCKED`    | Business failure                               |
| `HTTP_500`           | Technical failure                              |
| `TIMEOUT`            | Potentially unknown outcome                    |
| `SLOW_RESPONSE`      | Slow dependency                                |
| `CONNECTION_FAILURE` | Technical failure                              |
| `UNKNOWN_OUTCOME`    | T24 processes transaction but response is lost |

The mock is deterministic so the same demo can be reproduced during an interview.

---

# 9. Important: UNKNOWN Outcome Simulation

The most important resilience demonstration is the ambiguous transfer result.

Example:

```text
Corevia
   |
   | transfer
   v
Mock T24
   |
   | transaction actually processed
   |
   X response lost / timeout
```

Corevia cannot safely assume:

```text
FAILED
```

because T24 may already have processed the transfer.

Instead:

```text
SUBMITTED
    |
    | timeout
    v
 UNKNOWN
```

Corevia then performs a transaction status inquiry:

```text
UNKNOWN
   |
   | status inquiry
   v
SUCCESS
```

or:

```text
UNKNOWN
   |
   | status inquiry
   v
FAILED
```

This is the primary distributed-systems safety scenario in the Corevia demo.

---

# 10. Demo Event Publisher

Demo Mode replaces Kafka with:

```text
InMemoryEventPublisher
```

Production:

```text
KafkaEventPublisher
       |
       v
      Kafka
```

Demo:

```text
InMemoryEventPublisher
       |
       v
Application memory
```

The same application-level event abstraction is used.

Example events:

```text
TransferSubmitted
TransferSucceeded
TransferFailed
TransferUnknown
```

The event is still recorded in the PostgreSQL outbox where applicable.

The difference is that Demo Mode does not require a Kafka broker to publish the event.

---

# 11. Important Kafka Limitation in Demo Mode

Because Kafka is mocked in Demo Mode, the following scenarios require `local` or `full` mode:

* Kafka broker outage
* Kafka consumer lag
* Kafka partition behavior
* Kafka retry
* Kafka dead-letter topic
* Kafka consumer failure
* real outbox-to-Kafka delivery
* Kafka ordering behavior

Use:

```text
demo
```

for the application/business flow.

Use:

```text
local/full
```

when demonstrating Kafka integration and failure recovery.

---

# 12. Local Security

Demo Mode uses simplified local security.

The purpose is to demonstrate:

* authentication boundary
* authorization
* scopes
* protected endpoints

without requiring a full identity provider.

Example scopes:

```text
transfer:read
transfer:write
account:read
customer:read
```

Production architecture:

```text
Client
  |
  v
OIDC / OAuth2 Provider
  |
  v
JWT
  |
  v
Corevia
```

Demo architecture:

```text
Demo Client
    |
    v
Local JWT / Mock Security
    |
    v
Corevia
```

Production credentials and secrets must never be reused in Demo Mode.

---

# 13. Database

Corevia uses PostgreSQL for operational middleware state.

It does **not** become a second banking ledger.

```text
T24 owns:

- Customer master
- Account master
- Balance
- Core financial transaction


Corevia owns:

- Middleware transaction lifecycle
- Idempotency
- Request fingerprint
- Correlation metadata
- T24 transaction reference
- Outbox events
- Operational audit
```

This distinction is important for the architecture discussion.

---

# 14. Database Schema

The demo initializes the Corevia schema through Flyway.

Main tables:

```text
transaction
idempotency_record
outbox_event
audit_record
```

Optional tables:

```text
integration_attempt
reconciliation_record
```

Inspect PostgreSQL:

```bash
docker exec -it corevia-postgres psql \
  -U corevia \
  -d corevia
```

List tables:

```sql
\dt
```

Inspect transactions:

```sql
SELECT
    transaction_id,
    source_account,
    destination_account,
    amount,
    currency,
    status,
    t24_transaction_id,
    created_at
FROM transaction
ORDER BY created_at DESC;
```

Inspect idempotency:

```sql
SELECT
    idempotency_key,
    request_fingerprint,
    transaction_id,
    status
FROM idempotency_record;
```

Inspect outbox:

```sql
SELECT
    event_id,
    event_type,
    aggregate_id,
    published_at,
    retry_count
FROM outbox_event
ORDER BY created_at DESC;
```

---

# 15. Health Check

Check:

```http
GET /api/v1/health
```

Example:

```bash
curl http://localhost:8080/api/v1/health
```

Expected:

```json
{
  "status": "UP"
}
```

---

# 16. Successful Transfer Demo

Create a transfer:

```bash
curl -X POST http://localhost:8080/api/v1/transfers \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-transfer-001" \
  -d '{
    "sourceAccount": "1000012345",
    "destinationAccount": "2000098765",
    "amount": 1500000,
    "currency": "IDR",
    "reference": "PAYROLL-202609"
  }'
```

Expected flow:

```text
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

Example response:

```json
{
  "transactionId": "TX-...",
  "status": "SUCCESS"
}
```

---

# 17. Duplicate Request Demo

Send the same request again with:

```text
Idempotency-Key: demo-transfer-001
```

Corevia should identify the existing logical request.

```text
Request 1
   |
   v
transaction TX-001
   |
   v
T24 transfer


Request 2
   |
   v
same Idempotency-Key
   |
   v
existing TX-001
   |
   X
no second T24 transfer
```

This demonstrates protection against duplicate financial operations.

---

# 18. Idempotency Conflict Demo

Use the same idempotency key but change the amount:

```json
{
  "sourceAccount": "1000012345",
  "destinationAccount": "2000098765",
  "amount": 2000000,
  "currency": "IDR",
  "reference": "PAYROLL-202609"
}
```

Expected result:

```text
409 Conflict
```

Reason:

```text
Same Idempotency-Key
+
Different Request Fingerprint
=
Idempotency Conflict
```

---

# 19. T24 Business Failure Demo

Set the mock scenario to:

```text
INSUFFICIENT_FUNDS
```

The Mock T24 gateway returns a business error.

Expected Corevia state:

```text
SUBMITTED
    |
    v
FAILED
```

This is different from a technical timeout.

Example business errors:

```text
INSUFFICIENT_FUNDS
ACCOUNT_NOT_FOUND
ACCOUNT_BLOCKED
TRANSACTION_NOT_ALLOWED
```

---

# 20. T24 Timeout → UNKNOWN Demo

Set the Mock T24 scenario to:

```text
TIMEOUT
```

Submit a transfer.

Expected result:

```text
RECEIVED
    |
    v
VALIDATING
    |
    v
SUBMITTED
    |
    | T24 timeout
    v
UNKNOWN
```

Corevia must **not blindly retry** the transfer.

Query:

```http
GET /api/v1/transfers/{transactionId}
```

Then perform a transaction status inquiry.

The Mock T24 can resolve the transaction to:

```text
SUCCESS
```

or:

```text
FAILED
```

Example:

```text
UNKNOWN
   |
   | status inquiry
   v
SUCCESS
```

This is the most important scenario to explain during the architecture interview.

---

# 21. Account Inquiry Demo

Example:

```bash
curl http://localhost:8080/api/v1/accounts/1000012345
```

Corevia calls:

```text
CoreBankingGateway
        |
        v
MockT24Gateway
```

The response is mapped back into the Corevia domain model.

The T24-specific model does not leak into the API layer.

---

# 22. Customer Inquiry Demo

Example:

```bash
curl http://localhost:8080/api/v1/customers/CUST-001
```

Flow:

```text
REST API
   |
   v
Application Service
   |
   v
CoreBankingGateway
   |
   v
MockT24Gateway
```

---

# 23. Observability in Demo Mode

Demo Mode intentionally keeps observability lightweight.

Available:

```text
Spring Boot Actuator
Structured application logs
Correlation ID
Transaction ID
Idempotency Key
T24 Transaction ID
Basic metrics
```

Optional production-style components are not required.

For example:

```text
Prometheus
Grafana
OpenTelemetry Collector
Jaeger
```

are enabled only in the fuller environments.

This keeps the default demo fast.

---

# 24. Correlation and Transaction IDs

A transfer should be traceable using separate identifiers.

```text
Idempotency-Key
       |
       | identifies logical client request
       v
Transaction-ID
       |
       | identifies Corevia financial operation
       v
T24-Transaction-ID
       |
       | identifies operation in T24
       v
Correlation-ID
       |
       | traces technical flow
       v
logs / traces / integrations
```

Do not treat these identifiers as interchangeable.

---

# 25. Demo Scenario Matrix

| Scenario             |    Demo    |    Local   | Full |
| -------------------- | :--------: | :--------: | :--: |
| Successful transfer  |      ✓     |      ✓     |   ✓  |
| Idempotency          |      ✓     |      ✓     |   ✓  |
| Idempotency conflict |      ✓     |      ✓     |   ✓  |
| Insufficient funds   |      ✓     |      ✓     |   ✓  |
| Account not found    |      ✓     |      ✓     |   ✓  |
| Account blocked      |      ✓     |      ✓     |   ✓  |
| T24 timeout          |      ✓     |      ✓     |   ✓  |
| `UNKNOWN` outcome    |      ✓     |      ✓     |   ✓  |
| UNKNOWN → SUCCESS    |      ✓     |      ✓     |   ✓  |
| UNKNOWN → FAILED     |      ✓     |      ✓     |   ✓  |
| PostgreSQL failure   |      —     |      ✓     |   ✓  |
| Kafka failure        |      —     |      ✓     |   ✓  |
| Kafka lag            |      —     |      ✓     |   ✓  |
| Outbox recovery      |      —     |      ✓     |   ✓  |
| Kafka DLQ            |      —     |      ✓     |   ✓  |
| Prometheus           |      —     |      —     |   ✓  |
| Grafana              |      —     |      —     |   ✓  |
| OpenTelemetry        |      —     |      —     |   ✓  |
| Mock OIDC            | simplified | simplified |   ✓  |

---

# 26. Local Integration Mode

When the fast Demo Mode is insufficient, use `local`.

Architecture:

```text
+------------------+
|     Corevia      |
+--------+---------+
         |
         +----------------------+
         |                      |
         v                      v
+----------------+       +-------------+
|   Mock T24     |       |    Kafka    |
| HTTP Service   |       |             |
+----------------+       +-------------+
         |
         v
+----------------+
|  PostgreSQL    |
+----------------+
```

Start:

```bash
docker compose --profile local up -d
```

Then:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Use this mode when testing actual network integration and Kafka behavior.

---

# 27. Full Architecture Mode

Full Mode adds infrastructure useful for an architecture demonstration.

```text
                         +----------------+
                         |    Grafana     |
                         +-------+--------+
                                 |
                         +-------v--------+
                         |   Prometheus   |
                         +-------+--------+
                                 |
+---------+              +-------v--------+
| Client  +------------->|    Corevia     |
+---------+              +---+---------+-+
                             |         |
                             |         |
                       +-----v--+   +--v------+
                       | Mock   |   |  Kafka  |
                       | T24    |   +---------+
                       +--------+       |
                                        v
                                  Event Consumers

                             |
                             v
                       +-----------+
                       | PostgreSQL|
                       +-----------+

                    +----------------+
                    | OpenTelemetry  |
                    | / Jaeger       |
                    +----------------+
```

Start:

```bash
docker compose --profile full up -d
```

Run Corevia:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=full
```

This mode is intended for architecture exploration rather than everyday development.

---

# 28. Production Mode

Production does not use any of the local mocks.

```text
Digital Channels
       |
       v
 API Gateway
       |
       v
 Corevia
       |
       +------------------> T24 / Transact
       |
       +------------------> Kafka
       |
       +------------------> OIDC Provider
       |
       +------------------> PostgreSQL
       |
       +------------------> Observability Platform
```

The production implementation uses:

```text
T24Adapter
KafkaEventPublisher
OIDC/OAuth2
real PostgreSQL
production observability
```

The domain and application layers remain unchanged.

---

# 29. Why PostgreSQL Is Not Mocked

It may seem attractive to mock everything for maximum speed.

However, PostgreSQL is deliberately kept real.

Corevia's critical behavior depends on database guarantees:

```text
Unique constraints
        +
Transactions
        +
Concurrency
        +
Persistence
        +
Outbox consistency
```

For example, two concurrent requests with the same idempotency key should result in:

```text
Request A ----+
              |
              v
         PostgreSQL
              |
Request B ----+
              |
              v
      unique constraint
```

Only one logical transaction should be created.

Using a real PostgreSQL instance makes this behavior demonstrable.

---

# 30. Testcontainers

Testcontainers is used for automated integration testing.

It is separate from the normal Demo Mode.

Example:

```text
mvn test
```

Integration tests can automatically start:

```text
PostgreSQL
Kafka
Mock T24 / WireMock
```

as required by individual tests.

This gives the project three useful levels:

```text
Demo
  |
  +--> fastest manual demonstration

Local
  |
  +--> realistic developer integration

Testcontainers
  |
  +--> automated integration testing
```

---

# 31. Recommended Interview Demo

For a 15–20 minute architecture interview:

### Step 1 — Successful transfer

Show:

```text
POST /transfers
```

and explain:

```text
API
 -> Application Service
 -> CoreBankingGateway
 -> Mock T24
 -> PostgreSQL
```

### Step 2 — Duplicate request

Send the same request again.

Show:

```text
same Idempotency-Key
        |
        v
same Transaction
        |
        X
no duplicate T24 transfer
```

### Step 3 — Business failure

Simulate:

```text
INSUFFICIENT_FUNDS
```

Show:

```text
FAILED
```

### Step 4 — Timeout

Simulate:

```text
TIMEOUT
```

Show:

```text
UNKNOWN
```

Then perform status inquiry:

```text
UNKNOWN -> SUCCESS
```

Explain why Corevia does not blindly retry.

### Step 5 — Architecture discussion

Explain:

```text
Hexagonal Architecture
        |
        +-- T24 Adapter
        +-- Kafka Adapter
        +-- PostgreSQL Adapter
        +-- Security Adapter
```

This demonstrates that the external dependencies can be replaced without changing the business/application core.

---

# 32. Recommended 5-Minute Demo

If interview time is extremely limited:

```text
1. SUCCESS transfer
       |
       v
2. Duplicate request
       |
       v
3. TIMEOUT -> UNKNOWN
       |
       v
4. Status inquiry -> SUCCESS
```

The key message is:

> Corevia is not just an API wrapper. It protects banking transactions from distributed-system failure modes.

---

# 33. Cleanup

Stop PostgreSQL:

```bash
docker compose down
```

Remove PostgreSQL data as well:

```bash
docker compose down -v
```

Use `-v` only when you want to reset the local database completely.

---

# 34. Troubleshooting

## PostgreSQL is not running

Check:

```bash
docker compose ps
```

Start it:

```bash
docker compose up -d postgres
```

---

## Port 5432 already in use

Check:

```bash
lsof -i :5432
```

Either stop the existing PostgreSQL instance or change the Docker port mapping.

---

## Corevia cannot connect to PostgreSQL

Verify:

```text
Host: localhost
Port: 5432
Database: corevia
Username: corevia
```

Check application configuration for the active profile:

```text
demo
```

---

## Port 8080 already in use

Check:

```bash
lsof -i :8080
```

Or configure another application port:

```text
server.port=8081
```

---

## Demo T24 scenario is not behaving as expected

Verify that the application is running with:

```text
spring.profiles.active=demo
```

The in-process `MockT24Gateway` is enabled only for the appropriate profile.

---

# 35. Architecture Summary

The Corevia local strategy is intentionally layered:

```text
                     FAST
                      |
                      v
              +---------------+
              |  Demo Mode    |
              |               |
              | Mock T24       |
              | In-Memory Kafka|
              | Local Security |
              +-------+-------+
                      |
                      v
                 PostgreSQL
                      |
                      v
               Corevia Domain
```

Then progressively increase realism:

```text
Demo
  |
  v
Local
  |
  v
Full
  |
  v
Production
```

The key architectural principle remains constant:

```text
                   Ports
                     |
        +------------+------------+
        |            |            |
        v            v            v
    T24 Adapter   Kafka Adapter  DB Adapter
        |            |            |
        v            v            v
       T24         Kafka       PostgreSQL
```

Only the adapters and runtime dependencies change.

This allows Corevia to provide a **very fast local developer experience** while still demonstrating production-grade banking integration concepts such as:

* Hexagonal Architecture
* T24 integration
* Idempotency
* Transaction state management
* `UNKNOWN` financial outcomes
* Safe retry boundaries
* Transaction status inquiry
* PostgreSQL consistency
* Transactional outbox
* Event-driven integration
* Security boundaries
* Auditability
* Observability
* Resilience

---

## 36. Quick Reference

### Fastest demo

```bash
docker compose up -d postgres

./mvnw spring-boot:run \
  -Dspring-boot.run.profiles=demo
```

### Local integration

```bash
docker compose --profile local up -d

./mvnw spring-boot:run \
  -Dspring-boot.run.profiles=local
```

### Full architecture demo

```bash
docker compose --profile full up -d

./mvnw spring-boot:run \
  -Dspring-boot.run.profiles=full
```

### Tests

```bash
./mvnw test
```

### Stop everything

```bash
docker compose down
```

### Reset local database

```bash
docker compose down -v
```

---

## 37. Final Design Principle

Corevia deliberately separates:

```text
Business logic
      |
      v
Application ports
      |
      v
Infrastructure adapters
      |
      v
External systems
```

Therefore:

```text
Mock T24
    can become
Real T24

InMemory Events
    can become
Kafka

Local Security
    can become
OIDC

without rewriting the Corevia domain/application logic.
```

That is the primary reason the fast local demo remains architecturally representative of the production system.
