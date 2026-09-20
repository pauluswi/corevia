# Corevia — Observability

## 1. Purpose

This document defines the observability strategy for Corevia.

Observability allows operators and engineers to answer:

* Is Corevia healthy?
* Are transfers succeeding?
* Is T24 responding normally?
* Are transactions becoming `UNKNOWN`?
* Is Kafka keeping up?
* Is PostgreSQL healthy?
* Which transaction is causing a problem?
* How long does a transfer take?
* Where did a request fail?
* Is the problem inside Corevia or in a dependency?

The core principle is:

> **Every important transaction should be traceable from the incoming API request through Corevia, T24, PostgreSQL, Kafka, and the resulting business outcome.**

---

# 2. Observability Pillars

Corevia uses the standard three pillars:

```text
                 Observability
                      |
          +-----------+-----------+
          |           |           |
          v           v           v
        Logs       Metrics      Traces
```

Additionally, Corevia uses:

```text
             +----------------+
             |    Auditing    |
             +----------------+
```

because banking systems require business-level traceability beyond technical telemetry.

---

# 3. Observability Architecture

High-level architecture:

```text
                         +----------------+
                         |   API Client   |
                         +-------+--------+
                                 |
                                 | correlationId
                                 v
                         +---------------+
                         |    Corevia    |
                         +-------+-------+
                                 |
                 +---------------+---------------+
                 |               |               |
                 v               v               v
              Logs            Metrics          Traces
                 |               |               |
                 +---------------+---------------+
                                 |
                         +-------+-------+
                         | Observability |
                         |    Platform   |
                         +---------------+
```

Corevia also exposes operational metrics for:

```text
T24
PostgreSQL
Kafka
HTTP
JVM
transaction lifecycle
outbox
```

---

# 4. Recommended Technology Stack

Recommended baseline:

```text
Spring Boot Actuator
Micrometer
Prometheus
Grafana
OpenTelemetry
OpenTelemetry Collector
structured JSON logging
```

Possible production architecture:

```text
Corevia
  |
  +--> Micrometer
  |       |
  |       v
  |    Prometheus
  |       |
  |       v
  |    Grafana
  |
  +--> OpenTelemetry
          |
          v
      OTEL Collector
          |
          +--> tracing backend
          +--> log backend
```

The exact backend can vary by organization.

---

# 5. Observability Goals

Corevia should provide visibility into four levels.

### Technical health

```text
CPU
memory
JVM
threads
database connections
Kafka connections
```

### Application health

```text
request rate
error rate
latency
throughput
```

### Integration health

```text
T24 latency
T24 errors
T24 timeouts
Kafka publishing
database latency
```

### Business health

```text
successful transfers
failed transfers
UNKNOWN transfers
transaction volume
```

The last category is particularly important for banking systems.

---

# 6. Correlation ID

Every request should have a correlation ID.

Example:

```http
X-Correlation-ID: CORR-20260920-001
```

If the client does not provide one, Corevia should generate it.

The same correlation ID should be propagated through the request lifecycle where technically appropriate.

```text
Client
  |
  | CORR-001
  v
Corevia
  |
  +--> T24
  |
  +--> PostgreSQL
  |
  +--> Kafka
```

---

# 7. Transaction ID

The `transactionId` identifies the financial operation.

Example:

```text
TXN-20260920-000001
```

It should remain stable throughout the transaction lifecycle.

Example:

```text
TXN-001

RECEIVED
   |
VALIDATING
   |
SUBMITTED
   |
UNKNOWN
   |
SUCCESS
```

All important logs and business events should reference the transaction ID.

---

# 8. Correlation ID vs Transaction ID

These identifiers have different purposes.

| Identifier         | Purpose                              |
| ------------------ | ------------------------------------ |
| `correlationId`    | technical request tracing            |
| `transactionId`    | financial operation identity         |
| `idempotencyKey`   | logical duplicate-request protection |
| `t24TransactionId` | T24-side transaction identity        |
| `eventId`          | unique event identity                |

Example:

```text
correlationId   = CORR-001
transactionId   = TXN-001
idempotencyKey  = PAYROLL-ABC
t24TransactionId = T24-987
eventId         = EVT-001
```

These values must not be treated as interchangeable.

---

# 9. Structured Logging

Corevia should use structured logs rather than unstructured text.

Instead of:

```text
Transfer failed because T24 timed out
```

prefer:

```json
{
  "timestamp": "2026-09-20T10:00:00Z",
  "level": "WARN",
  "service": "corevia",
  "event": "T24_TIMEOUT",
  "transactionId": "TXN-001",
  "correlationId": "CORR-001",
  "operation": "TRANSFER",
  "dependency": "T24",
  "durationMs": 3000
}
```

Structured logs are easier to search, aggregate, and analyze.

---

# 10. Standard Log Fields

Recommended fields:

```text
timestamp
level
service
environment
event
message
correlationId
transactionId
operation
dependency
durationMs
status
errorCode
```

Additional fields can be included when useful.

---

# 11. Log Levels

Recommended usage:

### ERROR

Unexpected failures requiring investigation.

Example:

```text
database unavailable
unexpected application exception
```

### WARN

Important abnormal conditions that may not require immediate intervention.

Example:

```text
T24 timeout
transaction UNKNOWN
circuit breaker OPEN
```

### INFO

Normal business/application lifecycle events.

Example:

```text
transfer submitted
transfer completed
```

### DEBUG

Detailed development diagnostics.

Should normally be disabled or restricted in production.

### TRACE

Very detailed diagnostics.

Normally only enabled temporarily for troubleshooting.

---

# 12. Avoid Excessive Logging

Do not log every internal method call.

Bad:

```text
Entering method A
Entering method B
Entering method C
Exiting method C
Exiting method B
Exiting method A
```

This creates noise.

Prefer meaningful business and integration events:

```text
TRANSFER_RECEIVED
T24_REQUEST_SENT
T24_RESPONSE_RECEIVED
TRANSFER_SUCCESS
```

---

# 13. Sensitive Data

Logs must not contain:

```text
password
JWT
refresh token
API key
private key
database password
T24 credentials
```

Sensitive financial information should also be minimized.

For example:

```text
sourceAccount=******2345
destinationAccount=******8765
```

rather than logging complete account numbers.

---

# 14. Transaction Lifecycle Logging

A transfer should produce a traceable sequence.

Example:

```text
TRANSFER_RECEIVED
       |
       v
TRANSFER_VALIDATING
       |
       v
T24_REQUEST_SENT
       |
       v
T24_RESPONSE_RECEIVED
       |
       v
TRANSFER_SUCCESS
```

For an ambiguous failure:

```text
TRANSFER_RECEIVED
       |
       v
T24_REQUEST_SENT
       |
       X
T24_TIMEOUT
       |
       v
TRANSFER_UNKNOWN
```

---

# 15. Recommended Business Events

Useful application log events:

```text
TRANSFER_RECEIVED
TRANSFER_VALIDATION_FAILED
TRANSFER_SUBMITTED
TRANSFER_SUCCESS
TRANSFER_FAILED
TRANSFER_UNKNOWN
TRANSFER_UNKNOWN_RESOLVED
```

These events should be stable enough to support operational dashboards and investigation.

---

# 16. T24 Integration Events

Important T24-related events:

```text
T24_REQUEST_SENT
T24_RESPONSE_RECEIVED
T24_TIMEOUT
T24_CONNECTION_FAILURE
T24_HTTP_ERROR
T24_BUSINESS_ERROR
T24_STATUS_INQUIRY
```

Example:

```json
{
  "event": "T24_TIMEOUT",
  "transactionId": "TXN-001",
  "correlationId": "CORR-001",
  "operation": "TRANSFER",
  "durationMs": 3000
}
```

---

# 17. Metrics

Metrics should provide quantitative visibility.

Corevia should expose metrics through Micrometer and Prometheus-compatible endpoints.

Important categories:

```text
HTTP
Transaction
T24
Kafka
Database
Outbox
JVM
Security
```

---

# 18. HTTP Metrics

