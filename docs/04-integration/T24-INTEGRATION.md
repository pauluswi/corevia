# Corevia T24 / Temenos Transact Integration

## 1. Purpose

This document defines how **Corevia** integrates with a T24 / Temenos Transact-style core banking system.

It covers:

* Core banking integration boundaries
* `CoreBankingGateway` port
* `T24Adapter`
* request and response mapping
* customer inquiry
* account inquiry
* funds transfer
* transaction status inquiry
* timeout handling
* error mapping
* resilience
* mock Transact integration
* system-of-record responsibilities
* production integration considerations

The goal is to demonstrate a realistic **banking integration architecture** while keeping Corevia independent from proprietary core-banking implementation details.

> **Important:** Corevia is a portfolio project. It does not contain proprietary Temenos software, licensed Transact components, production credentials, or production banking data. The T24 integration is represented through an adapter and Mock Transact service.

---

# 2. Integration Objective

Corevia sits between digital channels and the core banking system.

```text
┌──────────────────────┐
│   Digital Channels   │
│                      │
│ Mobile / Web / API   │
└──────────┬───────────┘
           │
           │ REST / JSON
           ▼
┌────────────────────────────────┐
│            Corevia             │
│                                │
│  API                          │
│    ↓                           │
│  Application Service           │
│    ↓                           │
│  Domain                        │
│    ↓                           │
│  CoreBankingGateway             │
│    ↓                           │
│  T24Adapter                    │
└───────────────┬────────────────┘
                │
                │ Core Banking Protocol
                ▼
┌────────────────────────────────┐
│      T24 / Temenos Transact    │
│                                │
│ System of Record               │
│                                │
│ Customers                      │
│ Accounts                       │
│ Balances                       │
│ Core Transactions              │
└────────────────────────────────┘
```

Corevia provides an abstraction between the channel-facing API and the core banking platform.

---

# 3. Why Use an Adapter?

The application layer should not directly depend on T24.

Avoid:

```text
Controller
    ↓
T24 HTTP Client
    ↓
T24
```

Instead:

```text
Controller
    ↓
Application Service
    ↓
CoreBankingGateway
    ↓
T24Adapter
    ↓
T24
```

This provides several benefits:

* T24 implementation details remain isolated
* domain logic remains technology-independent
* T24 API changes have a smaller blast radius
* Mock Transact can be used for development
* automated tests do not require a real T24 environment
* another core banking platform can theoretically be introduced later

---

# 4. Integration Boundary

The boundary is:

```text
              Corevia
┌───────────────────────────────┐
│                               │
│ Domain                        │
│ Application                   │
│                               │
│ CoreBankingGateway            │
│          │                    │
└──────────┼────────────────────┘
           │
           │ Adapter Boundary
           ▼
┌───────────────────────────────┐
│ T24Adapter                    │
│                               │
│ Mapping                       │
│ Protocol                      │
│ Authentication                │
│ Error Translation             │
│ Timeout Handling               │
└──────────┬────────────────────┘
           │
           ▼
      T24 / Transact
```

The adapter is an **anti-corruption layer** between Corevia's domain model and the core banking model.

---

# 5. Core Banking Port

The application layer defines the required capabilities through a port.

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

The application layer depends only on this abstraction.

It does not know whether the implementation is:

```text
T24Adapter
MockCoreBankingGateway
AnotherCoreBankingAdapter
```

---

# 6. Port Responsibilities

`CoreBankingGateway` represents the minimum core-banking capabilities required by Corevia.

| Operation                | Purpose                      |
| ------------------------ | ---------------------------- |
| `getCustomer()`          | Customer inquiry             |
| `getAccount()`           | Account inquiry              |
| `transfer()`             | Submit financial transaction |
| `getTransactionStatus()` | Resolve transaction outcome  |

The port should represent **business capabilities**, not low-level HTTP operations.

Avoid exposing methods such as:

```java
sendHttpRequest();

executeT24Url();

postToEndpoint();
```

Those are infrastructure concerns.

---

# 7. T24 Adapter

The concrete implementation is:

```text
T24Adapter
```

Conceptually:

```java
public class T24Adapter implements CoreBankingGateway {

    @Override
    public Customer getCustomer(String customerId) {
        // call T24
        // map response
        // return domain object
    }

    @Override
    public Account getAccount(String accountId) {
        // call T24
    }

    @Override
    public TransferResult transfer(
            TransferRequest request) {
        // call T24
    }

    @Override
    public TransactionStatus getTransactionStatus(
            String transactionId) {
        // call T24
    }
}
```

