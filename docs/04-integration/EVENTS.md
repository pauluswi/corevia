# Corevia — Events

## 1. Purpose

This document defines Corevia's domain and integration event model.

Corevia uses Kafka to publish important transaction lifecycle events to downstream consumers without coupling those consumers directly to the synchronous transfer-processing flow.

The event architecture supports:

* asynchronous integration;
* downstream notifications;
* audit and monitoring;
* transaction lifecycle propagation;
* analytics;
* reconciliation workflows;
* future integration with other banking services.

The central architectural principle is:

> **Kafka distributes transaction events; it does not become the financial system of record.**

T24 remains authoritative for the actual financial transaction and account balance.

---

# 2. Why Corevia Uses Events

A synchronous transfer request may look like:

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
   v
Response
```

However, multiple systems may need to know that the transaction occurred:

```text
                    +--> Notification Service
                    |
Corevia --> Kafka --+--> Audit Service
                    |
                    +--> Reconciliation
                    |
                    +--> Analytics
                    |
                    +--> Monitoring
```

Without events, Corevia would need to synchronously call each downstream system.

That creates unnecessary coupling.

With Kafka:

```text
Corevia
   |
   | publish event
   v
Kafka
   |
   +--> Consumer A
   +--> Consumer B
   +--> Consumer C
```

Each consumer can process the event independently.

---

# 3. Event vs Command

Corevia distinguishes commands from events.

### Command

A command asks a system to perform an action.

Example:

```text
TransferFunds
```

Meaning:

> Please perform this transfer.

### Event

An event states that something happened.

Example:

```text
TransferSucceeded
```

Meaning:

> The transfer has successfully completed.

Therefore:

```text
Command:
"Do this."

Event:
"This happened."
```

Corevia's Kafka messages are primarily **events**, not commands.

---

# 4. Event Lifecycle

The event model follows the transaction lifecycle.

```text
RECEIVED
   |
   v
VALIDATING
   |
   v
SUBMITTED
   |
   +------------------+
   |                  |
   v                  v
SUCCESS             FAILED
   |                  |
   v                  v
TransferSucceeded   TransferFailed

If outcome cannot be determined:

SUBMITTED
   |
   v
UNKNOWN
   |
   v
TransferUnknown
   |
   +------> SUCCESS
   |           |
   |           v
   |     TransferSucceeded
   |
   +------> FAILED
               |
               v
         TransferFailed
