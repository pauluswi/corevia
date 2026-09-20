# ADR-004: Represent Unknown Transaction State Explicitly

* **Status:** Accepted
* **Date:** 2026-09-19
* **Decision Owners:** Corevia Architecture
* **Related:** ADR-003

## Context

A timeout during a financial transaction does not necessarily mean that the transaction failed.

Example:

```text
Corevia
   |
   | Transfer
   v
T24
   |
   | Transaction committed
   |
   X network failure
   |
Corevia
```

Corevia cannot safely determine the result from the timeout alone.

Treating the transaction as `FAILED` could cause the client to retry.

Treating it as `SUCCESS` without confirmation could also be incorrect.

## Decision

Corevia will explicitly support:

```text
UNKNOWN
```

as a transaction state.

The lifecycle is:

```text
RECEIVED
    ↓
VALIDATING
    ↓
SUBMITTED
    ↓
SUCCESS
    |
    +---- FAILED
    |
    +---- UNKNOWN
```

When the result is unknown, the middleware will not blindly retry the financial operation.

Instead, it may perform a transaction-status inquiry.

```text
UNKNOWN
   |
   v
Transaction Status Inquiry
   |
   +---- SUCCESS
   |
   +---- FAILED
   |
   +---- STILL UNKNOWN
```

## Consequences

### Positive

* Prevents unsafe retries.
* Represents distributed-system uncertainty explicitly.
* Reflects realistic financial transaction behavior.
* Provides a strong basis for reconciliation.

### Negative

* Transaction workflows become more complex.
* Additional status-inquiry logic is required.
* Clients must understand an intermediate/uncertain state.

## Alternatives Considered

### Treat Timeout as FAILED

Rejected because the core system may have committed the transaction.

### Automatically Retry

Rejected because retrying an unknown financial transaction may create duplicates.

### Treat Timeout as SUCCESS

Rejected because successful completion has not been confirmed.

## Result

`UNKNOWN` is a first-class transaction state in Corevia.
