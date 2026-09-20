# Corevia — Testing Strategy

## 1. Purpose

This document defines the testing strategy for Corevia.

Corevia is a banking integration middleware, so testing must cover more than ordinary business logic.

The test strategy must verify:

* business rules;
* API contracts;
* persistence;
* T24 integration;
* Kafka events;
* idempotency;
* concurrency;
* timeout handling;
* `UNKNOWN` transaction states;
* error classification;
* security;
* observability;
* resilience.

The most important principle is:

> **A successful test suite must demonstrate that Corevia does not accidentally duplicate, lose, or incorrectly report financial transactions when dependencies fail.**

---

# 2. Testing Pyramid

Corevia follows a layered testing strategy.

```text
                         +------------------+
                         |  E2E / System    |
                         +--------+---------+
                                  |
                         +--------+---------+
                         | Contract /       |
                         | Integration      |
                         +--------+---------+
                                  |
                    +-------------+-------------+
                    |                           |
             +------+-------+            +------+-------+
             | Application  |            | Persistence  |
             +------+-------+            +------+-------+
                    |                           |
                    +-------------+-------------+
                                  |
                         +--------+---------+
                         |    Unit Tests    |
                         +------------------+
```

The majority of tests should remain fast unit tests.

Integration and end-to-end tests should be fewer but cover critical boundaries.

---

# 3. Test Levels

| Level       | Purpose                         | Typical Tools                 |
| ----------- | ------------------------------- | ----------------------------- |
| Unit        | Business logic and domain rules | JUnit 5                       |
| Application | Service orchestration           | JUnit 5 + Mockito             |
| Repository  | Database behavior               | Testcontainers PostgreSQL     |
| Integration | Multiple components             | Spring Boot Test              |
| Contract    | T24/API integration contracts   | WireMock / MockWebServer      |
| Kafka       | Event publication/consumption   | Testcontainers Kafka          |
| Security    | Authentication/authorization    | Spring Security Test          |
| Resilience  | Timeout/failure behavior        | WireMock + Testcontainers     |
| E2E         | Complete transaction flow       | Docker Compose/Testcontainers |

---

# 4. Testing Principles

Corevia testing follows these principles:

### 4.1 Test behavior, not implementation

Tests should verify business behavior rather than private implementation details.

### 4.2 Deterministic tests

Tests should produce the same result repeatedly.

### 4.3 Independent tests

One test should not depend on another test's execution order.

### 4.4 Realistic integration tests

Critical infrastructure behavior should be tested against realistic dependencies where practical.

### 4.5 Failure-first testing

Financial integration failures must be tested explicitly.

### 4.6 Concurrency matters

Idempotency and duplicate prevention must be tested concurrently, not only sequentially.

---

# 5. Test Technology Stack

Recommended baseline:

```text
Java 25
Spring Boot
JUnit 5
Mockito
AssertJ
Spring Boot Test
Spring Security Test
Testcontainers
WireMock
MockMvc
Kafka test infrastructure
PostgreSQL Testcontainer
```

Maven is used as the build system.

---

# 6. Maven Test Structure

Recommended structure:

```text
src
├── main
│   └── java
│
└── test
    ├── java
    │   └── com.corevia
    │       ├── domain
    │       ├── application
    │       ├── api
    │       ├── infrastructure
    │       └── integration
    │
    └── resources
        ├── application-test.yml
        ├── fixtures
        └── wiremock
```

Optional:

```text
src
└── test
    └── resources
        └── contracts
```

---

# 7. Unit Testing

Unit tests should cover domain behavior without requiring:

* PostgreSQL;
* Kafka;
* HTTP;
* T24;
* Docker;
* external services.

Example:

```java
@Test
void shouldRejectZeroAmount() {
    assertThatThrownBy(() ->
        Money.of(BigDecimal.ZERO, CurrencyCode.IDR)
    )
    .isInstanceOf(IllegalArgumentException.class);
}
```

Unit tests should be fast enough to execute thousands of cases during normal development.

---

# 8. Domain Tests

Important domain objects to test:

```text
CustomerId
AccountId
TransactionId
IdempotencyKey
CorrelationId
Money
CurrencyCode
Reference
Transfer
Transaction
```

---

# 9. Money Tests

Money is particularly important because financial calculations must not use floating-point arithmetic.

Test:

* positive amount;
* zero amount;
* negative amount;
* decimal precision;
* currency association;
* equality;
* comparison.

Example:

```text
1000000.00 IDR
```

must not unexpectedly become:

```text
999999.999999 IDR
```

---

# 10. Transfer Validation Tests

Test invariants such as:

```text
source != destination
amount > 0
currency supported
reference present
idempotency key present
```

Example:

```text
sourceAccount = 1000012345
destinationAccount = 1000012345
```

Expected:

```text
validation failure
```

---

# 11. Transaction State Tests

The transaction lifecycle is:

```text
RECEIVED
    |
    v
VALIDATING
    |
    v
SUBMITTED
    |
    +-------> SUCCESS
    |
    +-------> FAILED
    |
    +-------> UNKNOWN
                  |
                  +----> SUCCESS
                  |
                  +----> FAILED
```

Tests must verify every valid transition.

---

# 12. Invalid State Transition Tests

Examples:

```text
SUCCESS -> RECEIVED
SUCCESS -> SUBMITTED
FAILED  -> SUBMITTED
FAILED  -> SUCCESS
```

must be rejected.

Similarly:

```text
UNKNOWN -> RECEIVED
UNKNOWN -> SUBMITTED
```

should not be allowed.

`UNKNOWN` can only be resolved through the defined resolution process.

---

# 13. UNKNOWN State Tests

This is one of Corevia's most important test areas.

Scenario:

```text
Corevia
   |
   | transfer
   v
T24
   |
   | transaction succeeds
   |
   X response lost
```

Expected Corevia state:

```text
UNKNOWN
```

Not:

```text
FAILED
```

and not:

```text
SUCCESS
```

unless the result is known.

---

# 14. UNKNOWN Resolution Tests

After an UNKNOWN result:

```text
UNKNOWN
   |
   | status inquiry
   v
T24
```

If T24 reports success:

```text
UNKNOWN -> SUCCESS
```

If T24 reports failure:

```text
UNKNOWN -> FAILED
```

Tests must verify both paths.

---

# 15. No Blind Retry Test

A critical resilience test:

```text
T24 transfer
      |
      X timeout
      |
      v
Corevia = UNKNOWN
```

Corevia must **not** automatically execute:

```text
transfer()
transfer()
```

because the first transfer may already have succeeded.

The test should verify the number of transfer calls.

Expected:

```text
transfer calls = 1
status inquiry calls = 1+
```

rather than:

```text
transfer calls = 2
```

---

# 16. Idempotency Tests

Test:

### Same key + same request

```text
Idempotency-Key: ABC
request = X
```

Repeated:

```text
Idempotency-Key: ABC
request = X
```

Expected:

```text
same transaction
same logical operation
```

No duplicate T24 transfer.

---

# 17. Idempotency Conflict Test

Same key:

```text
ABC
```

but different payload:

```text
Request 1:
source = A
destination = B
amount = 100

Request 2:
source = A
destination = C
amount = 500
```

Expected:

```text
409 Conflict
```

or the status defined by the API contract.

The second request must not create another transfer.

---

# 18. Idempotency Concurrency Test

This test is critical.

Two requests arrive simultaneously:

```text
Request A ----\
               \
                Corevia
               /
Request B ----/
```

Both use:

```text
Idempotency-Key: ABC
```

Expected:

```text
one transaction
one T24 transfer
one logical result
```

The test must verify this under actual concurrent execution.

---

# 19. Database Constraint Test

The database should enforce uniqueness as a final protection layer.

For example:

```sql
UNIQUE (idempotency_key)
```

Test that concurrent inserts cannot create two records with the same key.

Application-level checks alone are insufficient.

---

# 20. Request Fingerprint Tests

Test that the request fingerprint is deterministic.

Example:

```text
Request A
   |
   v
SHA-256
   |
   v
Fingerprint X
```