```

The events represent meaningful state transitions rather than every internal implementation detail.

---

# 5. Core Events

Corevia initially defines four primary events:

| Event               | Meaning                                                   |
| ------------------- | --------------------------------------------------------- |
| `TransferSubmitted` | Corevia submitted the transfer to the core banking system |
| `TransferSucceeded` | Transfer completed successfully                           |
| `TransferFailed`    | Transfer was deterministically rejected or failed         |
| `TransferUnknown`   | Corevia cannot currently determine the financial outcome  |

These events are associated with the `Transfer` / `Transaction` lifecycle.

---

# 6. TransferSubmitted

`TransferSubmitted` indicates that Corevia has submitted the financial operation to T24.

Example:

```json
{
  "eventType": "TransferSubmitted",
  "eventVersion": 1,
  "eventId": "evt-001",
  "occurredAt": "2026-09-20T08:30:10Z",
  "transactionId": "TXN-001",
  "correlationId": "CORR-001",
  "idempotencyKey": "ABC-123",
  "status": "SUBMITTED"
}
```

This event does **not** mean the financial transaction has succeeded.

It means:

```text
Corevia -> T24
submission occurred
```

---

# 7. TransferSucceeded

`TransferSucceeded` indicates that Corevia has reliable confirmation that the transaction succeeded.

Example:

```json
{
  "eventType": "TransferSucceeded",
  "eventVersion": 1,
  "eventId": "evt-002",
  "occurredAt": "2026-09-20T08:30:12Z",
  "transactionId": "TXN-001",
  "correlationId": "CORR-001",
  "status": "SUCCESS",
  "amount": 1500000,
  "currency": "IDR"
}
```

Consumers may use this event to:

* trigger customer notifications;
* update operational views;
* record audit information;
* initiate downstream processing;
* update reporting systems.

Consumers must not interpret the event as replacing the T24 ledger.

---

# 8. TransferFailed

`TransferFailed` indicates a deterministic failure.

Example:

```json
{
  "eventType": "TransferFailed",
  "eventVersion": 1,
  "eventId": "evt-003",
  "occurredAt": "2026-09-20T08:30:12Z",
  "transactionId": "TXN-002",
  "correlationId": "CORR-002",
  "status": "FAILED",
  "errorCode": "INSUFFICIENT_FUNDS"
}
```

The event may contain a stable application error code.

It should not expose internal stack traces or sensitive T24 implementation details.

---

# 9. TransferUnknown

`TransferUnknown` is particularly important for banking integration.

It means:

> Corevia cannot currently determine whether the financial operation succeeded.

Example:

```json
{
  "eventType": "TransferUnknown",
  "eventVersion": 1,
  "eventId": "evt-004",
  "occurredAt": "2026-09-20T08:30:15Z",
  "transactionId": "TXN-003",
  "correlationId": "CORR-003",
  "status": "UNKNOWN",
  "reasonCode": "CORE_BANKING_TIMEOUT"
}
```

A consumer must not interpret:

```text
TransferUnknown
```

as:

```text
TransferFailed
```

The transaction may subsequently resolve to either:

```text
SUCCESS
```

or:

```text
FAILED
```

---

# 10. Event Envelope

All Corevia events should use a consistent envelope.

Recommended structure:

```json
{
  "eventId": "evt-123",
  "eventType": "TransferSucceeded",
  "eventVersion": 1,
  "occurredAt": "2026-09-20T08:30:12Z",
  "producer": "corevia",
  "correlationId": "CORR-123",
  "transactionId": "TXN-001",
  "payload": {}
}
```

The envelope provides common metadata while the payload contains event-specific information.

---

# 11. Event Metadata

Recommended fields:

| Field           | Purpose                        |
| --------------- | ------------------------------ |
| `eventId`       | Unique identifier of the event |
| `eventType`     | Event type                     |
| `eventVersion`  | Schema version                 |
| `occurredAt`    | Event occurrence timestamp     |
| `producer`      | Producing service              |
| `correlationId` | Technical trace identifier     |
| `transactionId` | Corevia transaction identifier |
| `payload`       | Event-specific information     |

Additional fields may be introduced later when justified.

---

# 12. Event ID

Every event requires a unique `eventId`.

Example:

```text
evt-7e9a3b2f
```

The event ID is useful for:

* deduplication;
* tracing;
* debugging;
* audit;
* consumer processing.

An event ID is different from:

```text
transactionId
```

because one transaction can produce multiple events.

Example:

```text
TXN-001
   |
   +--> TransferSubmitted
   |
   +--> TransferSucceeded
```

Two events:

```text
evt-001
evt-002
```

but one transaction:

```text
TXN-001
```

---

# 13. Correlation ID

`correlationId` identifies the technical flow associated with a request.

Example:

```text
Client Request
     |
     v
CORR-001
     |
     +--> Corevia
     |
     +--> T24
     |
     +--> Kafka
     |
     +--> Consumer
```

This makes it possible to trace an operation across multiple components.

The correlation ID should be propagated through supported integration boundaries.

---

# 14. Transaction ID

`transactionId` identifies the Corevia financial operation.

Example:

```text
TXN-001
```

It remains stable throughout the lifecycle:

```text
TransferSubmitted
       |
       v
TransferUnknown
       |
       v
TransferSucceeded
```

All three events refer to:

```text
TXN-001
```

This allows consumers and operational tools to correlate lifecycle events.

---

# 15. Idempotency Key

The idempotency key may be included when useful for operational tracing.

Example:

```json
{
  "transactionId": "TXN-001",
  "idempotencyKey": "ABC-123"
}
```

However, the idempotency key should not normally be used as the event identity.

The event identity remains:

```text
eventId
```

The business operation identity remains:

```text
transactionId
```

---

# 16. Kafka Topic Strategy

A simple initial topic structure is recommended.

```text
corevia.transfer.events
```

All transfer lifecycle events can initially be published to this topic.

Example:

```text
corevia.transfer.events
    |
    +--> TransferSubmitted
    +--> TransferSucceeded
    +--> TransferFailed
    +--> TransferUnknown