Recommended metrics:

```text
http_server_requests_seconds
```

with useful dimensions such as:

```text
method
uri
status
```

Example questions:

> How many transfer requests are arriving?

> What percentage return 4xx?

> What percentage return 5xx?

> What is P95 transfer API latency?

---

# 19. Transfer Metrics

Recommended business metrics:

```text
corevia_transfer_received_total
corevia_transfer_success_total
corevia_transfer_failed_total
corevia_transfer_unknown_total
```

These allow operators to see the financial transaction lifecycle.

Example:

```text
Received       10,000
Success         9,700
Failed            250
Unknown            50
```

The numbers should reconcile according to the reporting period and lifecycle semantics.

---

# 20. UNKNOWN Is a First-Class Metric

The following metric is particularly important:

```text
corevia_transfer_unknown_total
```

A sudden increase can indicate:

* T24 latency problems;
* network instability;
* integration failures;
* timeout configuration problems;
* dependency degradation.

Example dashboard:

```text
UNKNOWN transactions
        |
        |       *
        |      **
        |    ****
        |********
        +----------------
          time
```

An increase should trigger investigation rather than being treated as an ordinary HTTP failure.

---

# 21. T24 Metrics

Recommended:

```text
corevia_t24_requests_total
corevia_t24_errors_total
corevia_t24_timeouts_total
corevia_t24_request_duration_seconds
```

Useful labels:

```text
operation
result
error_type
```

Example:

```text
operation=transfer
result=success
```

or:

```text
operation=transfer
result=timeout
```

---

# 22. T24 Latency

Latency should be measured using a histogram.

Example:

```text
corevia_t24_request_duration_seconds
```

Useful percentiles:

```text
P50
P95
P99
```

Example:

```text
T24 transfer latency

P50 = 120 ms
P95 = 420 ms
P99 = 1.8 s
```

The exact values are environment-dependent.

---

# 23. Why P95/P99 Matter

Average latency can hide outliers.

Example:

```text
Requests:
100ms
110ms
105ms
120ms
5000ms
```

Average may appear acceptable while one request experiences severe latency.

P95/P99 reveal tail latency.

This is particularly important for banking integrations where occasional slow T24 responses can lead to:

```text
timeout
   |
   v
UNKNOWN
```

---

# 24. T24 Error Metrics

Separate technical and business errors.

Example:

```text
corevia_t24_errors_total{
    error_type="BUSINESS"
}
```

versus:

```text
corevia_t24_errors_total{
    error_type="TIMEOUT"
}
```

This prevents operators from treating:

```text
INSUFFICIENT_FUNDS
```

as the same problem as:

```text
T24_UNAVAILABLE
```

---

# 25. Kafka Metrics

Important Kafka metrics include:

```text
events published
publication failures
consumer records processed
consumer failures
consumer lag
dead-letter records
```

Example application metrics:

```text
corevia_events_published_total
corevia_events_failed_total
corevia_consumer_processed_total
corevia_consumer_failed_total
corevia_dlq_total
```

---

# 26. Kafka Consumer Lag

Consumer lag is an important operational metric.

Conceptually:

```text
Produced events
      |
      v
Kafka topic
      |
      | 100,000 events
      v
Consumer
      |
      | processed 80,000
      v
Lag = 20,000
```

Increasing lag can indicate:

* consumer slowdown;
* consumer failure;
* insufficient consumer capacity;
* downstream dependency problems.

---

# 27. Outbox Metrics

Recommended:

```text
corevia_outbox_pending
corevia_outbox_published_total
corevia_outbox_failed_total
corevia_outbox_retry_total
```

A growing pending count may indicate:

```text
Kafka unavailable
publisher failure
database issue
consumer architecture problem
```

---

# 28. Outbox Age

Count alone is not enough.

Measure the age of the oldest unpublished event.

Example:

```text
corevia_outbox_oldest_pending_age_seconds
```

An alert might trigger if:

```text
oldest pending event > operational threshold
```

This identifies situations where the system is technically running but events are becoming stale.

---

# 29. Database Metrics

Important metrics include:

```text
connection pool usage
connection acquisition time
query latency
database errors
active connections
transaction duration
```

For HikariCP, monitor:

```text
active
idle
pending
max
```

A connection pool nearing exhaustion can cause application latency before the database itself becomes unavailable.

---

# 30. JVM Metrics

Standard JVM metrics should include:

```text
heap usage
non-heap usage
GC activity
GC pause
threads
CPU
class loading
```

Spring Boot Actuator and Micrometer can expose many of these automatically.

---

# 31. JVM Memory Example

A dashboard might show:

```text
Heap Used
  |
  |        /\
  |       /  \
  |  /\  /    \__
  |_/  \/
  +----------------
```

A continuously increasing heap may indicate a memory leak.

---

# 32. API Latency Metrics

Corevia should track:

```text
GET /customers/{id}
GET /accounts/{id}
POST /transfers
GET /transfers/{id}
```

Transfer latency deserves special attention because it includes external T24 communication.

---

# 33. Transfer Latency Breakdown

Where tracing is available:

```text
POST /transfers
       |
       +--> validation       5 ms
       |
       +--> PostgreSQL      12 ms
       |
       +--> T24            220 ms
       |
       +--> PostgreSQL      10 ms
       |
       +--> response         3 ms
```

This immediately shows where latency is being introduced.

---

# 34. Distributed Tracing

Corevia should support OpenTelemetry tracing.

Conceptually:

```text
Client
  |
  v
API Gateway
  |
  v
Corevia
  |
  +--> PostgreSQL
  |
  +--> T24
  |
  +--> Kafka
```

A single trace can connect the relevant spans.

---

# 35. Trace and Span

Example:

```text
Trace ID:
4f7c9e...

Spans:

POST /transfers
 |
 +-- validateTransfer
 |
 +-- postgres.insertTransaction
 |
 +-- T24.transfer
 |
 +-- postgres.updateTransaction
 |
 +-- kafka.publishEvent
```

This allows an engineer to identify which operation consumed most of the request time.

---

# 36. Trace Attributes

Useful attributes:

```text
transactionId
correlationId
operation
dependency
environment
result
error.type
```

Avoid putting sensitive financial data into trace attributes.

Do not add:

```text
full account number
JWT
password
secret
```

---

# 37. T24 Trace Example

A transfer trace might look conceptually like:

```text
Trace: 4f7c9e

POST /api/v1/transfers        320 ms
 |
 +-- validate                  5 ms
 |
 +-- PostgreSQL INSERT        10 ms
 |
 +-- T24.transfer             280 ms
 |      |
 |      +-- network            15 ms
 |      +-- T24 processing    250 ms
 |      +-- response           15 ms
 |
 +-- PostgreSQL UPDATE        10 ms
 |
 +-- outbox                   10 ms
```

This is much more useful than a single:

```text
request took 320 ms
```

---

# 38. Tracing UNKNOWN

Tracing is especially useful when a transaction becomes UNKNOWN.

Example:

```text
POST /transfers
      |
      +--> T24.transfer
              |
              | request sent
              |
              X timeout
              |
              v
         UNKNOWN
```

The trace should clearly show:

```text
T24 operation
timeout
duration
transactionId
correlationId
```

The operator can then perform a status inquiry.

---

# 39. Audit vs Logging

These are different.

### Logs

Technical diagnostics:

```text
T24 timeout
HTTP 500
database connection failure
```

### Audit

Business/security record:

```text
transaction submitted
transaction completed
manual reconciliation performed
authorization failed
```

Logs can be rotated aggressively.

Audit records may have different retention and regulatory requirements.

---

# 40. Audit Events

Corevia should record important business lifecycle events such as:

```text
TRANSFER_CREATED
TRANSFER_SUBMITTED
TRANSFER_SUCCEEDED
TRANSFER_FAILED
TRANSFER_UNKNOWN
TRANSFER_UNKNOWN_RESOLVED
```

Security events may include:

```text
AUTHENTICATION_FAILURE
AUTHORIZATION_FAILURE
```

---

# 41. Audit Data