The same canonical request should generate the same fingerprint.

Different business requests should produce different fingerprints.

---

# 21. Canonicalization Tests

Equivalent JSON formatting should not create different fingerprints.

For example:

```json
{
  "amount": 100,
  "currency": "IDR"
}
```

and:

```json
{
  "currency": "IDR",
  "amount": 100
}
```

should produce the same logical fingerprint if they represent the same canonical request.

The implementation should define exactly which fields participate in the fingerprint.

---

# 22. Application Service Tests

The application service is responsible for orchestration.

Typical flow:

```text
API
 |
 v
TransferService
 |
 +--> validate
 |
 +--> idempotency
 |
 +--> persist transaction
 |
 +--> T24
 |
 +--> update state
 |
 +--> publish/outbox event
```

Tests should verify orchestration behavior without requiring a real T24 system.

---

# 23. T24 Adapter Tests

The T24 adapter translates between Corevia and T24 models.

Test:

```text
Corevia request
      |
      v
T24 request
```

and:

```text
T24 response
      |
      v
Corevia result
```

Test mappings for:

* successful transfer;
* insufficient funds;
* account not found;
* account blocked;
* timeout;
* HTTP 500;
* connection failure;
* unknown outcome.

---

# 24. T24 Contract Tests

The adapter depends on a specific external contract.

The test should verify that Corevia sends the expected request structure.

Example:

```json
{
  "debitAccount": "1000012345",
  "creditAccount": "2000098765",
  "amount": 1500000,
  "currency": "IDR"
}
```

and correctly interprets the response.

WireMock or an equivalent HTTP mock is suitable for these tests.

---

# 25. Mock T24

Corevia includes a Mock T24/Transact service for development and testing.

The mock should support deterministic scenarios:

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

This allows the test suite to reproduce failure conditions consistently.

---

# 26. Mock T24 Behavior

Example:

```text
POST /mock-t24/transfers
```

Scenario:

```text
X-Test-Scenario: TIMEOUT
```

Mock behavior:

```text
accept request
delay response
exceed Corevia timeout
```

Expected:

```text
Corevia -> UNKNOWN
```

The exact mechanism is test-only and should not become a production dependency.

---

# 27. Repository Tests

Repository behavior should be tested against PostgreSQL.

Use:

```text
Testcontainers PostgreSQL
```

rather than relying exclusively on an in-memory database.

Reason:

```text
H2 != PostgreSQL
```

Database-specific behavior such as:

* `JSONB`;
* indexes;
* constraints;
* PostgreSQL SQL;
* transaction behavior

should be tested against PostgreSQL itself.

---

# 28. PostgreSQL Testcontainer

Conceptually:

```java
@Testcontainers
class TransactionRepositoryIT {

    @Container
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:18");
}
```

The exact PostgreSQL version should match the project's supported runtime version.

---

# 29. Database Migration Tests

Flyway migrations should be executed during integration tests.

Test:

```text
empty database
     |
     v
Flyway
     |
     v
expected schema
```

Verify:

* tables exist;
* constraints exist;
* indexes exist;
* unique constraints work;
* migrations execute successfully.

---

# 30. Persistence Tests

Test transaction persistence for:

```text
RECEIVED
VALIDATING
SUBMITTED
SUCCESS
FAILED
UNKNOWN
```

Verify:

* status;
* timestamps;
* transaction ID;
* idempotency key;
* correlation ID;
* T24 transaction ID;
* error information.

---

# 31. Outbox Tests

The outbox pattern requires testing the atomic relationship between:

```text
business state
+
outbox event
```

Example:

```text
Transaction SUCCESS
       |
       +--> database transaction
              |
              +--> transaction = SUCCESS
              |
              +--> outbox event = TransferSucceeded
```

Both should commit together.

---

# 32. Outbox Atomicity Test

Test successful transaction:

```text
BEGIN
 |
 +--> update transaction SUCCESS
 |
 +--> insert outbox event
 |
COMMIT
```

Expected:

```text
transaction exists
outbox event exists
```

