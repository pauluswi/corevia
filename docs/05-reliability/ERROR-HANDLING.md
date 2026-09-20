# Corevia — Error Handling

## 1. Purpose

This document defines how Corevia handles business errors, technical failures, dependency failures, timeouts, and uncertain transaction outcomes.

The primary objective is to ensure that Corevia:

* does not confuse technical failure with business failure;
* does not blindly retry financial transactions;
* preserves transaction state consistently;
* prevents duplicate financial operations;
* exposes actionable errors to API consumers;
* maintains sufficient information for investigation and reconciliation;
* protects sensitive banking information in logs and responses.

The most important principle is:

> **A failed request is not necessarily a failed financial transaction.**

A request can fail technically while the underlying T24 transaction has already been processed.

---

# 2. Error Classification

Corevia classifies errors into three major categories:

| Category          | Meaning                                                        | Example                           | Transaction Handling            |
| ----------------- | -------------------------------------------------------------- | --------------------------------- | ------------------------------- |
| Business Error    | T24 received and understood the request but rejected it        | Insufficient funds                | `FAILED`                        |
| Technical Failure | Communication or infrastructure failure                        | HTTP 500, connection refused      | Depends on possible side effect |
| Unknown Outcome   | Corevia cannot determine whether T24 processed the transaction | Transfer timeout after submission | `UNKNOWN`                       |

This distinction is fundamental to financial transaction processing.

---

# 3. Business Errors

A business error means the request reached the banking system and was rejected according to business rules.

Examples:

* insufficient funds;
* source account does not exist;
* destination account does not exist;
* account is blocked;
* transaction is not permitted;
* currency is unsupported;
* transaction limit exceeded.

These errors normally produce a deterministic outcome.

### Example

```text
Corevia
   |
   | Transfer 1,500,000 IDR
   v
T24
   |
   |-- insufficient funds
   |
   v
FAILED
```

The transaction can safely be represented as:

```text
TransactionStatus = FAILED
```

The client may receive an HTTP `422 Unprocessable Entity` or another API-level error defined by the API contract.

---

# 4. Technical Failures

Technical failures occur when Corevia cannot successfully communicate with T24 or another infrastructure component.

Examples:

* connection refused;
* DNS failure;
* connection reset;
* HTTP 500;
* HTTP 502/503/504;
* authentication failure;
* network failure;
* serialization failure;
* dependency unavailable.

Technical failure does **not automatically mean that the financial transaction failed**.

For example:

```text
Corevia
   |
   | Transfer
   v
T24
   |
   | transaction processed successfully
   |
   X  response lost
   |
Corevia
```

Corevia knows that communication failed, but it does not know whether T24 processed the transaction.

Therefore:

```text
Technical failure != FAILED transaction
```

The transaction outcome must be evaluated based on whether a side effect could have occurred.

---

# 5. Unknown Transaction Outcome

`UNKNOWN` is used when Corevia cannot determine whether a financial transaction was successfully processed.

This is particularly important for transfer operations.

Example:

```text
Corevia
   |
   | POST transfer
   v
T24
   |
   | debit + credit
   |
   | SUCCESS
   |
   X response lost
   |
Corevia
```

Corevia receives a timeout.

It cannot safely conclude:

```text
FAILED
```

because T24 may already have completed the transaction.

It also cannot safely conclude:

```text
SUCCESS
```

because Corevia did not receive confirmation.

Therefore:

```text
UNKNOWN
```

is the correct state.

---

# 6. Transaction State Model

Corevia uses the following lifecycle:

```text
RECEIVED
    |
    v
VALIDATING
    |
    v
SUBMITTED
    |
    +------------+
    |            |
    v            v
 SUCCESS       FAILED
    ^
    |
    |
 UNKNOWN
    |
    +-------> SUCCESS
    |
    +-------> FAILED
```

A more explicit representation:

```text
RECEIVED
   |
   v
VALIDATING
   |
   v
SUBMITTED
   |
   +---- business rejection ----> FAILED
   |
   +---- confirmed success -----> SUCCESS
   |
   +---- uncertain outcome -----> UNKNOWN
                                      |
                                      +--> SUCCESS
                                      |
                                      +--> FAILED
```