The adapter is responsible for translating between:

```text
Corevia Domain
      ↕
T24 Integration Model
```

---

# 8. Adapter Package Structure

Recommended structure:

```text
infrastructure/
└── t24/
    ├── adapter/
    │   └── T24Adapter.java
    │
    ├── client/
    │   └── T24Client.java
    │
    ├── mapper/
    │   ├── T24CustomerMapper.java
    │   ├── T24AccountMapper.java
    │   └── T24TransferMapper.java
    │
    ├── model/
    │   ├── T24CustomerRequest.java
    │   ├── T24CustomerResponse.java
    │   ├── T24AccountResponse.java
    │   ├── T24TransferRequest.java
    │   └── T24TransferResponse.java
    │
    └── exception/
        ├── T24TimeoutException.java
        ├── T24UnavailableException.java
        └── T24BusinessException.java
```

This keeps T24-specific structures inside the infrastructure boundary.

---

# 9. T24 Client vs T24 Adapter

The two components have different responsibilities.

```text
T24Adapter
    │
    │ business-oriented translation
    ▼
T24Client
    │
    │ technical communication
    ▼
T24
```

### T24Adapter

Responsible for:

* translating domain requests
* invoking the client
* translating responses
* translating errors
* applying integration semantics

### T24Client

Responsible for:

* HTTP/network communication
* connection configuration
* authentication
* timeout configuration
* serialization/deserialization
* low-level response handling

This prevents technical communication logic from leaking into the application layer.

---

# 10. Customer Inquiry

Corevia API:

```http
GET /api/v1/customers/{customerId}
```

Application flow:

```text
REST Controller
      ↓
CustomerService
      ↓
CoreBankingGateway
      ↓
T24Adapter
      ↓
T24Client
      ↓
T24
```

Response flow:

```text
T24
 ↓
T24Client
 ↓
T24CustomerResponse
 ↓
T24CustomerMapper
 ↓
Customer
 ↓
CustomerResponse
 ↓
REST API
```

---

# 11. Customer Mapping

Conceptually:

```text
T24 Customer Model
        │
        │ Mapper
        ▼
Corevia Customer
```

Example:

```text
T24-specific:
customer identifier
customer name
customer status
       ↓
Corevia:
CustomerId
fullName
CustomerStatus
```

The domain model should not contain T24-specific field names.

---

# 12. Account Inquiry

Corevia API:

```http
GET /api/v1/accounts/{accountId}
```

Flow:

```text
REST
 ↓
AccountService
 ↓
CoreBankingGateway
 ↓
T24Adapter
 ↓
T24Client
 ↓
T24
```

The response is mapped into Corevia's:

```text
Account
```

domain object.

---

# 13. Account Balance

The account balance returned by Corevia represents information retrieved from the core banking system.

Corevia does not become the authoritative owner of the balance.

```text
T24
 │
 │ balance
 ▼
T24Adapter
 │
 ▼
Corevia Account
```

Corevia may temporarily use the value for the response, but it does not create a competing ledger.

---

# 14. Funds Transfer

Funds transfer is the most important T24 integration operation.

API:

```http
POST /api/v1/transfers
```

Flow:

```text
Digital Channel
      │
      ▼
Corevia API
      │
      ▼
TransferApplicationService
      │
      ├── Validate
      │
      ├── Check idempotency
      │
      └── Create transaction state
      │
      ▼
CoreBankingGateway
      │
      ▼
T24Adapter
      │
      ▼
T24Client
      │
      ▼
T24 / Transact
```

---

# 15. Transfer Request Mapping

External API request:

```json
{
  "sourceAccount": "1000012345",
  "destinationAccount": "2000098765",
  "amount": 1500000.00,
  "currency": "IDR",
  "reference": "PAYROLL-202609"
}
```

Corevia domain representation:

```text
Transfer
├── sourceAccount
├── destinationAccount
├── Money
├── reference
├── transactionId
├── idempotencyKey
└── correlationId
```

T24 adapter representation:

```text
T24TransferRequest
├── core account identifiers
├── amount
├── currency
├── transaction reference
└── integration metadata
```

The exact T24 field mapping is deliberately isolated inside the adapter.

---

# 16. Transfer Response Mapping

Conceptually:

```text
T24 Response
     │
     ▼
T24TransferResponse
     │
     ▼
T24TransferMapper
     │
     ▼
TransferResult
     │
     ▼
Application Service
     │
     ▼
TransferResponse
```