```

This keeps the initial architecture simple.

Topic splitting can be introduced later if operational requirements justify it.

---

# 17. Partitioning

Kafka partitioning should preserve ordering for events belonging to the same transaction.

Recommended key:

```text
transactionId
```

Example:

```text
Kafka key = TXN-001
```

Kafka can then route all events for the same transaction to the same partition.

Conceptually:

```text
TXN-001
   |
   +--> TransferSubmitted
   |
   +--> TransferUnknown
   |
   +--> TransferSucceeded
```

The events remain ordered relative to each other within the partition.

---

# 18. Ordering Guarantee

Kafka ordering is generally guaranteed **within a partition**, not globally across all partitions.

Therefore Corevia should not assume:

```text
TransferSucceeded
```

will always arrive before:

```text
TransferFailed
```

across different partitions or independent event streams.

Using:

```text
transactionId
```

as the partition key helps preserve lifecycle ordering for a single transaction.

Consumers should still be designed defensively.

---

# 19. Event Delivery Semantics

Corevia should initially use:

> **At-least-once event delivery.**

This means consumers may receive the same event more than once.

Example:

```text
Corevia
   |
   | TransferSucceeded
   v
Kafka
   |
   v
Consumer
   |
   | process
   X acknowledgement lost
   |
Kafka
   |
   v
Consumer receives event again
```

Therefore consumers must be idempotent.

---

# 20. Consumer Idempotency

Consumers should store the processed event ID.

Example:

```text
eventId = evt-002
```

Before processing:

```text
Has evt-002 already been processed?
```

If yes:

```text
skip duplicate
```

If no:

```text
process event
record evt-002
```

Conceptually:

```text
Kafka Event
    |
    v
Consumer
    |
    v
Check eventId
   / \
 yes  no
 |     |
skip  process
       |
       v
 record eventId
```

---

# 21. Why Not Exactly-Once End-to-End?

Kafka supports strong delivery and processing guarantees, but Corevia should not claim that the entire banking transaction is automatically exactly-once.

The full flow contains independent systems:

```text
Client
  |
Corevia
  |
PostgreSQL
  |
T24
  |
Kafka
  |
Consumer
```

Exactly-once behavior across all of these systems is not automatically guaranteed by Kafka.

Corevia therefore uses:

```text
Idempotency
+
Durable transaction state
+
Kafka at-least-once delivery
+
Idempotent consumers
```

rather than relying on a broad "exactly once" claim.

---

# 22. Transactional Outbox Pattern

A key reliability problem exists when Corevia updates PostgreSQL and publishes Kafka separately.

Unsafe sequence:

```text
BEGIN
 |
 | update transaction = SUCCESS
 |
COMMIT
 |
 | publish Kafka event
 |
 X Kafka unavailable
```

Now:

```text
Database = SUCCESS
Kafka = no event
```

The transaction succeeded, but downstream consumers never receive the event.

The opposite problem can also occur:

```text
Kafka event published
 |
 X database transaction fails
```

This can create inconsistent state.

---

# 23. Recommended Outbox Architecture

Corevia should use a **Transactional Outbox** pattern.

Instead of immediately publishing directly to Kafka:

```text
Application
    |
    +--> Transaction state
    |
    +--> Outbox event
```

Both are stored in the same PostgreSQL transaction.

```text
BEGIN
   |
   +--> UPDATE transaction
   |
   +--> INSERT outbox_event
   |
COMMIT
```

Then a publisher asynchronously sends the outbox event to Kafka.

```text
PostgreSQL
   |
   | outbox
   v
Outbox Publisher
   |
   v
Kafka
```

This avoids the database/Kafka dual-write problem.

---

# 24. Outbox Table

Example:

```sql
CREATE TABLE outbox_event (
    id BIGSERIAL PRIMARY KEY,
    event_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMP NOT NULL,
    published_at TIMESTAMP,
    retry_count INTEGER NOT NULL DEFAULT 0
);
```

Recommended unique constraint:

```sql
CREATE UNIQUE INDEX ux_outbox_event_id
ON outbox_event(event_id);
```

---

# 25. Outbox Flow

Example successful transfer:

```text
T24
 |
 | success
 v
Corevia
 |
 +--> transaction = SUCCESS
 |
 +--> outbox_event = TransferSucceeded
 |
 v
COMMIT
 |
 v
Outbox Publisher
 |
 v
Kafka
 |
 v
