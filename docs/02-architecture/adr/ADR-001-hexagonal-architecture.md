# ADR-001: Use Hexagonal Architecture

* **Status:** Accepted
* **Date:** 2026-09-19
* **Decision Owners:** Corevia Architecture
* **Related:** ARC42, C4

## Context

Corevia integrates digital banking channels with Temenos Transact.

The system must communicate with external technologies including:

* REST clients
* Temenos Transact
* PostgreSQL
* Kafka

Directly coupling the application and domain logic to these technologies would make the system harder to test, evolve, and replace.

For example, business logic should not need to know whether a core-banking operation is implemented through HTTP, another protocol, or a mock implementation.

## Decision

Corevia will use **Hexagonal Architecture (Ports and Adapters)**.

The architecture will separate:

```text
Domain
   |
Application
   |
Ports
   |
Adapters
   |
Infrastructure
```

The application will depend on abstractions rather than infrastructure implementations.

### Example

```java
public interface CoreBankingGateway {

    Account getAccount(String accountId);

    TransferResult transfer(TransferRequest request);

}
```

The application depends on `CoreBankingGateway`, not on the T24 HTTP client.

## Consequences

### Positive

* Business logic remains independent of infrastructure.
* T24 integration can be replaced or mocked.
* Unit testing becomes easier.
* Infrastructure technologies can evolve independently.
* Architecture boundaries become explicit.

### Negative

* More interfaces and classes are required.
* The architecture introduces additional abstraction.
* Simple operations may require more code.

## Alternatives Considered

### Traditional Layered Architecture

```text
Controller
   ↓
Service
   ↓
Repository
```

Rejected because it does not provide as strong a boundary around the core-banking integration.

### Direct T24 Calls from Services

Rejected because it tightly couples application logic to T24-specific implementation details.

## Result

Corevia will use Hexagonal Architecture as its primary architectural structure.
