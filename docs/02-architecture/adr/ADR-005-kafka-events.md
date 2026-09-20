# ADR-005: Use Kafka for Business Events

* **Status:** Accepted
* **Date:** 2026-09-19
* **Decision Owners:** Corevia Architecture

## Context

Core banking transactions may trigger downstream activities such as:

* Notifications
* Audit processing
* Fraud analysis
* Reconciliation
* Analytics

Making the transfer API synchronously call every downstream system would increase coupling and latency.

## Decision

Corevia will publish business events to Kafka.

Example:

```text
TransferCompleted
```

Flow:

```text
Transfer
   |
   v
T24
   |
SUCCESS
   |
   v
Corevia
   |
   v
Kafka
   |
   +----> Notification
   |
   +----> Audit
   |
   +----> Analytics
   |
   +----> Reconciliation
```

The application will depend on an abstraction:

```java
public interface EventPublisher {

    void publish(DomainEvent event);

}
```

Kafka-specific implementation will remain in the infrastructure layer.

## Consequences

### Positive

* Downstream systems are loosely coupled.
* Consumers can evolve independently.
* Additional consumers can be added without modifying the transfer API.
* Event-driven architecture is demonstrated explicitly.

### Negative

* Introduces eventual consistency.
* Requires event monitoring.
* Requires handling delivery failures.
* Kafka adds operational complexity.

## Alternatives Considered

### Synchronous REST Calls

Rejected for downstream secondary processing because it creates unnecessary runtime coupling.

### Database Polling

Rejected as the primary event mechanism because it introduces polling latency and additional complexity.

## Future Evolution

A Transactional Outbox may be introduced:

```text
Transfer
   |
   v
PostgreSQL
   |
   +-- Transaction
   |
   +-- Outbox Event
           |
           v
     Outbox Publisher
           |
           v
         Kafka
```

This can provide stronger consistency between transaction state and event publication.

## Result

Kafka is the initial event-streaming platform for Corevia.