The external API should not expose raw T24 responses.

---

# 17. Transaction Status Inquiry

API:

```http
GET /api/v1/transfers/{transactionId}
```

This capability is especially important for resolving uncertain transactions.

Flow:

```text
Corevia
   │
   │ transactionId
   ▼
T24Adapter
   │
   ▼
T24
   │
   ├── SUCCESS
   └── FAILED
```

The result is mapped back into Corevia's transaction state.

---

# 18. The UNKNOWN Scenario

This is the most important resilience scenario.

Consider:

```text
1. Corevia creates transaction TXN-001

2. Corevia sends transfer to T24

3. T24 processes the transaction

4. Network connection fails

5. Corevia does not receive the response
```

At step 5:

```text
Corevia does NOT know whether T24 succeeded.
```

Therefore:

```text
SUBMITTED
    ↓
UNKNOWN
```

It must not automatically become:

```text
FAILED
```

---

# 19. Why Blind Retry Is Dangerous

Suppose T24 actually processed:

```text
TXN-001 → SUCCESS
```

but Corevia did not receive the response.

If Corevia blindly retries:

```text
Retry TXN-001
      ↓
T24 processes again
```

there is a risk of duplicate financial processing.

Therefore:

```text
Timeout
   ↓
UNKNOWN
   ↓
Status Inquiry
   ↓
Resolve outcome
```

rather than:

```text
Timeout
   ↓
Retry
   ↓
Potential duplicate transaction
```

---

# 20. T24 Timeout Handling

The T24 client uses a configurable timeout.

Conceptually:

```text
T24 Request
     │
     ▼
[ Timeout Window ]
     │
     ├── response → process response
     │
     └── timeout → classify outcome
```

For inquiry operations, a timeout can normally be treated as a technical failure.

For a financial transfer, the situation is more subtle.

If the request may already have reached the core banking system:

```text
Transfer timeout
       ↓
UNKNOWN
```

The application must preserve this distinction.

---

# 21. Timeout Classification

| Operation                  | Timeout behavior      |
| -------------------------- | --------------------- |
| Customer inquiry           | Technical failure     |
| Account inquiry            | Technical failure     |
| Transfer                   | Potentially `UNKNOWN` |
| Transaction status inquiry | Technical failure     |

The difference exists because a transfer can produce a financial side effect before the response is received.

---

# 22. Error Mapping

T24-specific errors must be translated into Corevia-level errors.

Conceptually:

```text
T24 Error
    │
    ▼
T24Adapter
    │
    ▼
Corevia Error
```

Example:

```text
T24:
INSUFFICIENT_FUNDS
        ↓
Corevia:
INSUFFICIENT_FUNDS
```

Another example:

```text
T24:
ACCOUNT_BLOCKED
        ↓
Corevia:
ACCOUNT_BLOCKED
```

Technical errors:

```text
T24 timeout
     ↓
Corevia
     ↓
UNKNOWN for transfer
```

---

# 23. Error Categories

The adapter should distinguish:

```text
Business Error
Technical Error
Unknown Outcome
```

### Business Error

The core banking system has definitively rejected the operation.

Examples:

```text
INSUFFICIENT_FUNDS
ACCOUNT_NOT_FOUND
ACCOUNT_BLOCKED
TRANSACTION_NOT_ALLOWED
```

Result:

```text
FAILED
```

### Technical Error

The operation could not be completed because of infrastructure problems.

Examples:

```text
connection refused
HTTP 500
service unavailable
authentication failure
```

The correct handling depends on whether a financial side effect may already have occurred.

### Unknown Outcome

The request may have been processed but the result is unavailable.

Result:

```text
UNKNOWN
```

---

# 24. Retry Policy

Retries must be operation-aware.

For inquiry:

```text
GET account
   ↓
timeout
   ↓
controlled retry may be acceptable
```

For transfer:

```text
POST transfer
   ↓
timeout
   ↓
DO NOT blindly retry
   ↓
UNKNOWN
   ↓
status inquiry
```

This distinction is a core banking reliability principle.

---

# 25. Circuit Breaker

Corevia can protect T24 using a circuit breaker.

```text
             ┌─────────┐
             │ CLOSED  │
             └────┬────┘
                  │
             repeated failures
                  │
                  ▼
             ┌─────────┐
             │  OPEN   │
             └────┬────┘
                  │
             recovery period
                  │
                  ▼
             ┌─────────┐
             │HALF-OPEN│
             └────┬────┘
                  │
             ┌────┴────┐
             ▼         ▼
          success    failure
             │         │
             ▼         ▼
          CLOSED      OPEN
```

