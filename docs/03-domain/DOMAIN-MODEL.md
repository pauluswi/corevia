# Corevia Domain Model

## 1. Purpose

This document defines the business domain model for **Corevia**, including:

* Core domain entities
* Value objects
* Enumerations
* Aggregate boundaries
* Entity relationships
* Transaction lifecycle
* Business invariants
* Domain responsibilities
* Mapping between API models and domain models
* Mapping between domain models and T24 integration models

The domain model is intentionally independent of:

* Spring Boot
* REST
* PostgreSQL
* Kafka
* HTTP
* T24-specific data structures

This allows the business logic to remain stable even when infrastructure implementations change.

---

# 2. Domain Overview

Corevia initially models four primary business concepts:

```text
Customer
   │
   │ owns
   ▼
Account
   │
   │ participates in
   ▼
Transfer
   │
   │ represented by
   ▼
Transaction
```

Conceptually:

```text
┌──────────────┐
│   Customer   │
└──────┬───────┘
       │
       │ owns
       ▼
┌──────────────┐
│   Account    │
└──────┬───────┘
       │
       │
       │       ┌─────────────────┐
       └──────►│    Transfer     │
               └────────┬────────┘
                        │
                        │ creates
                        ▼
               ┌─────────────────┐
               │   Transaction   │
               └─────────────────┘
```

The model is intentionally smaller than a real banking core.

Corevia is an **integration middleware**, not a replacement for the core banking domain.

---

# 3. Domain Boundaries

Corevia owns:

```text
Transfer orchestration
Transaction lifecycle
Idempotency
Integration state
Correlation
Operational audit metadata
```

Corevia does **not** own:

```text
Customer master data
Account ledger
Account balance
Interest calculation
General ledger
Core banking product configuration
Core banking accounting rules
```

Those remain responsibilities of the core banking system.

---

# 4. Core Domain Concepts

## 4.1 Customer

Represents a customer retrieved from the core banking system.

```text
Customer
├── customerId
├── fullName
└── status
```

Example:

```json
{
  "customerId": "CUST000123",
  "fullName": "John Doe",
  "status": "ACTIVE"
}
```

### Responsibilities

Customer is primarily an **inquiry model** in Corevia.

Corevia does not become the master owner of customer information.

---

# 5. Customer Identifier

Customer IDs are represented as strings.

Example:

```text
CUST000123
```

The domain should not assume a specific T24 identifier format.

Therefore:

```java
CustomerId
```

is preferable to embedding T24-specific assumptions throughout the application.

---

# 6. Customer Status

```text
ACTIVE
BLOCKED
CLOSED
```

Java representation:

```java
public enum CustomerStatus {
    ACTIVE,
    BLOCKED,
    CLOSED
}
```

---

# 7. Account

Represents a banking account retrieved from the core banking system.

```text
Account
├── accountId
├── customerId
├── currency
├── availableBalance
└── status
```

Example:

```json
{
  "accountId": "1000012345",
  "customerId": "CUST000123",
  "currency": "IDR",
  "availableBalance": 25000000.00,
  "status": "ACTIVE"
}
```

---

# 8. Account Ownership

An account belongs to a customer.

Conceptually:

```text
Customer 1 ──────────── * Account
```

One customer may have multiple accounts.

Corevia retrieves this relationship from the core banking system rather than maintaining an independent customer/account master.

---

# 9. Account Status

```text
ACTIVE
BLOCKED
CLOSED
```

Java representation:

```java
public enum AccountStatus {
    ACTIVE,
    BLOCKED,
    CLOSED
}
```

---

# 10. Money

Money is represented using two components:

```text
Money
├── amount
└── currency
```

Example:

```text
1,500,000.00 IDR
```

Java representation:

```java
public record Money(
    BigDecimal amount,
    CurrencyCode currency
) {}
```

The implementation must use:

```java
BigDecimal
```

for monetary amounts.

Never use:

```java
double
float
```

for financial calculations.

---

# 11. CurrencyCode

Currency follows ISO 4217 conventions.

Initial supported currency:

```text
IDR
```

Example:

```java
public enum CurrencyCode {
    IDR
}
```

The model can later expand to:

```text
USD
EUR
SGD
JPY
```

without changing the fundamental `Money` abstraction.

---

# 12. Transfer

`Transfer` represents the business intent to move money from one account to another.

```text
Transfer
├── transactionId
├── sourceAccount
├── destinationAccount
├── amount
├── currency
├── reference
├── status
├── idempotencyKey
├── correlationId
├── createdAt
└── updatedAt
```

Example:

```json
{
  "transactionId": "TXN-20260920-000001",
  "sourceAccount": "1000012345",
  "destinationAccount": "2000098765",
  "amount": 1500000.00,
  "currency": "IDR",
  "reference": "PAYROLL-202609",
  "status": "SUCCESS"
}
```