Consumers
```

The transaction state and event record are committed atomically in PostgreSQL.

---

# 26. Outbox Publisher

The publisher periodically finds unpublished events:

```text
SELECT *
FROM outbox_event
WHERE published_at IS NULL
ORDER BY created_at;
```

It publishes them to Kafka.

After successful publication:

```text
published_at = current_timestamp
```

Conceptually:

```text
+----------------+
| Outbox Record  |
+-------+--------+
        |
        | unpublished
        v
+----------------+
| Publisher      |
+-------+--------+
        |
        v
      Kafka
        |
        v
published_at
```

---

# 27. Publisher Failure

Suppose:

```text
Outbox
   |
   v
Publisher
   |
   v
Kafka
   |
   X failure
```

The outbox event remains:

```text
published_at = NULL
```

The publisher can retry later.

This is much safer than losing the event.

---

# 28. Duplicate Publication

At-least-once publishing can produce:

```text
eventId = evt-002
```

more than once.

Example:

```text
Publisher
   |
   | publish evt-002
   v
Kafka
   |
   | success
   |
   X acknowledgement lost
   |
Publisher retries
   |
   v
Kafka
```

Kafka may contain duplicate deliveries from the consumer's perspective.

Consumers must therefore be idempotent.

This is an intentional design trade-off:

> **Duplicate event delivery is preferable to silently losing a financial lifecycle event.**

---

# 29. Event State Machine

An outbox event can have a lifecycle:

```text
CREATED
   |
   v
PENDING
   |
   v
PUBLISHED
```

If publication fails:

```text
PENDING
   |
   +---- retry ----+
   |               |
   +---------------+
```

Persistent failure may produce an operational state such as:

```text
FAILED
```

requiring alerting or manual intervention.

---

# 30. Event Versioning

Every event should contain:

```text
eventVersion
```

Example:

```json
{
  "eventType": "TransferSucceeded",
  "eventVersion": 1
}
```

When the schema evolves incompatibly:

```text
v1
```

can evolve to:

```text
v2
```

Consumers should be designed to tolerate compatible additions.

---

# 31. Backward Compatibility

Prefer additive schema changes.

Good:

```json
{
  "transactionId": "TXN-001",
  "status": "SUCCESS",
  "channel": "MOBILE"
}
```

Adding:

```text
channel
```

can usually be handled by consumers that ignore unknown fields.

Riskier changes include:

* renaming existing fields;
* changing field types;
* changing semantic meaning;
* removing required fields.

Schema evolution should therefore be treated as an API compatibility concern.

---

# 32. Event Payload Design

Events should contain enough information for consumers to perform their responsibility without requiring unnecessary synchronous calls back to Corevia.

Example:

```json
{
  "eventId": "evt-002",
  "eventType": "TransferSucceeded",
  "eventVersion": 1,
  "occurredAt": "2026-09-20T08:30:12Z",
  "producer": "corevia",
  "correlationId": "CORR-001",
  "transactionId": "TXN-001",
  "payload": {
    "sourceAccount": "******2345",
    "destinationAccount": "******8765",
    "amount": 1500000,
    "currency": "IDR",
    "reference": "PAYROLL-202609"
  }
}
```

The payload should follow the principle:

> **Publish what downstream consumers legitimately need, not everything Corevia knows.**

---

# 33. Sensitive Data

Events can propagate through multiple systems.

Therefore sensitive data must be minimized.

Avoid publishing:

```text
passwords
access tokens
authentication credentials
secret keys
full PAN
unnecessary customer PII
internal security data
```

Account numbers should be included only when justified by the consumer's business requirement and protected appropriately.

For some consumers, a masked account identifier may be sufficient.

---

# 34. Event Ownership

Corevia owns the meaning of its published transfer lifecycle events.

For example:

```text
TransferSucceeded
```

means:

> Corevia has determined that the transfer completed successfully according to its integration with the core banking system.

It does not mean:

> Kafka itself is authoritative for the financial transaction.

T24 remains the financial system of record.

---

# 35. Kafka Does Not Replace T24

The architecture must preserve this boundary:

```text
              Financial Truth
                    |
                    v
                   T24
                    |
                    |
                 Corevia
                    |
                    v
                  Kafka
                    |
        +-----------+-----------+
        |           |           |
        v           v           v
   Notification   Audit      Analytics