Example:

```json
{
  "eventType": "TRANSFER_SUCCEEDED",
  "transactionId": "TXN-001",
  "correlationId": "CORR-001",
  "actor": "mobile-app",
  "createdAt": "2026-09-20T10:00:00Z"
}
```

Audit data should contain enough information for investigation without unnecessarily duplicating sensitive information.

---

# 42. Health Checks

Corevia should expose health information through Spring Boot Actuator.

Conceptually:

```text
/actuator/health
```

Possible components:

```text
Core application
PostgreSQL
Kafka
T24
```

However, dependency health must be interpreted carefully.

A temporary T24 outage should not necessarily make every Corevia endpoint unavailable.

---

# 43. Liveness vs Readiness

These are different concepts.

### Liveness

Answers:

> Is the process alive?

```text
Corevia process running
```

### Readiness

Answers:

> Can this instance receive traffic?

```text
Corevia ready to serve requests
```

Example:

```text
liveness  -> UP
readiness -> DOWN
```

can be appropriate during dependency or startup conditions.

---

# 44. Kubernetes Health Model

For Kubernetes:

```text
Pod
 |
 +--> Liveness probe
 |
 +--> Readiness probe
 |
 +--> Startup probe
```

A typical strategy:

```text
Startup
   |
   v
Startup probe
   |
   v
Readiness
   |
   v
Traffic
```

Liveness should not be so sensitive that a temporary T24 outage causes Kubernetes to restart every Corevia pod.

---

# 45. Alerting Philosophy

Alerts should represent conditions requiring action.

Avoid alerting on every error.

Good:

```text
UNKNOWN transaction rate suddenly increases
```

Less useful:

```text
one transfer failed
```

The objective is:

> **Alert on actionable symptoms, not normal business outcomes.**

---

# 46. Recommended Alerts

Potential alerts:

### High error rate

```text
HTTP 5xx > threshold
```

### T24 timeout spike

```text
T24 timeout rate > threshold
```

### UNKNOWN spike

```text
UNKNOWN transaction rate > threshold
```

### T24 latency degradation

```text
T24 P95/P99 latency > threshold
```

### Kafka lag

```text
consumer lag > threshold
```

### Outbox backlog

```text
pending outbox events > threshold
```

### Database connection exhaustion

```text
connection pool utilization > threshold
```

### Certificate expiration

```text
certificate expiry < configured window
```

---

# 47. Example Alert: UNKNOWN Spike

```text
Alert:
CoreviaUnknownTransactionRateHigh

Condition:
UNKNOWN transactions > expected threshold

Possible causes:
- T24 timeout
- network instability
- T24 degradation
- timeout configuration
- integration issue

Investigation:
1. Check T24 latency
2. Check T24 timeout count
3. Check network errors
4. Inspect affected transaction traces
5. Perform status inquiry/reconciliation
```

---

# 48. Example Alert: T24 Latency

```text
Alert:
CoreviaT24LatencyHigh

Condition:
T24 P95 > operational threshold

Investigate:
- T24 processing time
- network latency
- connection pool
- T24 load
- Corevia timeout configuration
```

The threshold should be environment-specific rather than hard-coded into the architecture document.

---

# 49. Example Alert: Outbox Backlog

```text
Alert:
CoreviaOutboxBacklogHigh

Condition:
pending events > threshold
OR
oldest pending event > threshold
```

Investigation:

```text
Kafka availability
       |
Kafka authentication
       |
producer errors
       |
database health
       |
outbox publisher
```

---

# 50. Dashboard Design

A useful Corevia dashboard should provide four views.

```text
+-------------------+
| System Health     |
+-------------------+

+-------------------+
| API Performance   |
+-------------------+

+-------------------+
| T24 Integration   |
+-------------------+

+-------------------+
| Business Metrics  |
+-------------------+
```

---

# 51. Dashboard — System Health

Suggested panels:

```text
CPU
Memory
JVM Heap
GC
Thread Count
Pod Count
Database Connections
Kafka Connectivity
```

---

# 52. Dashboard — API

Suggested panels:

```text
Requests/sec
4xx rate
5xx rate
P50 latency
P95 latency
P99 latency
Endpoint distribution
```

Special focus:

```text
POST /api/v1/transfers
```

---

# 53. Dashboard — T24

Suggested panels:

```text
T24 Requests/sec
T24 P50
T24 P95
T24 P99
T24 Errors
T24 Timeouts
Circuit Breaker State
UNKNOWN Transactions
```

This should be one of the most important Corevia dashboards.

---

# 54. Dashboard — Kafka

Suggested panels:

```text
Events published/sec
Publication failures
Consumer throughput
Consumer lag
Dead-letter messages
Outbox pending
Oldest outbox age
```

---

# 55. Dashboard — Business

Suggested panels:

```text
Transfers received
Transfers succeeded
Transfers failed
Transfers UNKNOWN
Success rate
Failure rate
UNKNOWN rate
Transaction volume
```

Business metrics should not expose individual customer data.

---

# 56. Golden Signals

Corevia should monitor the four classic service-level signals:

```text
Latency
Traffic
Errors
Saturation
```

### Latency

How long requests take.

### Traffic

How much traffic arrives.

### Errors

How many requests fail.

### Saturation

How close resources are to capacity.

For Corevia, add:

```text
UNKNOWN transaction rate
```

as an important banking-specific signal.

---

# 57. Service-Level Objectives

Potential SLO categories:

```text
API availability
API latency
T24 integration success
event publication latency
outbox age
```

Example conceptual SLO:

```text
99.9% of API requests available
```

The actual production target should be agreed with the business and operations teams.

This document does not prescribe a universal SLA.

---

# 58. Error Budget

If an SLO is introduced, Corevia can use an error budget.

Example:

```text
SLO = 99.9%

Allowed unavailability:
0.1%
```

The error budget can help balance:

```text
feature delivery
vs
reliability work
```

This is a production operations concern rather than a domain rule.

---

# 59. Observability During an Incident

Example incident:

> Users report delayed transfers.

Investigation path:

```text
1. Check API latency
        |
2. Check T24 P95/P99
        |
3. Check T24 timeout rate
        |
4. Check UNKNOWN transactions
        |
5. Check traces for affected transactions
        |
6. Check PostgreSQL latency
        |
7. Check Kafka/outbox
```

This provides a systematic path rather than immediately assuming Corevia is the cause.

---

# 60. Transaction Investigation

Given:

```text
transactionId = TXN-001
```

an operator should be able to find:

```text
TXN-001
   |
   +--> API request
   |
   +--> correlationId
   |
   +--> T24 call
   |
   +--> T24 transaction ID
   |
   +--> database state
   |
   +--> audit events
   |
   +--> Kafka event
   |
   +--> final status
```

This is the central observability capability of Corevia.

---

# 61. Correlation Example

```text
transactionId:
TXN-001

correlationId:
CORR-001

T24 transaction:
T24-987

eventId:
EVT-001
```

Search sequence:

```text
TXN-001
   |
   +--> CORR-001
          |
          +--> T24-987
          |
          +--> EVT-001
```

An engineer can therefore correlate multiple technical systems to one financial operation.

---

# 62. Observability Data Retention

Retention should be defined separately for:

```text
application logs
metrics
traces
audit records
financial transaction records
```

They do not necessarily have the same retention requirements.

For example:

```text
Metrics
   -> relatively short operational retention

Logs
   -> operational retention

Traces
   -> often shorter retention

Audit
   -> potentially much longer retention
```

Actual retention must follow organizational, regulatory, and business requirements.

---

# 63. High-Cardinality Warning

Metrics labels must be carefully designed.

Do not use:

```text
transactionId
correlationId
accountNumber
```

as Prometheus metric labels.

Example of a bad metric:

```text
corevia_transfer_total{
    transactionId="TXN-001"
}
```

This creates potentially millions of unique time series.

Prefer:

```text
corevia_transfer_total{
    status="SUCCESS"
}
```

Use logs and traces for individual transaction identifiers.

---

# 64. Metrics vs Logs vs Traces