---

# 13. Transfer as Corevia's Main Aggregate

The `Transfer` is the primary financial aggregate in the initial version of Corevia.

It owns the lifecycle of the transfer request.

Conceptually:

```text
Transfer
   │
   ├── Transaction ID
   ├── Idempotency Key
   ├── Source Account
   ├── Destination Account
   ├── Money
   ├── Reference
   └── Transaction Status
```

The aggregate protects important business invariants.

---

# 14. Transfer Invariants

A valid transfer must satisfy:

### Rule 1 — Amount must be positive

```text
amount > 0
```

---

### Rule 2 — Source and destination must differ

```text
sourceAccount != destinationAccount
```

---

### Rule 3 — Currency must be supported

```text
currency ∈ supported currencies
```

---

### Rule 4 — Reference must not be blank

```text
reference != null
reference != ""
```

---

### Rule 5 — Idempotency key is mandatory

Every financial transfer must have an idempotency key.

---

### Rule 6 — Terminal transactions cannot be modified

Once a transaction reaches:

```text
SUCCESS
FAILED
```

the financial outcome must not be changed by another transfer request.

---

### Rule 7 — `UNKNOWN` must not be blindly retried

An `UNKNOWN` transaction requires status resolution.

It must not automatically create another financial transaction.

---

# 15. Transaction

`Transaction` represents the processing state of a financial operation within Corevia.

It is closely associated with a `Transfer`.

The distinction is intentional:

```text
Transfer
= business intent

Transaction
= processing lifecycle
```

This allows Corevia to separate:

```text
"What does the client want?"
```

from:

```text
"What happened while processing it?"
```

---

# 16. Transaction Identifier

Every financial operation receives a unique transaction identifier.

Example:

```text
TXN-20260920-000001
```

The identifier is generated by Corevia.

It is not required to expose or reuse a T24 internal identifier.

The domain should therefore use its own:

```java
TransactionId
```

value object.

---

# 17. Transaction State

The transaction lifecycle is:

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
                    STATUS CHECK
                         │
                    ┌────┴────┐
                    ▼         ▼
                 SUCCESS     FAILED
```

---

# 18. TransactionStatus

```java
public enum TransactionStatus {

    RECEIVED,

    VALIDATING,

    SUBMITTED,

    SUCCESS,

    FAILED,

    UNKNOWN
}
```

---

# 19. State Transition Rules

Valid transitions:

```text
RECEIVED
   ↓
VALIDATING
```

```text
VALIDATING
   ↓
SUBMITTED
```

```text
SUBMITTED
   ├──► SUCCESS
   ├──► FAILED
   └──► UNKNOWN
```

```text
UNKNOWN
   ├──► SUCCESS
   └──► FAILED
```

Invalid examples:

```text
SUCCESS → SUBMITTED
SUCCESS → UNKNOWN
FAILED  → SUBMITTED
FAILED  → SUCCESS
```

Terminal states:

```text
SUCCESS
FAILED
```

Resolution state:

```text
UNKNOWN
```

---

# 20. Why `UNKNOWN` Is a Domain State

`UNKNOWN` is not simply an infrastructure error.

It represents a genuine business condition:

> Corevia cannot currently determine the final financial outcome.

For example:

```text
Corevia
   │
   │ transfer
   ▼
T24
   │
   │ transaction processed
   │
   X response lost
   │
   ▼
Corevia
```

Corevia cannot safely conclude:

```text
FAILED
```

because T24 may already have processed the transaction.

Therefore:

```text
UNKNOWN
```

becomes part of the domain model.

---

# 21. Resolving UNKNOWN

An `UNKNOWN` transaction can be resolved using:

```text
GET /api/v1/transfers/{transactionId}
```

Conceptually:

```text
UNKNOWN
   │
   ▼
Query core banking
   │
   ├── transaction exists and succeeded
   │          ↓
   │       SUCCESS
   │
   └── transaction failed
              ↓
           FAILED
```

The resolution mechanism must not create another transfer.

---

# 22. IdempotencyKey

`IdempotencyKey` identifies a logical client request.

Example:

```text
7c8f2d10-8f4a-4c31-a2bd-123456789abc
```

It should be represented as a domain value rather than passing raw strings throughout the application.

Example:

```java
public record IdempotencyKey(String value) {

    public IdempotencyKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                "Idempotency key must not be blank"
            );
        }
    }
}
```

---

# 23. Idempotency Invariant

The combination:

```text
IdempotencyKey + RequestFingerprint
```

determines whether a request is a duplicate.

Conceptually:

```text
Same key
   │
   ├── Same request
   │      ↓
   │   Return existing transaction
   │
   └── Different request
          ↓
       CONFLICT
