# ADR-003: Use Idempotency for Financial Transactions

* **Status:** Accepted
* **Date:** 2026-09-19
* **Decision Owners:** Corevia Architecture

## Context

Financial APIs operate over networks where requests can be retried.

A client may submit a transfer and experience a timeout even though the core banking transaction was successfully completed.

The client may then retry the same request.

Without idempotency, the middleware could submit the transaction to T24 twice.

```text
Client
  |
  | Transfer
  v
Corevia
  |
  v
T24
  |
  | SUCCESS
  X response lost
  |
Client timeout
  |
  | retry
  v
Corevia
  |
  v
T24 again
```

This could create a duplicate financial transaction.

## Decision

Corevia will require an **Idempotency-Key** for financial transaction APIs.

Example:

```http
POST /api/v1/transfers
Idempotency-Key: PAYROLL-202609-000001
```

The middleware will persist:

```text
Idempotency-Key
Request Hash
Transaction ID
Processing State
Response
Created Timestamp
```

## Processing Rules

### New Key

```text
Key not found
    ↓
Process transaction
    ↓
Store result
```

### Existing Key + Same Request

```text
Key found
    ↓
Request matches
    ↓
Return original result
```

### Existing Key + Different Request

```text
Key found
    ↓
Request differs
    ↓
Reject request
```

This prevents accidental reuse of an idempotency key for a different transaction.

## Consequences

### Positive

* Duplicate financial transactions are prevented.
* Client retries become safe.
* Transaction behavior becomes deterministic.

### Negative

* Additional database storage is required.
* Idempotency records require lifecycle/retention management.
* Distributed deployments require shared idempotency storage.

## Alternatives Considered

### Client-Side Duplicate Prevention

Rejected because the server must not rely solely on client behavior.

### In-Memory Idempotency

Rejected because it does not work reliably across multiple middleware instances.

## Result

PostgreSQL will provide shared idempotency state for the initial implementation.
