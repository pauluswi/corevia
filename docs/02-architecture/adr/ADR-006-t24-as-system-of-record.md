# ADR-006: Keep Temenos Transact as the System of Record

* **Status:** Accepted
* **Date:** 2026-09-19
* **Decision Owners:** Corevia Architecture

## Context

Corevia requires some persistent state for:

* Idempotency
* Transaction lifecycle
* Audit
* Integration metadata

However, introducing a database inside the middleware can create confusion about which system owns banking data.

Corevia must not become an accidental second core-banking ledger.

## Decision

Temenos Transact remains the authoritative system of record for core-banking data and financial transactions.

Corevia PostgreSQL stores only middleware-owned operational data.

```text
                 +----------------------+
                 | Temenos Transact     |
                 |                      |
                 | System of Record     |
                 |                      |
                 | Accounts             |
                 | Balances             |
                 | Core Transactions    |
                 +----------+-----------+
                            ^
                            |
                     Core Banking
                       Operations
                            |
                            v
                 +----------------------+
                 | Corevia              |
                 |                      |
                 | Integration Layer    |
                 +----------+-----------+
                            |
                            v
                 +----------------------+
                 | PostgreSQL           |
                 |                      |
                 | Middleware State     |
                 | Idempotency          |
                 | Audit                |
                 +----------------------+
```

## Corevia-Owned Data

Corevia may store:

* Transaction processing state
* Idempotency records
* Audit metadata
* Correlation IDs
* Integration metadata
* Event/outbox records

## Data That Must Remain Authoritative in T24

Corevia must not become authoritative for:

* Account balances
* Ledger entries
* Customer core-banking master data
* Final financial transaction state
* Core banking product configuration

## Consequences

### Positive

* Clear system ownership.
* Avoids duplicate ledger responsibility.
* Reduces risk of data divergence.
* Reflects realistic core-banking integration architecture.

### Negative

* Corevia depends on Transact for authoritative data.
* Some inquiries require synchronous core-banking calls.
* Distributed consistency must be handled explicitly.

## Alternatives Considered

### Replicate the T24 Ledger in Corevia

Rejected because it would create unnecessary complexity and competing sources of truth.

### Make PostgreSQL the Primary Transaction Store

Rejected because Corevia is an integration middleware, not a core-banking platform.

## Result

Temenos Transact remains the system of record.

Corevia is responsible for orchestration, integration, resilience, operational state, and event distribution.