```

The idempotency record must be persistent.

An in-memory map is insufficient because multiple Corevia instances may process requests.

---

# 24. Request Fingerprint

Corevia calculates a deterministic fingerprint from the relevant request fields.

Conceptually:

```text
sourceAccount
destinationAccount
amount
currency
reference
```

→ canonical representation

→ hash

→ request fingerprint

This allows Corevia to detect:

```text
same key + same request
```

versus:

```text
same key + different request
```

The exact hashing implementation belongs to the application/infrastructure layer rather than the core domain.

---

# 25. CorrelationId

`CorrelationId` identifies the processing flow across distributed components.

Example:

```text
CORR-20260920-001
```

It is used for:

* API requests
* application logs
* T24 calls
* Kafka events
* audit records
* distributed tracing

It is different from `TransactionId`.

---

# 26. Transaction ID vs Correlation ID

| Identifier       | Purpose                                  |
| ---------------- | ---------------------------------------- |
| `TransactionId`  | Identifies the financial transaction     |
| `CorrelationId`  | Identifies the technical processing flow |
| `IdempotencyKey` | Identifies the logical client request    |

Example:

```text
TransactionId
TXN-001

CorrelationId
CORR-001

IdempotencyKey
CLIENT-ABC-123
```

One request can therefore be traced without confusing technical correlation with financial identity.

---

# 27. Reference

`Reference` represents a client-provided business reference.

Example:

```text
PAYROLL-202609
```

Constraints:

```text
Required
Maximum 50 characters
Must not be blank
```

The reference is not the transaction identifier.

---

# 28. Domain Value Objects

The initial domain should use value objects for important concepts:

```text
CustomerId
AccountId
TransactionId
IdempotencyKey
CorrelationId
Money
CurrencyCode
Reference
```

Conceptually:

```text
domain/
├── customer/
│   ├── Customer.java
│   ├── CustomerId.java
│   └── CustomerStatus.java
│
├── account/
│   ├── Account.java
│   ├── AccountId.java
│   └── AccountStatus.java
│
└── transfer/
    ├── Transfer.java
    ├── TransactionId.java
    ├── IdempotencyKey.java
    ├── CorrelationId.java
    ├── Money.java
    ├── CurrencyCode.java
    ├── Reference.java
    └── TransactionStatus.java
```

---

# 29. Value Object Characteristics

Value objects should:

* be immutable
* validate their own basic invariants
* have value-based equality
* avoid infrastructure dependencies

For example:

```java
public record AccountId(String value) {

    public AccountId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                "Account ID must not be blank"
            );
        }
    }
}
```

---

# 30. Transfer Aggregate Example

A conceptual Java representation:

```java
public final class Transfer {

    private final TransactionId transactionId;
    private final AccountId sourceAccount;
    private final AccountId destinationAccount;
    private final Money amount;
    private final Reference reference;
    private final IdempotencyKey idempotencyKey;
    private final CorrelationId correlationId;

    private TransactionStatus status;

    // domain behavior
}
```

The important point is that the aggregate should expose **behavior**, not merely getters and setters.

---

# 31. Domain Behavior

Examples:

```java
transfer.validate();

transfer.submit();

transfer.markSuccess();

transfer.markFailed();

transfer.markUnknown();

transfer.resolveSuccess();

transfer.resolveFailure();
```

Rather than allowing arbitrary:

```java
transfer.setStatus(...);
```

This prevents invalid state transitions.

---

# 32. Example State Transition API

Conceptually:

```java
public void markSubmitted() {
    requireStatus(TransactionStatus.VALIDATING);

    this.status = TransactionStatus.SUBMITTED;
}
```

```java
public void markSuccess() {
    requireStatus(TransactionStatus.SUBMITTED,
                  TransactionStatus.UNKNOWN);

    this.status = TransactionStatus.SUCCESS;
}
```

```java
public void markUnknown() {
    requireStatus(TransactionStatus.SUBMITTED);

    this.status = TransactionStatus.UNKNOWN;
}
```

```java
public void markFailed() {
    requireStatus(TransactionStatus.SUBMITTED,
                  TransactionStatus.UNKNOWN);

    this.status = TransactionStatus.FAILED;
}
```

The exact implementation can be refined during coding.

---

# 33. Aggregate Invariants

The `Transfer` aggregate should protect:

```text
Source account != destination account
Amount > 0
Currency supported
Reference present
Idempotency key present
Valid transaction state transition
Terminal states cannot be changed
UNKNOWN requires explicit resolution
```

These rules should not depend on REST controllers or database implementation.

---

# 34. Customer and Account as Read Models

Unlike `Transfer`, Customer and Account are initially treated primarily as inquiry models.

The core banking system owns:

```text
Customer
Account
Balance
Account status
Customer status
```

Corevia retrieves these through:

```java
CoreBankingGateway
```

rather than maintaining an independent authoritative copy.

---

# 35. Domain vs API Models

API DTOs should not be reused as domain entities.

For example:

```text
REST Request
     │
     ▼