Use each tool for the right purpose.

| Question                                | Best Tool     |
| --------------------------------------- | ------------- |
| How many transfers failed?              | Metrics       |
| Is T24 latency increasing?              | Metrics       |
| Why did TXN-001 fail?                   | Logs + Trace  |
| What happened to TXN-001?               | Trace + Audit |
| How many UNKNOWN transactions exist?    | Metrics       |
| Which T24 call timed out?               | Logs + Trace  |
| Is Kafka lagging?                       | Metrics       |
| Who performed an administrative action? | Audit         |

---

# 65. Observability and Security

Observability must not bypass security controls.

Protect:

```text
Prometheus
Grafana
Tracing backend
Log platform
Actuator
```

These systems can contain sensitive operational information.

Access should be controlled through appropriate authentication and authorization.

---

# 66. Production Observability Topology

A possible deployment:

```text
                         +----------------+
                         |    Grafana     |
                         +-------+--------+
                                 |
                                 v
                         +----------------+
                         |   Prometheus   |
                         +-------+--------+
                                 |
                                 |
Corevia -------------------------+
   |
   +--> OpenTelemetry
   |        |
   |        v
   |   OTEL Collector
   |        |
   |        +--> Trace Backend
   |        |
   |        +--> Log Backend
   |
   +--> Actuator
```

The exact observability platform is environment-specific.

---

# 67. Local Development

For the portfolio project, a lightweight local setup is sufficient.

Possible Docker Compose components:

```text
Corevia
PostgreSQL
Kafka
Mock T24
Prometheus
Grafana
```

Optional:

```text
OpenTelemetry Collector
Jaeger
```

This allows the complete transaction flow to be demonstrated locally.

---

# 68. Local Demo

A useful demonstration:

```text
1. Start Docker Compose
2. Start Corevia
3. Send transfer request
4. Open Grafana
5. Observe transfer metrics
6. Inspect transaction trace
7. Simulate T24 timeout
8. Observe UNKNOWN metric
9. Resolve UNKNOWN through status inquiry
10. Observe SUCCESS
```

This makes observability a visible portfolio feature rather than merely documentation.

---

# 69. Recommended Demo Dashboard

A portfolio dashboard could show:

```text
+------------------------------------------------+
|              CORE VIA                          |
|            Banking Middleware                  |
+------------------------------------------------+

Transfers        10,245
Success           9,870
Failed              340
Unknown              35

+----------------+  +---------------------------+
| Transfer Rate  |  | Transfer Latency          |
|      /\        |  |       /\                  |
|  /\ /  \__     |  |  ___/  \___              |
+----------------+  +---------------------------+

+----------------+  +---------------------------+
| T24 P95        |  | T24 Timeouts              |
|    420 ms      |  |      12                   |
+----------------+  +---------------------------+

+----------------+  +---------------------------+
| Kafka Lag      |  | Outbox Pending            |
|      25        |  |       3                   |
+----------------+  +---------------------------+
```

The actual dashboard implementation can be created later.

---

# 70. Final Observability Model

Corevia's observability model can be summarized as:

```text
                       TRANSACTION
                            |
                            v
                     transactionId
                            |
              +-------------+-------------+
              |             |             |
              v             v             v
           Logs          Metrics        Trace
              |             |             |
              +-------------+-------------+
                            |
                            v
                         Audit
                            |
              +-------------+-------------+
              |             |             |
              v             v             v
             T24       PostgreSQL       Kafka
```

The key design principle is:

> **Metrics tell us that something is wrong. Logs and traces help explain why. Audit tells us what happened from a business and operational perspective.**

For Corevia, the most important observability capability is the ability to follow a single financial transaction across the entire integration path:

```text
API Request
    |
    v
Corevia
    |
    v
T24
    |
    v
Transaction State
    |
    +--> SUCCESS
    +--> FAILED
    +--> UNKNOWN
              |
              v
       Status Inquiry
              |
              v
          Resolution
    |
    v
Outbox
    |
    v
Kafka
```

This makes observability an integral part of the architecture rather than an afterthought.