The circuit breaker prevents Corevia from continuously sending requests to an unhealthy dependency.

---

# 26. T24 Authentication

The real integration would require the appropriate authentication mechanism supported by the target Transact deployment.

Corevia therefore abstracts authentication behind configuration.

The application must not contain:

```text
username
password
API key
client secret
private key
```

in source code.

Example configuration concept:

```yaml
t24:
  base-url: ${T24_BASE_URL}
  connect-timeout: 2s
  read-timeout: 5s
```

Secrets are supplied through the runtime environment or a secret-management solution.

---

# 27. T24 Base URL

The T24 endpoint is external configuration:

```text
T24_BASE_URL
```

Example local environment:

```text
T24_BASE_URL=http://mock-transact:8081
```

A production environment would use the actual approved core banking integration endpoint.

The application code should not hard-code environment-specific URLs.

---

# 28. Mock Transact

Because Corevia does not include a licensed Temenos environment, development uses:

```text
Mock Transact
```

The mock reproduces the integration behavior required by Corevia.

```text
Corevia
   │
   ▼
T24Adapter
   │
   ▼
T24Client
   │
   ▼
Mock Transact
```

The mock should behave like an external dependency rather than bypassing the adapter.

---

# 29. Mock Transact Capabilities

The mock should support:

### Customer

```text
GET /customers/{customerId}
```

### Account

```text
GET /accounts/{accountId}
```

### Transfer

```text
POST /transfers
```

### Transaction Status

```text
GET /transfers/{transactionId}
```

---

# 30. Mock Transact Test Scenarios

The mock should be able to simulate:

```text
SUCCESS
INSUFFICIENT_FUNDS
ACCOUNT_NOT_FOUND
ACCOUNT_BLOCKED
HTTP_500
TIMEOUT
SLOW_RESPONSE
CONNECTION_FAILURE
UNKNOWN_OUTCOME
```

This makes resilience testing reproducible.

---

# 31. Mock Transfer Example

Request:

```json
{
  "sourceAccount": "1000012345",
  "destinationAccount": "2000098765",
  "amount": 1500000.00,
  "currency": "IDR",
  "reference": "PAYROLL-202609"
}
```

Mock response:

```json
{
  "status": "SUCCESS",
  "transactionId": "T24-TXN-001"
}
```

Corevia maps this into:

```text
Corevia Transaction
TXN-20260920-000001

Status
SUCCESS
```

The external client does not need to know the T24-specific transaction identifier unless there is a specific business requirement to expose it.

---

# 32. Mock Timeout Scenario

The mock can intentionally delay the response:

```text
Corevia
   │
   │ POST transfer
   ▼
Mock Transact
   │
   │ delay
   │
   X
   │
timeout
   ▼
Corevia
```

Corevia then:

```text
SUBMITTED
    ↓
UNKNOWN
```

The test can subsequently invoke:

```http
GET /api/v1/transfers/{transactionId}
```

and resolve the transaction.

---

# 33. Mock Outage Scenario

The mock can return:

```http
503 Service Unavailable
```

Repeated failures should eventually cause:

```text
Circuit Breaker → OPEN
```

Subsequent requests fail fast until recovery.

---

# 34. T24 Transaction Identifier

The core banking system may return its own transaction identifier.

Example:

```text
T24-TXN-987654
```

Corevia should distinguish:

```text
Corevia TransactionId
        ≠
T24 TransactionId
```

For example:

```text
Corevia:
TXN-20260920-000001

T24:
T24-TXN-987654
```

This avoids coupling Corevia's public API to a specific core banking implementation.

The T24 transaction identifier may be stored as integration metadata when required for reconciliation or status inquiry.

---

# 35. System of Record

T24 / Transact remains authoritative for:

```text
Customer data
Account data
Account balances
Core financial transactions
Core banking transaction outcome
```

Corevia owns:

```text
Middleware transaction lifecycle
Idempotency state
Request fingerprint
Correlation
Integration metadata
Operational audit
```

Conceptually:

```text
             SYSTEM OF RECORD
                    │
                    ▼
             T24 / Transact
                    │
        ┌───────────┼───────────┐
        │           │           │
     Account      Balance    Financial
      Data                    Ledger
        │
        ▼
      Corevia
        │
        ├── Idempotency
        ├── Transaction State
        ├── Audit
        └── Correlation
```