TransferRequest
     │
     ▼
Domain
     │
     ▼
Transfer
     │
     ▼
Application Service
```

And responses:

```text
Domain
   │
   ▼
Transfer
   │
   ▼
TransferResponse
   │
   ▼
REST API
```

This prevents external API changes from directly changing the domain model.

---

# 36. Domain vs T24 Models

T24-specific models should also remain outside the domain.

```text
                Corevia Domain
                     │
                     │
             CoreBankingGateway
                     │
                     ▼
                 T24 Adapter
                     │
                     ▼
                T24 Models
```

For example, the domain should not contain:

```text
T24Field
T24Request
T24Response
T24-specific field names
T24 HTTP response structures
```

Those belong to:

```text
infrastructure/t24/
```

---

# 37. Domain Error Concepts

The domain should distinguish business errors from infrastructure errors.

Business-level examples:

```text
INVALID_ACCOUNT
ACCOUNT_BLOCKED
INSUFFICIENT_FUNDS
TRANSACTION_NOT_ALLOWED
INVALID_TRANSFER
```

Infrastructure-level examples:

```text
T24_TIMEOUT
T24_UNAVAILABLE
NETWORK_FAILURE
CONNECTION_FAILURE
```

The infrastructure layer translates technical failures into application-level outcomes.

---

# 38. Domain Events

Important business state changes may generate domain/application events.

Initial events:

```text
TransferSubmitted
TransferSucceeded
TransferFailed
TransferUnknown
```

Example:

```java
public record TransferSucceeded(
    TransactionId transactionId,
    AccountId sourceAccount,
    AccountId destinationAccount,
    Money amount,
    Instant occurredAt
) {}
```

Kafka-specific serialization does not belong in the domain event itself.

---

# 39. Domain Event Flow

```text
Transfer
   │
   │ state change
   ▼
TransferSucceeded
   │
   ▼
Application Event Publisher
   │
   ▼
Kafka Adapter
   │
   ▼
Kafka Topic
```

This maintains the architecture boundary:

```text
Domain/Application
        ↓
     Port
        ↓
 Kafka Adapter
```

---

# 40. Entity Relationship Overview

```text
┌──────────────────┐
│     Customer     │
│                  │
│ customerId       │
│ fullName         │
│ status           │
└────────┬─────────┘
         │
         │ 1:N
         ▼
┌──────────────────┐
│     Account      │
│                  │
│ accountId        │
│ customerId       │
│ currency         │
│ balance*         │
│ status           │
└──────────────────┘

* Balance is owned by T24,
  not by Corevia.


┌────────────────────────────────┐
│            Transfer            │
│                                │
│ transactionId                  │
│ sourceAccount                  │
│ destinationAccount             │
│ money                          │
│ reference                      │
│ idempotencyKey                 │
│ correlationId                  │
│ status                         │
└────────────────────────────────┘
```

---

# 41. Corevia-Owned Data vs T24-Owned Data

| Data                         | Owner   |
| ---------------------------- | ------- |
| Customer master              | T24     |
| Account master               | T24     |
| Account balance              | T24     |
| Core banking transaction     | T24     |
| Transaction ID               | Corevia |
| Transaction processing state | Corevia |
| Idempotency key              | Corevia |
| Request fingerprint          | Corevia |
| Correlation ID               | Corevia |
| Integration audit metadata   | Corevia |
| Kafka event metadata         | Corevia |

This boundary is critical.

Corevia should not create a second ledger.

---

# 42. Persistence Model

The domain model should not be forced to mirror the database schema.

For example:

```text
Domain:
Transfer

Persistence:
transfer_transaction
idempotency_record
audit_record
```

Database entities belong to:

```text
infrastructure/persistence/
```

and are mapped to/from domain objects.

This keeps persistence concerns outside the domain.

---

# 43. Domain Model Summary

The initial Corevia domain is intentionally compact:

```text
                    Corevia Domain
                         │
          ┌──────────────┼──────────────┐
          │              │              │
      Customer        Account        Transfer
          │              │              │
          │              │              ▼
          │              │         Transaction
          │              │              │
          └──────────────┴──────────────┘
                         │
                    Value Objects
                         │
        ┌────────────────┼────────────────┐
        │                │                │
    AccountId       TransactionId     Money
    CustomerId      IdempotencyKey    CurrencyCode
                    CorrelationId     Reference
```

The most important domain concept is the **Transfer lifecycle**, particularly:

```text
SUBMITTED
    │
    ├── SUCCESS
    ├── FAILED
    └── UNKNOWN
```

The `UNKNOWN` state, combined with idempotency and explicit status resolution, is a central reliability characteristic of Corevia.

---
