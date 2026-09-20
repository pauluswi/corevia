# Corevia — Architecture Documentation

**Project:** Corevia
**System:** T24 / Temenos Transact Integration Middleware
**Target Core:** Temenos Transact R25
**Architecture Style:** Hexagonal Architecture + Integration Patterns + Event-Driven Architecture
**Technology:** Java 25, Spring Boot, PostgreSQL, Kafka, Docker
**Documentation:** arc42

---

# 1. Introduction and Goals

## 1.1 Overview

**Corevia** is an enterprise integration middleware designed to connect digital banking channels with **Temenos Transact (formerly T24)**.

The system provides a stable, channel-facing REST API while isolating the consuming applications from core-banking-specific protocols, data models, error formats, and integration mechanisms.

The project demonstrates architecture patterns commonly required in banking and payment systems, including:

* Core-banking integration
* API orchestration
* Hexagonal architecture
* Adapter pattern
* Idempotency
* Resilience
* Transaction-state management
* Event-driven architecture
* Auditability
* Observability
* Containerization

The project targets **Temenos Transact R25 concepts and APIs**, while the actual core-banking environment is represented by a Mock Transact service.

---

## 1.2 Business Goals

Corevia shall demonstrate how an organization can expose modern APIs to digital channels while preserving the core-banking system as the authoritative system of record.

The primary business goals are:

1. Simplify digital-channel integration with T24/Transact.
2. Protect the core banking system from channel-specific complexity.
3. Provide consistent APIs across different channels.
4. Safely process financial transactions.
5. Prevent duplicate transactions.
6. Provide controlled behavior during core-banking failures.
7. Support asynchronous downstream processing.
8. Provide sufficient auditability and observability for banking operations.

---

## 1.3 Technical Goals

The architecture shall demonstrate:

* Clear separation of concerns.
* Dependency inversion.
* Technology-independent business logic.
* Replaceable T24 integration adapters.
* Explicit transaction state management.
* Safe retry behavior.
* Idempotent financial operations.
* Event publication.
* Centralized error handling.
* Automated testing.
* Containerized deployment.

---

## 1.4 Quality Goals

| Priority | Quality Attribute | Goal                                                   |
| -------- | ----------------- | ------------------------------------------------------ |
| 1        | Reliability       | Financial operations must avoid accidental duplication |
| 2        | Maintainability   | T24-specific code isolated from business logic         |
| 3        | Observability     | Requests and transactions traceable end-to-end         |
| 4        | Resilience        | Controlled behavior when T24 is unavailable            |
| 5        | Security          | Sensitive data protected and minimized                 |
| 6        | Scalability       | Middleware instances can scale horizontally            |
| 7        | Testability       | Core business logic testable without T24               |
| 8        | Performance       | Low-overhead API orchestration                         |

---

# 2. Architecture Constraints

## 2.1 Business Constraints

Corevia is a showcase project rather than a production banking platform.

Therefore:

* No production customer data is used.
* No real financial transactions are performed.
* No licensed Temenos environment is required.
* The core-banking system is simulated.
* The project must remain executable locally.

---

## 2.2 Technical Constraints

The baseline technology stack is:

```text
Java             25
Framework        Spring Boot
Build            Maven
Database         PostgreSQL
Messaging        Apache Kafka
API              REST / JSON
Documentation    OpenAPI
Containerization Docker
Testing          JUnit + Testcontainers
Observability    Micrometer
```

The project shall use **Maven rather than Gradle**.

---

## 2.3 Core-Banking Constraint

The actual Temenos Transact R25 installation is not part of the project.

Therefore:

```text
Corevia
   |
   v
T24 Adapter
   |
   v
Mock Transact R25
```

The Mock Transact service simulates selected core-banking operations.

The architecture must nevertheless maintain a clean boundary so that a real Transact adapter could theoretically replace the mock implementation.

---

# 3. System Scope and Context

## 3.1 System Context

```text
                         +------------------+
                         | Digital Banking  |
                         | Channel          |
                         +--------+---------+
                                  |
                                  | REST / JSON
                                  |
                                  v
                         +------------------+
                         |                  |
                         |    COREvia       |
                         |                  |
                         | T24 Integration  |
                         | Middleware       |
                         |                  |
                         +--------+---------+
                                  |
                                  |
                                  v
                         +------------------+
                         | Temenos          |
                         | Transact R25     |
                         |                  |
                         | Mock Environment |
                         +------------------+

                                  |
                                  v
                         +------------------+
                         | Kafka            |
                         | Event Platform   |
                         +------------------+
```

---

## 3.2 External Actors and Systems

### Digital Channel

