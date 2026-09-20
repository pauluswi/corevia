# ADR-002: Isolate Temenos Transact Behind an Adapter

* **Status:** Accepted
* **Date:** 2026-09-19
* **Decision Owners:** Corevia Architecture
* **Related:** ADR-001

## Context

Corevia integrates with Temenos Transact R25.

Transact-specific concepts may include:

* Request formats
* Response formats
* Error codes
* API conventions
* Transport mechanisms
* Core-banking-specific data models

Allowing these concepts to propagate throughout the application would create strong coupling.

## Decision

Corevia will isolate Transact integration behind a `CoreBankingGateway` port.

```text
Application
     |
     v
CoreBankingGateway
     |
     v
T24Adapter
     |
     v
T24Client
     |
     v
Temenos Transact
```

The initial implementation will be:

```text
T24Adapter
```

A mock implementation will be used for local development and automated testing.

## Responsibilities of T24Adapter

The adapter will:

1. Translate Corevia requests.
2. Call Transact.
3. Translate Transact responses.
4. Translate Transact errors.
5. Handle technical communication failures.
6. Hide Transact-specific models from the domain.

## Consequences

### Positive

* T24-specific logic has a clearly defined boundary.
* Mock Transact can be used for testing.
* A future alternative core-banking implementation can be introduced.
* Application code remains focused on banking use cases.

### Negative

* Additional mapping code is required.
* Some Transact-specific capabilities may require adapter-specific extensions.

## Alternatives Considered

### Direct HTTP Client in Application Service

Rejected because it couples business logic to Transact transport details.

### Generic Integration Service

Rejected because excessive abstraction could hide important banking-specific integration behavior.

## Result

All core-banking communication will pass through the `CoreBankingGateway` abstraction and `T24Adapter`.