```

Kafka distributes information about the transaction.

It does not maintain the authoritative account balance.

---

# 36. Balance Updates

Corevia must not use Kafka events to create an independent financial ledger.

For example:

```text
TransferSucceeded
```

should not automatically mean:

```text
Corevia balance = Corevia balance - amount
```

unless a separate, explicitly designed read model requires it.

The authoritative balance remains in T24.

---

# 37. Event Consumers

Potential future consumers include:

### Notification Service

```text
TransferSucceeded
        |
        v
SMS / Email / Push
```

### Audit Service

```text
TransferSucceeded
        |
        v
Immutable audit record
```

### Reconciliation Service

```text
TransferUnknown
        |
        v
Reconciliation workflow
```

### Analytics

```text
TransferSucceeded
TransferFailed
        |
        v
Operational analytics
```

### Monitoring

```text
TransferUnknown
        |
        v
Operational alert
```

These consumers remain independently deployable.

---

# 38. Consumer Failure Isolation

A consumer failure should not prevent the core transaction from completing.

Example:

```text
T24
 |
 v
Corevia
 |
 v
Transaction SUCCESS
 |
 v
Kafka
 |
 +--> Notification Consumer -- X failure
 |
 +--> Audit Consumer --------- OK
 |
 +--> Analytics Consumer ----- OK
```

The notification failure should not roll back the T24 transaction.

This demonstrates the separation between:

```text
Financial transaction processing
```

and:

```text
Asynchronous downstream processing
```

---

# 39. Dead Letter Handling

Consumers should have a strategy for messages that repeatedly fail processing.

Conceptually:

```text
Kafka
  |
  v
Consumer
  |
  +---- success ----> processed
  |
  +---- failure
          |
          v
       retry
          |
          v
       retry
          |
          v
      Dead Letter
```

A dead-letter mechanism prevents one malformed or problematic event from blocking the consumer indefinitely.

The exact Kafka topic naming can be defined during implementation.

---

# 40. Event Retry

Consumer retry should distinguish:

### Transient failure

Example:

```text
database temporarily unavailable
```

Retry.

### Permanent failure

Example:

```text
invalid event schema
```

Move to dead-letter handling after the configured retry policy.

---

# 41. Event Ordering and UNKNOWN

Consider:

```text
TransferSubmitted
TransferUnknown
TransferSucceeded
```

A consumer may use the sequence to understand the transaction lifecycle.

However, consumers should also use:

```text
transactionId
eventType
occurredAt
```

and their own state management rather than assuming that every event arrives exactly once.

For example, if:

```text
TransferSucceeded
```

is received before a previously delayed:

```text
TransferUnknown
```

the consumer should avoid incorrectly reverting the transaction from SUCCESS back to UNKNOWN.

A terminal-state rule can protect consumer state:

```text
SUCCESS
FAILED
```

should normally not be downgraded by a stale event.

---

# 42. Event Processing Idempotency

A consumer can maintain:

```sql
CREATE TABLE processed_event (
    event_id VARCHAR(100) PRIMARY KEY,
    processed_at TIMESTAMP NOT NULL
);
```

Conceptual processing:

```text
BEGIN
   |
   | check event_id
   |
   +---- already exists ---> skip
   |
   +---- not exists
            |
            +--> process event
            |
            +--> insert event_id
   |
COMMIT
```

Where possible, event processing and the processed-event record should be committed atomically.

---

# 43. Event Observability

Corevia should expose metrics such as:

```text
corevia_events_published_total
corevia_events_publish_failures_total
corevia_events_retry_total
corevia_events_consumer_failures_total
corevia_events_dead_letter_total
corevia_outbox_pending_total
corevia_outbox_publish_latency
```

Useful operational dashboards include:

```text
Outbox backlog
Publication failures
Kafka producer latency
Consumer lag
Dead-letter count
Event processing failures
```

---

# 44. Event Tracing

Events should preserve traceability through:

```text
eventId
transactionId
correlationId
```

Example:

```text
Client
  |
  | CORR-001
  v
Corevia
  |
  | TXN-001
  v
T24
  |
  v
PostgreSQL Outbox
  |
  | evt-002
  v
Kafka
  |
  v