---

# 36. Why Corevia Does Not Maintain a Ledger

Corevia should not attempt to reproduce the core banking ledger.

Avoid:

```text
T24 Balance = 10,000,000
Corevia Balance = 10,000,000
```

as two independent authoritative values.

Instead:

```text
T24
 │
 │ authoritative balance
 ▼
Corevia
 │
 │ transient / response usage
 ▼
Digital Channel
```

This avoids creating competing financial sources of truth.

---

# 37. Integration Sequence — Successful Transfer

```text
Client
  │
  │ POST /transfers
  ▼
Corevia API
  │
  ▼
Transfer Service
  │
  │ validate
  ▼
Idempotency Store
  │
  │ new request
  ▼
CoreBankingGateway
  │
  ▼
T24Adapter
  │
  ▼
T24Client
  │
  ▼
T24
  │
  │ SUCCESS
  ▼
T24Client
  │
  ▼
T24Adapter
  │
  ▼
Transfer Service
  │
  ▼
SUCCESS
```

---

# 38. Integration Sequence — Timeout

```text
Client
  │
  ▼
Corevia
  │
  ▼
T24Adapter
  │
  ▼
T24
  │
  │ request may have been processed
  X
  │ network timeout
  ▼
Corevia
  │
  ▼
UNKNOWN
```

Corevia does not blindly retry.

---

# 39. Integration Sequence — Resolve UNKNOWN

```text
Client
  │
  │ GET /transfers/TXN-001
  ▼
Corevia
  │
  ▼
Transaction Service
  │
  ▼
CoreBankingGateway
  │
  ▼
T24Adapter
  │
  ▼
T24
  │
  │ transaction status
  ▼
Corevia
  │
  ├── SUCCESS
  │
  └── FAILED
```

---

# 40. Integration Sequence — Duplicate Request

```text
Client
  │
  │ POST transfer
  │ Idempotency-Key: ABC
  ▼
Corevia
  │
  ▼
Idempotency Store
  │
  │ key exists
  ▼
Existing Transaction
  │
  ▼
Return Existing Result
```

T24 is not called a second time.

---

# 41. Integration Sequence — T24 Outage

```text
Client
   │
   ▼
Corevia
   │
   ▼
Circuit Breaker
   │
   ▼
T24
   │
   X
Unavailable
```

After repeated failures:

```text
Circuit Breaker
       ↓
OPEN
       ↓
Fail Fast
```

---

# 42. Mapping Responsibilities

Mapping should be explicit.

```text
API DTO
   ↓
Domain
   ↓
T24 Integration DTO
```

and:

```text
T24 Integration DTO
   ↓
Domain
   ↓
API DTO
```

Example:

```text
TransferRequest
       ↓
Transfer
       ↓
T24TransferRequest
```

Response:

```text
T24TransferResponse
       ↓
TransferResult
       ↓
TransferResponse
```

---

# 43. Avoiding Leaky Abstractions

The following should not appear in the domain:

```text
T24Client
T24Response
HttpStatus
WebClient
RestClient
JSON field names
T24-specific error codes
```

Instead:

```text
Domain
   ↓
CoreBankingGateway
```

Infrastructure owns the implementation details.

---

# 44. Future Core Banking Adapter

The architecture allows another implementation:

```text
                CoreBankingGateway
                       │
          ┌────────────┼────────────┐
          │            │            │
          ▼            ▼            ▼
      T24Adapter   MockAdapter   FutureAdapter
```

For example:

```text
T24
Temenos Transact
Other Core Banking Platform
Mock
```

The business/application layer remains unchanged.

This is one of the main benefits of the ports-and-adapters architecture.

---

# 45. Production Considerations

A real production integration would require additional concerns depending on the actual bank and Transact deployment, including:

* approved integration protocols
* network segmentation
* mTLS where required
* service authentication
* certificate management
* secret management
* connection pooling
* timeout tuning
* rate limiting
* monitoring
* distributed tracing
* operational alerting
* reconciliation
* disaster recovery
* high availability
* deployment topology
* regulatory controls
* audit requirements

These are deployment-specific and are intentionally outside the assumptions of this portfolio project.

---

# 46. Security Boundary

The T24 adapter must never expose credentials to the domain.

```text
Domain
   │
   │ no credentials
   ▼
Application
   │
   ▼
T24Adapter
   │
   ▼
T24Client
   │
   │ credentials / certificates
   ▼
Secret Management
```