`UNKNOWN` is therefore **not a permanent failure state**.

It represents unresolved transaction outcome.

---

# 7. Error Decision Matrix

| Situation                                  |    Side Effect Possible? | Result                          |
| ------------------------------------------ | -----------------------: | ------------------------------- |
| Invalid request                            |                       No | Validation error                |
| Account not found                          |                       No | `FAILED`                        |
| Insufficient funds                         |                       No | `FAILED`                        |
| Account blocked                            |                       No | `FAILED`                        |
| T24 HTTP 400 business rejection            |                       No | `FAILED`                        |
| T24 HTTP 500 before processing             |                  Unknown | Depends on integration contract |
| Connection failure before submission       |                       No | Technical failure               |
| Timeout during inquiry                     | No financial side effect | Technical failure               |
| Timeout during transfer                    |                      Yes | `UNKNOWN`                       |
| Response lost after transfer               |                      Yes | `UNKNOWN`                       |
| T24 unavailable before transfer submission |                       No | Technical failure               |
| Status inquiry confirms success            |        Already submitted | `SUCCESS`                       |
| Status inquiry confirms rejection          |        Already submitted | `FAILED`                        |

The key distinction is **whether the financial side effect may already have happened**.

---

# 8. HTTP API Error Model

Corevia should expose a consistent error response.

Recommended structure:

```json
{
  "timestamp": "2026-09-20T10:15:30Z",
  "status": 422,
  "code": "INSUFFICIENT_FUNDS",
  "message": "The source account does not have sufficient funds.",
  "transactionId": "TXN-20260920-000123",
  "correlationId": "CORR-8f31a2",
  "retryable": false
}
```

For an uncertain transfer:

```json
{
  "timestamp": "2026-09-20T10:15:30Z",
  "status": 202,
  "code": "TRANSACTION_OUTCOME_UNKNOWN",
  "message": "The transaction outcome could not be confirmed.",
  "transactionId": "TXN-20260920-000123",
  "correlationId": "CORR-8f31a2",
  "retryable": false
}
```

The API must **not** tell the client to blindly retry an `UNKNOWN` financial transaction.

---

# 9. Error Code Taxonomy

Corevia should use stable application-level error codes.

### Validation Errors

| Code                      | Meaning                             |
| ------------------------- | ----------------------------------- |
| `INVALID_REQUEST`         | Request structure is invalid        |
| `INVALID_AMOUNT`          | Amount is invalid                   |
| `INVALID_CURRENCY`        | Unsupported currency                |
| `INVALID_ACCOUNT`         | Account identifier is invalid       |
| `MISSING_IDEMPOTENCY_KEY` | Required idempotency key is missing |

### Business Errors

| Code                         | Meaning                                |
| ---------------------------- | -------------------------------------- |
| `ACCOUNT_NOT_FOUND`          | Account does not exist                 |
| `ACCOUNT_BLOCKED`            | Account cannot perform transaction     |
| `INSUFFICIENT_FUNDS`         | Insufficient available funds           |
| `TRANSACTION_NOT_ALLOWED`    | Transaction rejected by business rules |
| `TRANSACTION_LIMIT_EXCEEDED` | Transaction exceeds permitted limit    |

### Technical Errors

| Code                            | Meaning                  |
| ------------------------------- | ------------------------ |
| `CORE_BANKING_UNAVAILABLE`      | T24 unavailable          |
| `CORE_BANKING_TIMEOUT`          | T24 request timed out    |
| `CORE_BANKING_CONNECTION_ERROR` | Connection failure       |
| `CORE_BANKING_ERROR`            | Unexpected T24 error     |
| `INTERNAL_ERROR`                | Unexpected Corevia error |

### Transaction State Errors