If the database transaction rolls back:

```text
ROLLBACK
```

Expected:

```text
transaction update absent
outbox event absent
```

---

# 33. Outbox Publication Failure

Scenario:

```text
Database
   |
   | commit
   v
Outbox = pending
   |
   X Kafka unavailable
```

Expected:

```text
transaction remains successful
outbox remains pending
```

The financial transaction must not be rolled back simply because Kafka is temporarily unavailable.

---

# 34. Duplicate Event Publication

Kafka may receive duplicate events.

Example:

```text
TransferSucceeded
       |
       +--> event #1
       |
       +--> event #1 again
```

Consumers must remain idempotent.

Test that processing the same `eventId` twice does not create duplicate side effects.

---

# 35. Event Ordering Tests

Events for the same transaction should preserve the intended lifecycle ordering.

Example:

```text
TransferSubmitted
        |
        v
TransferSucceeded
```

The Kafka partition key should be:

```text
transactionId
```

Tests should verify the producer uses the correct key.

---

# 36. Event Schema Tests

Verify mandatory envelope fields:

```text
eventId
eventType
eventVersion
occurredAt
producer
correlationId
transactionId
payload
```

Missing mandatory fields should fail validation.

---

# 37. Event Versioning Tests

Example:

```text
TransferSucceeded v1
TransferSucceeded v2
```

Consumers should remain compatible according to the defined compatibility policy.

The test suite should verify that supported older event versions can still be consumed where backward compatibility is required.

---

# 38. API Tests

Use Spring MockMvc or WebTestClient to test the REST layer.

Examples:

```text
POST /api/v1/transfers
GET /api/v1/transfers/{id}
GET /api/v1/accounts/{id}
GET /api/v1/customers/{id}
```

Test:

* HTTP status;
* request validation;
* response schema;
* headers;
* correlation ID;
* error format.

---

# 39. Transfer API Happy Path

Example:

```text
POST /api/v1/transfers
```

with:

```json
{
  "sourceAccount": "1000012345",
  "destinationAccount": "2000098765",
  "amount": 1500000,
  "currency": "IDR",
  "reference": "PAYROLL-202609"
}
```

Expected:

```text
HTTP 201 / 200
transactionId returned
status = SUCCESS
```

depending on the final API contract.

---

# 40. Transfer API Validation Tests

Test:

```text
missing sourceAccount
missing destinationAccount
missing amount
negative amount
zero amount
unsupported currency
empty reference
invalid account format
missing Idempotency-Key
```

Expected:

```text
4xx
```

with the standardized Corevia error response.

---

# 41. Error Contract Tests

Every error response should follow a consistent structure.

Example:

```json
{
  "code": "INSUFFICIENT_FUNDS",
  "message": "The source account does not have sufficient funds",
  "correlationId": "CORR-001"
}
```

The API must not leak:

```text
T24 stack trace
database exception
Java class name
internal hostname
secret
```

---

# 42. Security Tests

Security tests should verify:

### Missing JWT

```text
401
```

### Expired JWT

```text
401
```

### Invalid JWT

```text
401
```

### Missing scope

```text
403
```

### Correct scope

```text
request allowed
```

---

# 43. Authorization Matrix Tests

Create a test matrix:

| Endpoint          | No Token | Read Scope | Write Scope |
| ----------------- | -------: | ---------: | ----------: |
| Customer inquiry  |      401 |   allowed* |    allowed* |
| Account inquiry   |      401 |   allowed* |    allowed* |
| Transfer creation |      401 |        403 |     allowed |
| Transfer status   |      401 |    allowed |     allowed |

`*` subject to the final scope definition.

---

# 44. Resilience Tests

Corevia must explicitly test dependency failures.

Test:

```text
T24 timeout
T24 HTTP 500
T24 connection failure
T24 slow response
Kafka unavailable
PostgreSQL unavailable
```

The expected outcome must be defined for each scenario.

---

# 45. T24 Failure Matrix