Notification Service
```

An operator should be able to trace the transaction across the major components.

---

# 45. Security

Kafka communication should use appropriate security controls in production.

Depending on the deployment environment:

* TLS;
* SASL authentication;
* ACLs;
* network segmentation;
* secret management;
* topic-level authorization;
* encryption at rest.

Producer permissions should be limited to the topics Corevia actually needs.

Consumers should receive only the permissions required for their topics.

---

# 46. Example End-to-End Success Flow

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
  | SUCCESS
  v
Corevia
  |
  +--> UPDATE transaction = SUCCESS
  |
  +--> INSERT TransferSucceeded into outbox
  |
  v
COMMIT
  |
  v
Outbox Publisher
  |
  v
Kafka
  |
  +--> Notification
  |
  +--> Audit
  |
  +--> Analytics
```

The transaction and event record are committed together.

---

# 47. Example End-to-End UNKNOWN Flow

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
  X timeout
  |
  v
Corevia
  |
  +--> transaction = UNKNOWN
  |
  +--> TransferUnknown -> outbox
  |
  v
Kafka
```

Later:

```text
Reconciliation / Status Inquiry
            |
            v
           T24
            |
            v
         SUCCESS
            |
            v
         Corevia
            |
            +--> transaction = SUCCESS
            |
            +--> TransferSucceeded -> outbox
            |
            v
           Kafka
```

The event stream therefore represents the transaction's lifecycle.

---

# 48. What Events Should Not Do

Corevia events should not:

* directly mutate the T24 ledger;
* replace synchronous transfer submission;
* contain secrets;
* contain unnecessary PII;
* become an independent source of financial truth;
* assume exactly-once delivery;
* assume consumers are always available;
* force all consumers to process the same business logic;
* contain infrastructure-specific exceptions.

---

# 49. Recommended Java Model

A simple event abstraction can be introduced:

```java
public interface CoreviaEvent {

    String eventId();

    String eventType();

    int eventVersion();

    Instant occurredAt();

    String correlationId();

    String transactionId();
}
```

Example:

```java
public record TransferSucceededEvent(
    String eventId,
    String transactionId,
    String correlationId,
    Instant occurredAt,
    Money amount,
    CurrencyCode currency
) implements CoreviaEvent {

    @Override
    public String eventType() {
        return "TransferSucceeded";
    }

    @Override
    public int eventVersion() {
        return 1;
    }
}
```

The exact implementation may evolve as the project is coded.

---

# 50. Recommended Package Structure

Events should remain separated from the core domain model where appropriate.

```text
com.corevia
├── domain
│   └── transfer
│
├── application
│   └── event
│
└── infrastructure
    └── kafka
        ├── producer
        ├── event
        ├── mapper
        └── configuration
```

For example:

```text
infrastructure/kafka/event/
    TransferSubmittedEvent.java
    TransferSucceededEvent.java
    TransferFailedEvent.java
    TransferUnknownEvent.java
```

Kafka-specific serialization concerns should remain in the infrastructure layer.

---

# 51. Domain Events vs Integration Events

Corevia should distinguish between:

### Domain Event

An internal representation of a domain state transition.

Example:

```text
TransferSucceeded
```

### Integration Event

The externally published representation sent through Kafka.

Example:

```json
{
  "eventType": "TransferSucceeded",
  "eventVersion": 1,
  "eventId": "evt-002",
  "transactionId": "TXN-001",
  "payload": {}
}
```

This separation prevents Kafka-specific structures from leaking throughout the domain model.

Conceptually:

```text
Domain
  |
  | Domain Event
  v
Application
  |
  | map
  v
Integration Event
  |
  v
Kafka
```

---

# 52. Summary

Corevia's event architecture can be summarized as:

```text
                 T24
                  |
                  | financial truth
                  v
               Corevia
                  |
        +---------+---------+
        |                   |
        v                   v
   PostgreSQL             Outbox
        |                   |
        |                   v
        |                 Kafka
        |                   |
        |       +-----------+-----------+
        |       |           |           |
        |       v           v           v
        |   Notification   Audit    Analytics
        |
        v
 Transaction State
```

The key principles are:

```text
1. T24 remains the financial system of record.

2. Corevia owns middleware transaction state.

3. Kafka distributes transaction lifecycle events.

4. Events use at-least-once delivery.

5. Consumers must be idempotent.

6. Transactional Outbox prevents database/Kafka dual-write inconsistency.

7. transactionId preserves business-operation identity.

8. correlationId supports technical tracing.

9. eventId supports event-level deduplication.

10. UNKNOWN is a legitimate transaction state and must be represented explicitly.
```

The resulting architecture provides asynchronous integration without turning Kafka into a second banking ledger.