Secrets should be injected through secure runtime configuration.

---

# 47. Observability

Every T24 interaction should be observable.

At minimum:

```text
Correlation ID
Transaction ID
Operation
Target system
Latency
Outcome
Error category
```

Example structured log:

```json
{
  "correlationId": "CORR-001",
  "transactionId": "TXN-001",
  "operation": "TRANSFER",
  "dependency": "T24",
  "durationMs": 238,
  "outcome": "SUCCESS"
}
```

Sensitive information must not be logged.

---

# 48. Metrics

Useful integration metrics include:

```text
corevia_t24_requests_total
corevia_t24_errors_total
corevia_t24_timeouts_total
corevia_t24_request_duration
corevia_transfer_success_total
corevia_transfer_failed_total
corevia_transfer_unknown_total
```

These metrics allow operators to distinguish:

```text
Business failures
```

from:

```text
Integration failures
```

---

# 49. Testing the Adapter

The adapter should be tested independently from the rest of the application.

Tests should cover:

### Customer

```text
successful inquiry
customer not found
T24 unavailable
timeout
```

### Account

```text
successful inquiry
account not found
account blocked
timeout
```

### Transfer

```text
successful transfer
insufficient funds
account blocked
T24 error
timeout
unknown outcome
```

### Transaction Status

```text
SUCCESS
FAILED
NOT_FOUND
T24 timeout
T24 unavailable
```

---

# 50. Contract Testing

The Mock Transact API should provide a stable contract for Corevia.

Conceptually:

```text
Corevia T24 Adapter
        │
        │ expected contract
        ▼
Mock Transact
```

Contract tests verify:

* request structure
* response structure
* required fields
* error behavior
* status codes

This helps prevent accidental divergence between the adapter and mock service.

---

# 51. Integration Configuration

Example configuration:

```yaml
t24:
  base-url: ${T24_BASE_URL}
  connect-timeout: 2s
  read-timeout: 5s
  retry:
    enabled: false
  circuit-breaker:
    failure-rate-threshold: 50
    wait-duration: 10s
```

The exact values are implementation defaults for the portfolio project, not production banking recommendations.

They should be configurable per environment.

---

# 52. Configuration Principles

Configuration must be:

* externalized
* environment-specific
* non-secret where possible
* secret-managed where necessary
* observable
* testable

Avoid:

```java
private static final String T24_URL =
    "http://production-t24...";
```

Prefer:

```text
Environment
    ↓
Configuration
    ↓
T24Client
```

---

# 53. T24 Integration Package

Final recommended structure:

```text
infrastructure/
└── t24/
    │
    ├── adapter/
    │   └── T24Adapter.java
    │
    ├── client/
    │   └── T24Client.java
    │
    ├── mapper/
    │   ├── T24CustomerMapper.java
    │   ├── T24AccountMapper.java
    │   ├── T24TransferMapper.java
    │   └── T24TransactionMapper.java
    │
    ├── model/
    │   ├── customer/
    │   ├── account/
    │   ├── transfer/
    │   └── transaction/
    │
    ├── exception/
    │   ├── T24TimeoutException.java
    │   ├── T24UnavailableException.java
    │   └── T24BusinessException.java
    │
    └── configuration/
        └── T24Configuration.java
```

---

# 54. Architecture Summary

The complete integration boundary is:

```text
┌─────────────────────────────────────────────┐
│                  Corevia                    │
│                                             │
│  REST API                                   │
│      │                                      │
│      ▼                                      │
│  Application Service                        │
│      │                                      │
│      ▼                                      │
│  Domain                                     │
│      │                                      │
│      ▼                                      │
│  CoreBankingGateway                         │
│      │                                      │
└──────┼──────────────────────────────────────┘
       │
       │ Adapter Boundary
       ▼
┌─────────────────────────────────────────────┐
│              T24 Adapter                    │
│                                             │
│ Mapping                                     │
│ Error Translation                           │
│ Timeout Handling                            │
│ Integration Semantics                       │
└──────────────────┬──────────────────────────┘
                   │
                   ▼
┌─────────────────────────────────────────────┐
│             T24 / Transact                 │
│                                             │
│ System of Record                            │
│                                             │
│ Customer / Account / Balance / Transactions │
└─────────────────────────────────────────────┘
```

The most important architectural rule is:

> **Corevia knows the banking capability it needs; the T24 adapter knows how to implement that capability against the core banking system.**

---