| T24 Scenario                                   | Expected Transaction State       |
| ---------------------------------------------- | -------------------------------- |
| Success                                        | `SUCCESS`                        |
| Insufficient funds                             | `FAILED`                         |
| Account not found                              | `FAILED`                         |
| Account blocked                                | `FAILED`                         |
| HTTP 500 before side effect is known           | depends on integration semantics |
| Timeout after submission                       | `UNKNOWN`                        |
| Connection failure before request transmission | potentially `FAILED`             |
| Response lost after successful processing      | `UNKNOWN`                        |
| Status inquiry confirms success                | `SUCCESS`                        |
| Status inquiry confirms failure                | `FAILED`                         |

The critical distinction is whether Corevia can determine whether T24 may have processed the transaction.

---

# 46. Timeout Tests

Configure a test timeout:

```text
Corevia T24 timeout = 2 seconds
```

Mock T24:

```text
response delay = 5 seconds
```

Expected:

```text
Corevia timeout
       |
       v
UNKNOWN
```

The test should also verify:

```text
no automatic duplicate transfer
```

---

# 47. Circuit Breaker Tests

If a circuit breaker is implemented:

```text
CLOSED
   |
   | repeated failures
   v
OPEN
   |
   | wait
   v
HALF_OPEN
   |
   +--> success -> CLOSED
   |
   +--> failure -> OPEN
```

Tests should verify each state transition.

The circuit breaker should protect Corevia from repeatedly overwhelming an unavailable T24 service.

---

# 48. Retry Tests

Retries must be classified by operation.

### Safe retry candidates

Examples:

```text
customer inquiry
account inquiry
status inquiry
```

### Potentially unsafe retry

```text
funds transfer
```

after an ambiguous timeout.

Tests must verify that transfer operations are not automatically retried when the outcome is unknown.

---

# 49. Concurrency Testing

Concurrency is especially important for:

* idempotency;
* transaction creation;
* duplicate requests;
* state transitions.

Example:

```text
Thread 1 ----\
              \
               Idempotency Key ABC
              /
Thread 2 ----/
```

Expected:

```text
one transaction
one T24 transfer
```

---

# 50. Race Condition Test

A useful test intentionally creates a race:

```text
Thread A:
check idempotency

Thread B:
check idempotency

Thread A:
insert

Thread B:
insert
```

The database unique constraint must prevent duplicate records.

The application should correctly handle the resulting constraint conflict.

---

# 51. Transaction Boundary Tests

The application must not hold a database transaction open while waiting for T24.

Bad:

```text
BEGIN DB TRANSACTION
       |
       v
CALL T24
       |
       | 5 seconds
       v
COMMIT
```

Preferred:

```text
short DB transaction
       |
       v
persist state
       |
       v
T24 call
       |
       v
short DB transaction
       |
       v
persist result
```

Tests should verify the application's transaction boundaries.

---

# 52. Integration Test

A representative integration test should execute:

```text
HTTP
 |
 v
Controller
 |
 v
Application Service
 |
 v
PostgreSQL
 |
 v
T24 Mock
 |
 v
PostgreSQL
 |
 v
Outbox
```

This validates that the main components work together.

---

# 53. Full Transfer Integration Scenario

Example:

```text
1. POST /transfers
2. authenticate JWT
3. validate request
4. create transaction
5. persist idempotency
6. call Mock T24
7. receive success
8. persist SUCCESS
9. create outbox event
10. return response
```

Assertions:

```text
transaction.status == SUCCESS
idempotency.transactionId exists
T24 transaction ID exists
outbox event exists
```

---

# 54. UNKNOWN Integration Scenario

Example:

```text
1. POST /transfers
2. T24 accepts transaction
3. T24 response is lost
4. Corevia reaches timeout
5. transaction becomes UNKNOWN
6. client requests transaction status
7. Corevia performs T24 status inquiry
8. T24 reports SUCCESS
9. transaction becomes SUCCESS
```

Assertions:

```text
T24 transfer calls = 1
T24 status calls >= 1
final state = SUCCESS
```

This should be one of the showcase integration tests.

---

# 55. Duplicate Request Integration Scenario

```text
Request A
Idempotency-Key = ABC
       |
       v
Corevia
       |
       v
T24
```