| Code                            | Meaning                                           |
| ------------------------------- | ------------------------------------------------- |
| `TRANSACTION_NOT_FOUND`         | Transaction does not exist                        |
| `TRANSACTION_OUTCOME_UNKNOWN`   | Outcome cannot currently be confirmed             |
| `TRANSACTION_ALREADY_COMPLETED` | Transaction is already terminal                   |
| `DUPLICATE_REQUEST`             | Request conflicts with existing idempotency state |

---

# 10. Retry Policy

Retry policy must depend on the operation.

## 10.1 Inquiry Operations

Examples:

```text
GET /customers/{id}
GET /accounts/{id}
GET /transfers/{id}
```

These operations are generally safe to retry.

Example:

```text
Account inquiry
     |
     X timeout
     |
     v
Retry
     |
     v
T24
```

A bounded retry policy can be used.

---

## 10.2 Financial Transfer

A transfer must **not** be blindly retried.

Example:

```text
POST /transfers
       |
       v
     T24
       |
       | transaction succeeds
       |
       X response lost
       |
     timeout
```

Incorrect behavior:

```text
timeout
   |
   v
retry transfer
   |
   v
T24
   |
   v
potential duplicate transaction
```

Correct behavior:

```text
timeout
   |
   v
UNKNOWN
   |
   v
status inquiry / reconciliation
   |
   +---- SUCCESS
   |
   +---- FAILED
```

This is one of the most important reliability characteristics of Corevia.

---

# 11. Retry Categories

Corevia should distinguish:

### Safe Retry

An operation can be retried without creating an additional financial side effect.

Examples:

* customer inquiry;
* account inquiry;
* transaction status inquiry.

### Conditional Retry

Retry may be possible if the integration contract guarantees idempotency.

Example:

```text
POST transfer
Idempotency-Key: ABC-123
```

If T24 or the integration layer guarantees that `ABC-123` cannot create multiple financial transactions, a controlled retry may be considered.

### Unsafe Retry

Retry may create another financial transaction.

Example:

```text
Transfer timeout
+
unknown T24 state
```

Corevia must not automatically retry this operation.

---

# 12. Circuit Breaker

Corevia should protect itself from repeated T24 failures using a circuit breaker.

Conceptual states:

```text
             failure threshold
CLOSED --------------------------> OPEN
  ^                                  |
  |                                  |
  | successful probe                 |
  |                                  v
  +---------------------------- HALF_OPEN
```

### CLOSED

Requests flow normally.

### OPEN

Requests are rejected immediately because T24 is considered unavailable.

### HALF_OPEN

A limited number of test requests are allowed.

If T24 recovers:

```text
HALF_OPEN -> CLOSED
```

If failures continue:

```text
HALF_OPEN -> OPEN
```

Circuit breaking prevents Corevia from overwhelming an already unhealthy dependency.

---

# 13. Timeout Strategy

Timeouts should be configured explicitly.

Example configuration:

```yaml
core-banking:
  timeout:
    connect: 2s
    read: 5s
```

The actual values must be tuned according to the production T24 integration environment.

The important design principle is:

> **Never allow an external banking dependency to block Corevia indefinitely.**

Timeouts should be observable through metrics and logs.

---

# 14. Exception Hierarchy

Corevia should avoid throwing generic exceptions throughout the application.

Recommended hierarchy:

```text
CoreviaException
|
+-- ValidationException
|
+-- BusinessException
|    |
|    +-- AccountNotFoundException
|    +-- AccountBlockedException
|    +-- InsufficientFundsException
|    +-- TransactionNotAllowedException
|
+-- IntegrationException
|    |
|    +-- CoreBankingUnavailableException
|    +-- CoreBankingTimeoutException
|    +-- CoreBankingConnectionException
|    +-- CoreBankingResponseException
|
+-- UnknownTransactionOutcomeException
|
+-- IdempotencyException
```

`UnknownTransactionOutcomeException` should be treated differently from ordinary integration failures because the financial outcome may already exist.

---

# 15. Domain vs Infrastructure Exceptions

Infrastructure-specific exceptions should not leak into the domain.

Bad:

```java
throw new WebClientResponseException(...);
```

from the domain/application layer.

Instead:

```text
T24 HTTP error
      |
      v
T24Client
      |
      v
CoreBankingResponseException
      |
      v
T24Adapter
      |
      v
application/domain semantics
```

The domain should understand concepts such as:

```text
InsufficientFunds
UnknownTransactionOutcome
AccountNotFound
```

rather than:

```text
HTTP 504
SocketTimeoutException
WebClientResponseException
```

This preserves the Hexagonal Architecture boundary.

---

# 16. Error Mapping

The T24 adapter is responsible for translating T24-specific responses into Corevia semantics.

Example:

```text
T24 response
    |
    | code = "FUNDS.NOT.SUFFICIENT"
    v
T24 Error Mapper
    |
    v
InsufficientFundsException
    |
    v
FAILED
```

Another example:

```text
T24 timeout
    |
    | transfer operation
    v
UnknownTransactionOutcomeException
    |
    v
UNKNOWN
```

The same timeout should not necessarily produce the same transaction state for every operation.

---

# 17. Global API Exception Handling

The API layer should use centralized exception handling.

Conceptually:

```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    // map application exceptions
    // to stable API error responses
}
```

Responsibilities include:

* HTTP status mapping;
* stable error codes;
* correlation ID;
* transaction ID where applicable;
* retryability indication;
* safe user-facing messages;
* structured logging.

Business logic should not be duplicated across individual controllers.

---

# 18. HTTP Status Mapping

Recommended mapping:

| HTTP Status | Corevia Meaning                                 |
| ----------: | ----------------------------------------------- |
|       `400` | Invalid request                                 |
|       `401` | Authentication required/failed                  |
|       `403` | Authorization failure                           |
|       `404` | Resource not found                              |
|       `409` | Idempotency/state conflict                      |
|       `422` | Business validation/rejection                   |
|       `429` | Rate limit exceeded                             |
|       `500` | Unexpected Corevia error                        |
|       `502` | Dependency returned invalid/unexpected response |
|       `503` | Dependency unavailable                          |
|       `504` | Dependency timeout                              |
|       `202` | Request accepted but outcome unresolved         |

The exact status mapping should remain aligned with `API-SPEC.md`.

---

# 19. UNKNOWN Response Semantics

For an unresolved transfer, Corevia should return enough information for the client to continue safely.

Example:

```http
HTTP/1.1 202 Accepted
```

```json
{
  "transactionId": "TXN-20260920-000123",
  "status": "UNKNOWN",
  "message": "Transaction outcome is currently unknown.",
  "correlationId": "CORR-8f31a2"
}
```

The client should then query:

```http
GET /api/v1/transfers/TXN-20260920-000123
```

rather than submitting another transfer.

---

# 20. Idempotency Interaction

Error handling and idempotency are tightly coupled.

Example:

```text
Request
  |
  | Idempotency-Key = ABC-123
  v
Corevia
  |
  v
T24
  |
  X timeout
  |
  v
UNKNOWN
```

The original request must remain associated with:

```text
ABC-123
TXN-20260920-000123
UNKNOWN
```

A subsequent request using the same idempotency key must not create a second transfer.

The detailed idempotency design is defined separately in:

```text
IDEMPOTENCY.md
```

---

# 21. Logging Strategy

Every error should be traceable using:

* `correlationId`;
* `transactionId`;
* operation;
* dependency;
* error code;
* error category;
* latency;
* outcome.

Example:

```json
{
  "level": "WARN",
  "event": "core_banking_timeout",
  "operation": "TRANSFER",
  "transactionId": "TXN-20260920-000123",
  "correlationId": "CORR-8f31a2",
  "dependency": "T24",
  "errorCode": "CORE_BANKING_TIMEOUT",
  "transactionStatus": "UNKNOWN"
}
```

---

# 22. Sensitive Data Protection

Logs must not contain sensitive banking information unnecessarily.

Do not log:

```text
full account numbers
customer credentials
JWT tokens
API keys
passwords
secret keys
authorization headers
full payment payloads
```

Where account identifiers are required for troubleshooting, masking should be used.

Example:

```text
1000012345
```

becomes:

```text
******2345
```