Represents:

* Mobile banking
* Internet banking
* Branch applications
* Other enterprise applications

The channel communicates using Corevia's REST API.

### Temenos Transact

The core-banking system responsible for authoritative banking operations and data.

### Kafka

Provides asynchronous event distribution for downstream consumers.

### PostgreSQL

Stores middleware-owned operational information such as:

* Idempotency records
* Transaction state
* Audit records
* Integration metadata

PostgreSQL is **not** the system of record for core banking balances.

---

# 4. Solution Strategy

## 4.1 Hexagonal Architecture

Corevia follows a ports-and-adapters approach.

```text
                    External World
                         |
          +--------------+--------------+
          |                             |
       REST API                     Kafka
          |                             |
          v                             ^
   +-----------------------------------------+
   |              Application                |
   |                                         |
   |  Transfer Service                       |
   |  Account Service                        |
   |  Customer Service                       |
   |                                         |
   +-------------------+---------------------+
                       |
                       v
              +----------------+
              | Domain Model   |
              +----------------+
                       |
              +--------+--------+
              |                 |
              v                 v
      CoreBankingPort      EventPublisherPort
              |                 |
              v                 v
        T24 Adapter           Kafka
              |
              v
         Transact Core
```

The core application does not directly depend on infrastructure technologies.

---

## 4.2 Adapter Pattern for T24

The application interacts with the core banking system through an abstraction:

```java
public interface CoreBankingGateway {

    Customer getCustomer(String customerId);

    Account getAccount(String accountId);

    TransferResult transfer(TransferRequest request);

    TransactionStatus getTransactionStatus(String transactionId);
}
```

The concrete implementation is:

```text
T24Adapter
```

This isolates T24-specific details.

---

## 4.3 System of Record

Temenos Transact remains the authoritative system for:

* Account balances
* Core customer/account relationships
* Core financial transactions
* Core transaction status

Corevia stores operational state required to safely perform integration.

It does not attempt to replace the core banking ledger.

---

## 4.4 Idempotency Strategy

Financial transaction APIs require protection against duplicate requests.

The middleware stores:

```text
Idempotency-Key
Request Hash
Transaction ID
Processing State
Response
Timestamp
```

Processing model:

```text
Request
   |
   v
Check Idempotency Key
   |
   +---- Existing ----> Return Previous Result
   |
   +---- New
          |
          v
       Process
          |
          v
       Store Result
```

A duplicate request shall never automatically generate a second core transaction.

---

## 4.5 Unknown Transaction State

A core-banking timeout does not necessarily mean transaction failure.

Example:

```text
Corevia
   |
   | Transfer
   v
Transact
   |
   | transaction committed
   |
   X response lost
   |
Corevia = UNKNOWN
```

Corevia shall represent this state explicitly.

```text
RECEIVED
    |
VALIDATING
    |
SUBMITTED
    |
 +--+---------+
 |            |
 v            v
SUCCESS     UNKNOWN
              |
              v
       Status Inquiry
```

This prevents unsafe blind retries.

---

## 4.6 Resilience Strategy

The T24 integration shall implement:

* Connection timeout
* Read timeout
* Circuit breaker
* Controlled retry for safe operations
* Transaction status inquiry
* Error classification

Financial operations shall not use unrestricted automatic retries.

---

## 4.7 Event-Driven Strategy

Successful business operations generate domain events.

Example:

```text
TransferCompleted
```

Flow:

```text
REST Request
     |
     v
Transfer Service
     |
     v
T24
     |
     v
SUCCESS
     |
     v
Event Publisher
     |
     v
Kafka
```

Kafka consumers can later support:

* Notifications
* Audit processing
* Analytics
* Reconciliation
* Fraud monitoring

---

# 5. Building Block View

## 5.1 Level 1 — Containers

```text
+---------------------------------------------------------+
|                       Corevia                           |
|                                                         |
| +------------------+      +--------------------------+ |
| | Middleware       |      | Mock Transact R25       | |
| |                  |----->|                          | |
| | REST API         |      | Customer                 | |
| | Application      |      | Account                  | |
| | Domain          |      | Transfer                 | |
| | T24 Adapter      |      | Transaction Status       | |
| +--------+---------+      +--------------------------+ |
|          |                                            |
|          v                                            |
| +------------------+                                  |
| | PostgreSQL       |                                  |
| +------------------+                                  |
|          |                                            |
|          v                                            |
| +------------------+                                  |
| | Kafka            |                                  |
| +------------------+                                  |
+---------------------------------------------------------+
```

---

## 5.2 Middleware Components

The middleware contains:

```text
API Layer
Application Layer
Domain Layer
Port Layer
Adapter Layer
Infrastructure Layer
```

---

## 5.3 API Layer

Responsibilities:

* HTTP request handling
* Request validation
* Authentication
* API versioning
* Response formatting
* Correlation ID handling

Example:

```text
TransferController
AccountController
CustomerController
TransactionController
```

---

## 5.4 Application Layer

Responsible for use-case orchestration.

Example:

```text
TransferService
AccountInquiryService
CustomerInquiryService
TransactionStatusService
```

The application layer coordinates:

```text
Validation
   ↓
Idempotency
   ↓
Core Banking
   ↓
Persistence
   ↓
Event Publishing
```

---

## 5.5 Domain Layer

Contains business concepts independent of infrastructure.

Examples:

```text
Transfer
Account
Customer
Transaction
Money
TransactionStatus
```

The domain layer shall not depend on:

* Spring MVC
* PostgreSQL
* Kafka
* HTTP clients
* T24 libraries

---

## 5.6 Port Layer

Primary ports:

```text
TransferUseCase
AccountInquiryUseCase
CustomerInquiryUseCase
TransactionStatusUseCase
```

Secondary ports:

```text
CoreBankingGateway
TransactionRepository
IdempotencyRepository
AuditRepository
EventPublisher
```

---

## 5.7 Adapter Layer

Primary adapters:

```text
REST Controllers
Kafka Consumers
```

Secondary adapters:

```text
T24Adapter
PostgresTransactionRepository
PostgresIdempotencyRepository
KafkaEventPublisher
```

---

# 6. Runtime View

## 6.1 Successful Transfer

```text
Client
  |
  | POST /api/v1/transfers
  v
TransferController
  |
  v
TransferService
  |
  +--> IdempotencyRepository
  |
  +--> CoreBankingGateway
  |          |
  |          v
  |       T24Adapter
  |          |
  |          v
  |       Transact
  |
  +--> TransactionRepository
  |
  +--> EventPublisher
             |
             v
           Kafka
```

---

## 6.2 Duplicate Request

```text
Client
  |
  | same Idempotency-Key
  v
TransferController
  |
  v
TransferService
  |
  v
IdempotencyRepository
  |
  +--> Existing record
           |
           v
      Previous Result
           |
           v
         Client
```

No second T24 transaction is created.

---

## 6.3 T24 Timeout

```text
Client
  |
  v
Corevia
  |
  v
T24
  |
  X timeout
  |
  v
Corevia
  |
  v
UNKNOWN
  |
  v
Transaction Status Inquiry
```

---

## 6.4 T24 Unavailable

```text
Request
   |
   v
T24 Adapter
   |
   X
   |
Circuit Breaker
   |
   v
OPEN
   |
   v
Fail Fast
```

---

# 7. Deployment View

## 7.1 Local Development

Docker Compose shall provide:

```text
+------------------------------------------------+
| Docker Compose                                 |
|                                                |
|  +-------------+     +----------------------+  |
|  | Corevia     |---->| Mock Transact R25   |  |
|  +------+------+     +----------------------+  |
|         |                                      |
|         +---------> PostgreSQL                 |
|         |                                      |
|         +---------> Kafka                      |
|                                                |
+------------------------------------------------+
```

---

## 7.2 Future Kubernetes Deployment

The middleware shall be designed to run as multiple replicas.

```text
                    Load Balancer
                         |
             +-----------+-----------+
             |           |           |
             v           v           v
          Corevia     Corevia     Corevia
             |           |           |
             +-----------+-----------+
                         |
                  Shared Services
```

The middleware should remain stateless where practical.

---

# 8. Crosscutting Concepts

## 8.1 Error Handling

Errors are classified into:

```text
Validation Error
Business Error
Core Banking Error
Technical Error
Timeout
Unknown Transaction State
```

Each category maps to a standardized API response.

---

## 8.2 Logging

Logs shall contain:

```text
timestamp
level
service
correlationId
transactionId
operation
duration
result
```

Sensitive data shall be masked.

---

## 8.3 Correlation

Every request receives:

```text
X-Correlation-ID
```

The identifier follows the request through:

```text
API
 ↓
Application
 ↓
T24 Adapter
 ↓
Database
 ↓
Kafka
```

---

## 8.4 Security

Security boundaries include:

```text
Client
  |
  | JWT
  v
API
  |
  v
Application
  |
  v
T24 Adapter
```

Secrets and credentials shall be supplied through external configuration.

---

## 8.5 Configuration

Configuration shall be environment-specific.

Examples:

```text
T24_BASE_URL
T24_CONNECT_TIMEOUT
T24_READ_TIMEOUT

DATABASE_URL

KAFKA_BOOTSTRAP_SERVERS

JWT_ISSUER
```

No environment-specific credentials shall be committed to Git.

---

# 9. Architecture Decisions

The following ADRs shall accompany this document.

## ADR-001 — Hexagonal Architecture

**Decision:** Use Hexagonal Architecture.

**Reason:** Core banking integration logic should remain independent of infrastructure and transport mechanisms.

---

## ADR-002 — T24 Adapter

**Decision:** Isolate Transact integration behind `CoreBankingGateway`.

**Reason:** T24-specific implementation details should not leak into the domain or application layer.

---

## ADR-003 — Idempotency

**Decision:** Require idempotency for financial transaction APIs.

**Reason:** Network retries and client retries can otherwise create duplicate financial transactions.

---

## ADR-004 — Explicit UNKNOWN State

**Decision:** Represent uncertain transaction outcomes explicitly.

**Reason:** A timeout cannot safely be interpreted as transaction failure.

---

## ADR-005 — Kafka Events

**Decision:** Publish business events through Kafka.

**Reason:** Downstream consumers should not need synchronous coupling to the transaction API.

---

## ADR-006 — T24 as System of Record

**Decision:** Keep core banking data authoritative in Transact.

**Reason:** Middleware operational storage must not become a competing banking ledger.

---

# 10. Quality Scenarios

## Q1 — Duplicate Transfer

**Stimulus:** Client submits the same transfer twice.

**Expected:** Only one core-banking transaction is created.

---

## Q2 — T24 Timeout

**Stimulus:** T24 does not respond within configured timeout.

**Expected:** Middleware does not blindly retry the financial transaction and represents the transaction as `UNKNOWN` when the final state cannot be established.

---

## Q3 — T24 Outage

**Stimulus:** T24 becomes unavailable.

**Expected:** Circuit breaker opens and middleware fails fast after configured thresholds.

---

## Q4 — Horizontal Scaling

**Stimulus:** Multiple middleware instances process concurrent requests.

**Expected:** Idempotency remains consistent because state is stored in shared infrastructure rather than local memory.

---

## Q5 — Traceability

**Stimulus:** An operator receives a transaction ID.

**Expected:** The transaction can be correlated across application logs, audit records, T24 integration logs, and Kafka events.

---

# 11. Risks and Technical Debt

## R1 — Mock T24 vs Real T24

The project cannot validate behavior against a licensed Transact environment.

**Mitigation:** Keep the adapter boundary clean and document simulated behavior.

---

## R2 — Simplified Core Banking Model

The mock core will not represent the full complexity of T24.

**Mitigation:** Explicitly define supported operations and avoid claiming full T24 compatibility.

---

## R3 — Event Delivery

Publishing a Kafka event after a successful database transaction can create consistency challenges.

**Future consideration:**

```text
Transactional Outbox
```

This may be implemented in a later iteration.

---

## R4 — Distributed Transaction

The middleware cannot assume a single ACID transaction across:

```text
PostgreSQL
T24
Kafka
```

**Mitigation:** Use explicit state management, idempotency, reconciliation and eventually-consistent event processing.

---

# 12. Architecture Evolution

## Phase 1 — Core Middleware

```text
REST
  ↓
Corevia
  ↓
Mock T24
```

Features:

* Account inquiry
* Customer inquiry
* Transfer
* Error handling

---

## Phase 2 — Reliability

Add:

```text
Idempotency
Timeout
Circuit Breaker
Transaction State
Audit
```

---

## Phase 3 — Event Driven

Add:

```text
Kafka
Events
Event Consumers
```

---

## Phase 4 — Production Engineering

Add:

```text
Metrics
Tracing
Docker
Integration Tests
Testcontainers
```

---

## Phase 5 — Cloud Native

Optional:

```text
Kubernetes
AWS EKS
Horizontal Scaling
Prometheus
Grafana
```

---

# 13. Architecture Summary

Corevia separates digital-channel APIs from the core-banking integration layer.

The central architectural principle is:

```text
             Digital Channels
                    |
                    v
              Corevia API
                    |
                    v
            Application Layer
                    |
                    v
             Domain / Ports
                    |
                    v
             T24 Adapter
                    |
                    v
          Temenos Transact
```

This separation allows Corevia to evolve independently from both the consuming channels and the underlying core-banking implementation.

The project intentionally demonstrates the architectural concerns that become important when integrating modern digital channels with a mission-critical banking core:

**reliability, idempotency, resilience, transaction-state management, observability, auditability, and controlled integration boundaries.**