Then:

```text
Request B
Idempotency-Key = ABC
       |
       v
Corevia
```

Expected:

```text
same transactionId
no second T24 transfer
```

---

# 56. T24 Business Failure Integration Scenario

Example:

```text
T24
 |
 +--> INSUFFICIENT_FUNDS
```

Expected:

```text
transaction = FAILED
errorCode = INSUFFICIENT_FUNDS
```

No UNKNOWN state is required because the business outcome is known.

---

# 57. API-to-T24 Contract Test

The complete mapping should be tested:

```text
API Request
    |
    v
Domain Transfer
    |
    v
T24 Request
```

and back:

```text
T24 Response
    |
    v
Domain Result
    |
    v
API Response
```

This protects the adapter boundary from accidental changes.

---

# 58. Consumer Tests

Kafka consumers should be tested for:

* valid event;
* duplicate event;
* unknown event version;
* malformed event;
* missing event ID;
* processing failure;
* retry;
* dead-letter behavior.

---

# 59. Dead-Letter Tests

If an event cannot be processed after the configured retry policy:

```text
Kafka
  |
  v
Consumer
  |
  +--> retry
  |
  +--> retry
  |
  +--> retry
  |
  v
Dead Letter Topic
```

The test should verify that the event is not silently lost.

---

# 60. Observability Tests

Important operational information should be available.

Test that a transfer generates logs/metrics containing:

```text
transactionId
correlationId
operation
outcome
latency
```

Sensitive values must not appear.

For example:

```text
sourceAccount=******2345
```

rather than:

```text
sourceAccount=1000012345
```

where masking is required.

---

# 61. Test Data

Test data should be deterministic.

Example accounts:

```text
1000012345
2000098765
3000012345
```

Example scenarios:

```text
PAYROLL-202609
TEST-SUCCESS
TEST-TIMEOUT
TEST-INSUFFICIENT
```

Do not use real customer or account data.

---

# 62. Test Fixtures

Fixtures can be stored under:

```text
src/test/resources/fixtures
```

Example:

```text
fixtures/
├── customer.json
├── account.json
├── transfer-success.json
├── transfer-failed.json
├── transfer-unknown.json
└── t24/
    ├── success.json
    ├── insufficient-funds.json
    └── account-not-found.json
```

Fixtures should remain synthetic.

---

# 63. Test Naming

Use behavior-oriented names.

Good:

```java
shouldReturnExistingTransactionForRepeatedIdempotencyKey()
```

Good:

```java
shouldMoveTransactionToUnknownWhenT24TransferTimesOut()
```

Good:

```java
shouldNotRetryTransferWhenOutcomeIsUnknown()
```

Avoid:

```java
testTransfer1()
```

---

# 64. Arrange / Act / Assert

Tests should generally follow:

```text
Arrange
   |
   v
Act
   |
   v
Assert
```

Example:

```java
// Arrange
given(t24.transfer(request))
    .willThrow(new T24TimeoutException());

// Act
TransferResult result =
    service.transfer(command);

// Assert
assertThat(result.status())
    .isEqualTo(TransactionStatus.UNKNOWN);
```

---

# 65. Mockito Usage

Mockito is appropriate for unit tests when testing application orchestration.

Example:

```java
@Mock
CoreBankingGateway coreBankingGateway;

@Mock
TransactionRepository transactionRepository;
```

However, Mockito should not replace integration testing.

A mocked PostgreSQL repository cannot prove that:

```text
unique constraints
transactions
JSONB
indexes
```

actually work.

---

# 66. Testcontainers

Testcontainers should be used for infrastructure-dependent tests.

Recommended containers:

```text
PostgreSQL
Kafka
Mock T24 / WireMock
```

Conceptually:

```text
+-------------------+
| Integration Test  |
+---------+---------+
          |
   +------+------+------+
   |             |      |
   v             v      v
PostgreSQL     Kafka   WireMock
                       |
                       v
                    Mock T24
```

---

# 67. Container Lifecycle

Containers should be:

* isolated per test suite;
* reproducible;
* automatically started/stopped;
* configured from test properties.

The test environment should not depend on manually running infrastructure.

---

# 68. CI Pipeline

Recommended pipeline:

```text
Commit
  |
  v
Compile
  |
  v
Unit Tests
  |
  v
Static Analysis
  |
  v
Integration Tests
  |
  v
Security Scan
  |
  v
Container Build
  |
  v
Container Scan
  |
  v
Package
```

A pull request should fail if critical tests fail.

---

# 69. Test Execution Strategy

### Fast feedback

Every code change:

```text
unit tests
```

### Pull request

```text
unit
+
application
+
API
+
repository
+
integration
```

### Main branch

```text
full test suite
+
security scanning
+
container scanning
```

### Pre-release

```text
full integration
+
resilience
+
E2E
```

---

# 70. Test Tags

JUnit tags can separate test categories.

Example:

```java
@Tag("unit")
```

```java
@Tag("integration")
```

```java
@Tag("e2e")
```

```java
@Tag("security")
```

This allows Maven CI stages to execute appropriate test groups.

---

# 71. Coverage

Code coverage is useful but should not be the primary success criterion.

The important areas should have strong behavioral coverage:

```text
domain invariants
transaction state machine
idempotency
T24 adapter
error classification
UNKNOWN handling
outbox
security
```

A high percentage of trivial getters does not prove financial correctness.

---

# 72. Mutation Testing

Mutation testing may optionally be introduced using a tool such as PIT.

It can help determine whether tests actually detect changes to business rules.

Example:

```text
Original:
amount > 0

Mutated:
amount >= 0
```

A strong test suite should detect the mutation.

---

# 73. Contract Compatibility

API and event contracts should be treated as compatibility boundaries.

Tests should detect accidental changes to:

```text
JSON field
field type
required/optional status
enum value
HTTP status
event version
```

This is especially important because external clients and Kafka consumers may evolve independently.

---

# 74. Regression Testing

Every production-like defect should result in a regression test.

Example:

```text
Bug:
duplicate transfer after timeout
```

Add:

```text
shouldNotRetryTransferWhenT24OutcomeIsUnknown()
```

The regression test should remain permanently.

---

# 75. Critical Test Matrix

| Scenario                              | Expected Result                               |
| ------------------------------------- | --------------------------------------------- |
| Valid transfer                        | `SUCCESS`                                     |
| Invalid amount                        | `400` / validation error                      |
| Same idempotency key + same request   | same transaction                              |
| Same key + different request          | conflict                                      |
| Insufficient funds                    | `FAILED`                                      |
| Account not found                     | `FAILED`                                      |
| Account blocked                       | `FAILED`                                      |
| T24 timeout after possible submission | `UNKNOWN`                                     |
| Lost T24 response                     | `UNKNOWN`                                     |
| UNKNOWN + status success              | `SUCCESS`                                     |
| UNKNOWN + status failure              | `FAILED`                                      |
| UNKNOWN + automatic blind retry       | **must not happen**                           |
| Kafka unavailable                     | transaction remains committed; outbox pending |
| Duplicate Kafka event                 | consumer remains idempotent                   |
| Missing JWT                           | `401`                                         |
| Insufficient scope                    | `403`                                         |
| SQL injection attempt                 | rejected/safely handled                       |
| Duplicate concurrent request          | one transaction                               |

---

# 76. Final Testing Philosophy

Corevia should be tested as a **financial integration system**, not merely as a Spring Boot REST application.

The most important questions are:

```text
Can a valid transfer succeed?

Can an invalid transfer be rejected?

Can the same request safely be retried?

Can two concurrent requests accidentally create two transfers?

What happens when T24 times out?

Can Corevia distinguish FAILED from UNKNOWN?

Can UNKNOWN be resolved safely?

Can Kafka fail without reversing a financial transaction?

Can unauthorized callers access the transfer API?

Can infrastructure failures be reproduced deterministically?
```

If the test suite can answer these questions reliably, it demonstrates that Corevia's architecture is not merely documented—it is **behaviorally verified**.