The exact masking policy should be defined in `SECURITY.md`.

---

# 23. Error Observability

Corevia should expose metrics for error analysis.

Recommended metrics:

```text
corevia_errors_total
corevia_business_errors_total
corevia_integration_errors_total
corevia_t24_timeouts_total
corevia_transfer_unknown_total
corevia_transfer_failed_total
corevia_circuit_breaker_open_total
```

Useful dimensions include:

```text
operation
dependency
error_code
outcome
```

Avoid high-cardinality labels such as:

```text
transactionId
accountId
customerId
correlationId
```

as Prometheus labels.

These belong in logs/traces instead.

---

# 24. Error Flow Example — Insufficient Funds

```text
Client
  |
  | POST /transfers
  v
Corevia
  |
  | validate
  v
T24
  |
  | insufficient funds
  v
T24Adapter
  |
  | map error
  v
InsufficientFundsException
  |
  v
Transfer = FAILED
  |
  v
HTTP 422
```

This is a deterministic business failure.

---

# 25. Error Flow Example — Transfer Timeout

```text
Client
  |
  | POST /transfers
  v
Corevia
  |
  v
T24
  |
  | transaction may have succeeded
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
HTTP 202
```

Corevia does **not** retry the financial transfer automatically.

---

# 26. UNKNOWN Resolution Flow

```text
                 +----------------+
                 |    UNKNOWN     |
                 +-------+--------+
                         |
                         v
                 Status Inquiry
                         |
              +----------+----------+
              |                     |
              v                     v
          T24 SUCCESS          T24 FAILED
              |                     |
              v                     v
           SUCCESS                FAILED
```

If the status inquiry itself fails:

```text
UNKNOWN
   |
   v
Status Inquiry
   |
   X timeout
   |
   v
UNKNOWN
```

The transaction remains unresolved until reliable evidence is obtained.

---

# 27. Reconciliation

For production banking systems, unresolved transactions may require reconciliation.

Example:

```text
Corevia
  |
  | UNKNOWN transaction
  v
Reconciliation Process
  |
  +---- T24 transaction found ----> SUCCESS
  |
  +---- T24 rejection found ------> FAILED
  |
  +---- no result ----------------> remain UNKNOWN
```

Reconciliation is particularly important when the external system and middleware cannot guarantee exactly-once end-to-end processing.

Corevia should therefore provide enough transaction metadata to support operational reconciliation.

---

# 28. Error Handling Principles

Corevia follows these principles:

### 1. Never equate timeout with failure

```text
Timeout != FAILED
```

The outcome depends on whether the operation could have produced a side effect.

### 2. Never blindly retry financial transactions

```text
UNKNOWN -> status inquiry
```

not:

```text
UNKNOWN -> POST transfer again
```

### 3. Preserve transaction state

Every important outcome must be persisted.

### 4. Separate business and technical errors

Business errors represent deterministic rejection.

Technical errors represent infrastructure or communication problems.

### 5. Protect the system of record

T24 remains the authoritative source for financial transaction truth.

### 6. Make failures observable

Errors must be traceable through:

```text
correlationId
transactionId
metrics
logs
traces
```

### 7. Keep infrastructure concerns outside the domain

T24/HTTP-specific exceptions should be translated at the adapter boundary.

### 8. Fail safely

When the outcome of a financial transaction cannot be established, preserve uncertainty rather than inventing success or failure.

---

# 29. Summary

Corevia's error-handling strategy is based on a simple but critical banking principle:

```text
                Did the transaction definitely fail?
                           |
                +----------+----------+
                |                     |
               YES                    NO
                |                     |
                v                     v
             FAILED               UNKNOWN
                                      |
                                      v
                              Determine outcome
                                      |
                           +----------+----------+
                           |                     |
                           v                     v
                        SUCCESS               FAILED
```

The system must preserve the distinction between:

```text
Business rejection
Technical failure
Unknown financial outcome
```

This prevents a common integration failure mode:

> treating a lost response as proof that a financial transaction did not happen.

For Corevia, **safe failure is more important than aggressive retry**.
