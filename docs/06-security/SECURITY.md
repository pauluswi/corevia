# Corevia — Security

## 1. Purpose

This document defines the security architecture for Corevia.

Corevia sits between digital channels and a core banking system. It therefore handles sensitive financial requests and must protect:

* APIs;
* authentication credentials;
* authorization boundaries;
* transaction data;
* account information;
* T24 integration;
* Kafka communication;
* PostgreSQL;
* operational logs;
* secrets;
* administrative endpoints.

The security architecture follows the principle:

> **Authenticate every caller, authorize every protected operation, minimize sensitive data, protect every integration boundary, and never expose secrets through code or logs.**

---

# 2. Security Architecture

The high-level security model is:

```text
                         External Client
                              |
                              | HTTPS
                              v
                       +--------------+
                       | API Gateway  |
                       +------+-------+
                              |
                              | JWT
                              v
                       +--------------+
                       |   Corevia    |
                       +------+-------+
                              |
              +---------------+---------------+
              |               |               |
              v               v               v
          PostgreSQL        Kafka            T24
              |               |               |
              |               |               |
         TLS / ACLs       TLS / ACLs     TLS / mTLS
```

Each trust boundary requires explicit security controls.

---

# 3. Security Principles

Corevia follows these principles:

### 3.1 Least privilege

Every user, service, database account, and Kafka client receives only the permissions it requires.

### 3.2 Defense in depth

Security must not depend on a single control.

Example:

```text
HTTPS
 +
JWT
 +
Authorization
 +
Input validation
 +
Database access control
 +
Audit
```

### 3.3 Secure by default

Endpoints and integrations should require explicit configuration rather than relying on permissive defaults.

### 3.4 Secrets are externalized

Passwords, tokens, private keys, and certificates must not be stored in source code.

### 3.5 Minimize sensitive data

Corevia should store and transmit only data required for its responsibilities.

### 3.6 Fail securely

Authentication, authorization, validation, and security infrastructure failures should not result in unauthorized access.

---

# 4. Trust Boundaries

Corevia has several important trust boundaries.

```text
+------------------+
| External Client  |
+--------+---------+
         |
      HTTPS/JWT
         |
         v
+------------------+
|     Corevia      |
+--------+---------+
         |
    +----+----+
    |         |
    v         v
 PostgreSQL  T24
    |
    v
  Kafka
```

The main boundaries are:

1. Client → Corevia
2. Corevia → T24
3. Corevia → PostgreSQL
4. Corevia → Kafka
5. Operations → management endpoints

Each boundary should have authentication, authorization, encryption, and monitoring appropriate to the environment.

---

# 5. API Transport Security

All external API communication must use HTTPS.

Development:

```text
http://localhost
```

may be acceptable for local development.

Production:

```text
https://api.corevia.example
```

must use TLS.

Plain HTTP must not be exposed for production API traffic.

---

# 6. TLS

TLS protects:

```text
Client
  |
  | encrypted
  v
Corevia
```

TLS should provide:

* confidentiality;
* integrity;
* server authentication.

Production TLS configuration should use currently supported protocol versions and strong cipher configuration appropriate to the deployment platform.

Certificate management should be automated where possible.

---

# 7. API Authentication

Corevia should use OAuth 2.0 / OpenID Connect-compatible JWT bearer tokens for API authentication.

Example:

```http
Authorization: Bearer <access-token>
```

The JWT should contain claims such as:

```json id="m8r4k2"
{
  "sub": "mobile-app",
  "iss": "https://identity.example",
  "aud": "corevia-api",
  "scope": "transfer:write transfer:read",
  "exp": 1790000000
}
```

The exact identity provider is implementation-dependent.

---

# 8. JWT Validation

Corevia must validate at least:

* signature;
* issuer (`iss`);
* audience (`aud`);
* expiration (`exp`);
* token validity;
* required scopes/authorities.

Conceptually:

```text
JWT
 |
 +--> Signature valid?
 |
 +--> Issuer valid?
 |
 +--> Audience valid?
 |
 +--> Not expired?
 |
 +--> Required scope?
 |
 v
Allow request
```

An invalid token must result in an authentication failure.

---

# 9. Authentication vs Authorization

These are separate concerns.

### Authentication

Answers:

> Who is calling Corevia?

Example:

```text
sub = mobile-app
```

### Authorization

Answers:

> What is this caller allowed to do?

Example:

```text
scope = transfer:write
```

Therefore:

```text
Authenticated != automatically authorized
```

---

# 10. API Scopes

Recommended initial scopes:

```text
transfer:read
transfer:write
account:read
customer:read
```

Example:

```text
POST /api/v1/transfers
```

requires:

```text
transfer:write
```

While:

```text
GET /api/v1/transfers/{transactionId}
```

requires:

```text
transfer:read
```

---

# 11. Endpoint Authorization

Example policy:

| Endpoint              | Required Authority                           |
| --------------------- | -------------------------------------------- |
| `GET /customers/{id}` | `customer:read`                              |
| `GET /accounts/{id}`  | `account:read`                               |
| `POST /transfers`     | `transfer:write`                             |
| `GET /transfers/{id}` | `transfer:read`                              |
| `GET /health`         | public or restricted depending on deployment |
| Actuator endpoints    | operations/admin only                        |

Authorization should be enforced server-side.

The client must never be trusted to enforce authorization itself.

---

# 12. Example Spring Security Configuration

Conceptually:

```java id="q7m2v9"
@Bean
SecurityFilterChain securityFilterChain(HttpSecurity http)
        throws Exception {

    return http
        .csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/api/v1/health").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/v1/transfers")
                .hasAuthority("SCOPE_transfer:write")
            .requestMatchers(HttpMethod.GET, "/api/v1/transfers/**")
                .hasAuthority("SCOPE_transfer:read")
            .anyRequest().authenticated()
        )
        .oauth2ResourceServer(oauth2 ->
            oauth2.jwt(Customizer.withDefaults())
        )
        .build();
}
```

The exact configuration may evolve with the selected identity provider.

---

# 13. Authentication Failure

Invalid or missing authentication should result in:

```http
401 Unauthorized
```

Example:

```json id="f4x8m1"
{
  "code": "UNAUTHORIZED",
  "message": "Authentication is required",
  "correlationId": "CORR-001"
}
```

Do not expose token validation details.

Avoid messages such as:

```text
"JWT signature failed because key ID xyz is unknown"
```

to external clients.

---

# 14. Authorization Failure

An authenticated client without sufficient permission should receive:

```http
403 Forbidden
```

Example:

```json id="w9r3k5"
{
  "code": "FORBIDDEN",
  "message": "Insufficient permission",
  "correlationId": "CORR-002"
}
```

The response should not reveal unnecessary internal authorization details.

---

# 15. Request Validation

Security starts before business processing.

Corevia must validate:

* required fields;
* field lengths;
* account identifier format;
* currency;
* amount;
* reference;
* idempotency key;
* JSON structure.

Example:

```text
amount <= 0
        |
        v
400 Bad Request
```

rather than allowing invalid data to reach T24.

---

# 16. Input Size Limits

Corevia should impose reasonable limits on:

* HTTP request body;
* header size;
* reference length;
* idempotency-key length;
* query parameters.

Example:

```text
reference <= 140 characters
idempotency-key <= 255 characters
```

These limits reduce abuse and accidental resource consumption.

---

# 17. Injection Protection

Corevia must protect against common injection attacks.

### SQL Injection

Use parameterized queries / JPA repositories.

Avoid:

```java id="s5k9p2"
String sql = "SELECT ... WHERE id = '" + input + "'";
```

Prefer parameter binding.

### JSON Injection

Use controlled serialization/deserialization.

### Command Injection

Do not construct operating-system commands from API input.

---

# 18. Database Security

Corevia should use a dedicated database identity.

Example:

```text
corevia_app
```

The application account should not be a PostgreSQL superuser.

Recommended separation:

```text
Application
    |
    v
corevia_app
    |
    +--> required schema permissions
```

Avoid:

```text
Corevia
   |
   v
postgres superuser
```

---

# 19. Database Credentials

Credentials must be externalized.

Example:

```yaml id="p7n4c8"
spring:
  datasource:
    url: ${DB_URL}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
```

Never commit:

```text
DB_PASSWORD=MyRealPassword
```

to Git.

---

# 20. Secrets Management

Production secrets should come from a dedicated secret-management mechanism.

Possible platforms include:

```text
AWS Secrets Manager
AWS Systems Manager Parameter Store
GCP Secret Manager
Kubernetes Secrets
Enterprise Vault
```

For the portfolio implementation, environment variables or local `.env` configuration may be used for development.

Real production secrets must never be committed to the repository.

---

# 21. T24 Credentials

T24 credentials must be isolated from the rest of the application.

Conceptually:

```text
Secret Manager
      |
      v
T24 Client Configuration
      |
      v
T24
```

The credentials should not appear in:

* Java source code;
* Git;
* Docker image layers;
* API responses;
* logs;
* Kafka events.

---

# 22. T24 Authentication

The exact authentication mechanism depends on the real T24/Transact deployment.

The adapter should therefore abstract authentication configuration.

Example:

```text
T24Adapter
    |
    v
T24Client
    |
    +--> authentication
    +--> TLS
    +--> timeout
    +--> HTTP communication
```

The business/domain layer should not know how T24 authentication works.

---

# 23. Mutual TLS

For high-trust service-to-service communication, mTLS may be used.

Example:

```text
Corevia
   |
   | client certificate
   v
T24 Integration Layer
   |
   | server certificate
   v
Corevia
```

mTLS provides:

* server authentication;
* client authentication;
* encrypted communication.

The exact use of mTLS depends on the actual integration platform.

For the portfolio project, it can be documented as a production deployment option.

---

# 24. T24 Network Security

Production topology should avoid exposing T24 directly to the public internet.

Preferred:

```text
Internet
   |
API Gateway
   |
Application Network
   |
Corevia
   |
Private Network
   |
T24
```

Possible controls:

* private subnets;
* security groups;
* network ACLs;
* firewall rules;
* allowlists;
* private DNS;
* service mesh controls where appropriate.

---

# 25. Kafka Security

Kafka communication should be protected with:

```text
TLS
+
Authentication
+
Authorization
```

For example:

```text
Corevia Producer
      |
      | TLS/SASL
      v
Kafka Cluster
```

Corevia should have only the permissions required to:

* publish to Corevia topics;
* consume from topics if required.

---

# 26. Kafka Topic Authorization

Example conceptual permissions:

```text
corevia-service
    |
    +--> WRITE corevia.transfer.events
```

A notification consumer might have:

```text
notification-service
    |
    +--> READ corevia.transfer.events
```

A consumer should not automatically have:

```text
WRITE
```

permission unless required.

---

# 27. PostgreSQL TLS

Production PostgreSQL connections should use TLS where the deployment architecture requires it.

Conceptually:

```text
Corevia
   |
   | TLS
   v
PostgreSQL
```

Database certificates should be managed through the deployment environment rather than committed into the repository.

---

# 28. Data Minimization

Corevia should store only the data necessary for its responsibilities.

For example:

```text
Required:
transactionId
sourceAccount
destinationAccount
amount
currency
status
```

Potentially unnecessary:

```text
full customer profile
customer password
authentication token
unrelated customer attributes
```

The architecture should avoid turning Corevia into an unnecessary copy of T24 data.

---

# 29. Account Number Protection

Account identifiers are sensitive financial information.

Depending on the use case, Corevia may store the full account number because it needs it to perform a T24 transaction.

However, exposure should be minimized.

For logs:

```text
1000012345
```

should become something such as:

```text
******2345
```

or an equivalent controlled masking strategy.

---

# 30. Sensitive Data in APIs

API responses should contain only information required by the caller.

Avoid returning:

```text
T24 credentials
internal hostnames
database identifiers
internal stack traces
secret configuration
```

Example safe response:

```json id="n2v7k4"
{
  "transactionId": "TXN-001",
  "status": "SUCCESS",
  "amount": 1500000,
  "currency": "IDR"
}
```

---

# 31. Secure Logging

Logs must never contain:

```text
password
access token
refresh token
private key
API secret
database password
```

Avoid logging full sensitive account information.

Instead:

```text
sourceAccount=******2345
```

Use structured logs.

Example:

```json id="q8m4x1"
{
  "level": "INFO",
  "event": "TRANSFER_COMPLETED",
  "transactionId": "TXN-001",
  "correlationId": "CORR-001",
  "status": "SUCCESS"
}
```

---

# 32. Correlation ID Security

Clients may provide a correlation ID, but Corevia should validate its format and length.

Example:

```text
X-Correlation-ID: CORR-001
```

The value should not be allowed to contain unbounded data.

Correlation IDs should also be treated as untrusted input.

---

# 33. Error Responses

Production APIs must not expose stack traces.

Bad:

```json id="m7q3x9"
{
  "error": "NullPointerException",
  "stackTrace": "..."
}
```

Preferred:

```json id="c8v2k5"
{
  "code": "INTERNAL_ERROR",
  "message": "An internal error occurred",
  "correlationId": "CORR-001"
}
```

Operators can use the correlation ID to locate the detailed internal logs.

---

# 34. Business Error vs Security Error

Corevia should distinguish:

### Authentication failure

```text
401
```

### Authorization failure

```text
403
```

### Invalid request

```text
400
```

### Business rejection

Example:

```text
INSUFFICIENT_FUNDS
```

may result in:

```text
422
```

depending on the API contract.

### Unknown financial outcome

The transaction may remain:

```text
UNKNOWN
```

rather than being converted into a generic:

```text
500
```

The HTTP response and transaction state have different meanings.

---

# 35. Rate Limiting

Corevia should support rate limiting at the API gateway or application layer.

Example:

```text
Client
  |
  | 10,000 requests/minute
  v
API Gateway
  |
  X rate limit exceeded
```

This protects against:

* accidental traffic spikes;
* abusive clients;
* resource exhaustion;
* uncontrolled retry storms.

Rate limits should be appropriate to the business channel.

---

# 36. Retry Safety

Security and reliability overlap when handling financial requests.

A client must not blindly retry a transfer after a timeout.

Example:

```text
Client
  |
  | transfer
  v
Corevia
  |
  v
T24
  |
  | transaction succeeds
  |
  X response lost
```

The client sees a timeout but the financial operation may already have succeeded.

Corevia therefore uses:

```text
Idempotency
+
UNKNOWN
+
Status Inquiry
```

to avoid accidental duplicate transfers.

---

# 37. Idempotency as a Security Control

Idempotency is not only a reliability feature.

It also limits the effect of repeated requests.

Example:

```text
POST /transfers
Idempotency-Key: ABC-123
```

Repeated requests should resolve to the same logical transaction.

This reduces the risk of:

```text
duplicate submission
```

caused by:

* client retries;
* network retries;
* gateway retries;
* user double-clicks;
* application-level retry logic.

---

# 38. Replay Protection

The idempotency mechanism should have an appropriate retention period.

After an idempotency record expires, the same key may become reusable depending on the business policy.

For financial operations, the retention window must be long enough to cover the expected retry/reconciliation period.

The exact duration should be configuration-driven.

---

# 39. JWT Replay Considerations

Access tokens should have controlled lifetimes.

Corevia should validate:

```text
exp
iss
aud
signature
```

Where appropriate, additional token controls may be implemented by the identity platform.

Corevia should not attempt to build its own identity provider.

---

# 40. Administrative Endpoints

Spring Boot Actuator endpoints must not be exposed publicly without protection.

Examples:

```text
/actuator/health
/actuator/metrics
/actuator/prometheus
```

A typical strategy is:

```text
/actuator/health
    |
    +--> limited exposure

/actuator/metrics
    |
    +--> internal/authorized

/actuator/prometheus
    |
    +--> monitoring network only
```

Sensitive actuator endpoints should never be anonymously accessible from the public internet.

---

# 41. Health Checks

Health endpoints should avoid revealing sensitive implementation information.

Avoid returning:

```text
database password
T24 hostname
Kafka credentials
internal configuration
```

A basic health response can be:

```json id="r5k8m2"
{
  "status": "UP"
}
```

Detailed dependency diagnostics should be restricted to authorized operational users.

---

# 42. Dependency Security

Dependencies should be kept current and scanned for known vulnerabilities.

The build pipeline should consider:

```text
Dependency vulnerability scanning
+
SAST
+
Secret scanning
+
Container scanning
```

Potential tools include:

```text
OWASP Dependency-Check
Trivy
GitHub Dependabot
Semgrep
SonarQube
```

The exact tooling can be selected during CI/CD implementation.

---

# 43. Container Security

The Corevia Docker image should:

* use a minimal base image;
* run as a non-root user;
* avoid embedding secrets;
* expose only required ports;
* use read-only filesystem where practical;
* receive configuration through environment/secrets;
* be scanned for vulnerabilities.

Conceptually:

```dockerfile id="u4m7q2"
USER 1001
```

rather than running the application as root.

---

# 44. Kubernetes Security

For future Kubernetes deployment:

```text
Pod
 |
 +--> Service Account
 +--> Network Policy
 +--> Secret reference
 +--> Resource limits
 +--> Security Context
```

Recommended controls include:

* non-root containers;
* restricted security context;
* network policies;
* RBAC;
* secret management;
* resource limits;
* pod security controls.

---

# 45. Service Account

Corevia should use a dedicated workload identity/service account.

Avoid sharing one credential across multiple services.

Example:

```text
corevia-service-account
```

should have only the permissions required by Corevia.

This principle applies to:

* AWS IAM;
* GCP IAM;
* Kubernetes RBAC;
* Kafka ACLs;
* database permissions.

---

# 46. Cloud IAM

If deployed to AWS or GCP, cloud permissions should follow least privilege.

For example, Corevia may need:

```text
Read specific secret
Publish specific Kafka topic
Write specific object if required
Read monitoring configuration
```

It should not receive unrestricted permissions such as:

```text
AdministratorAccess
```

for normal application execution.

---

# 47. Secret Rotation

Secrets should support rotation without requiring source-code changes.

Examples:

```text
T24 credential
Database password
Kafka credential
TLS certificate
```

A production implementation should define:

```text
Secret
  |
  v
Secret Manager
  |
  v
Corevia
  |
  v
rotation
```

The exact rotation mechanism depends on the infrastructure platform.

---

# 48. Certificate Management

Certificates used for TLS/mTLS should have:

* expiration monitoring;
* controlled issuance;
* secure storage;
* rotation procedures.

A certificate nearing expiration should generate an operational alert before service interruption occurs.

---

# 49. Auditability

Security-sensitive operations should be auditable.

Examples:

```text
Authentication failure
Authorization failure
Administrative configuration change
Transaction status inquiry
Manual reconciliation
Security configuration change
```

Audit records should include enough information to answer:

```text
Who?
What?
When?
Which transaction?
Which correlation ID?
What was the result?
```

Sensitive credentials must never be stored in audit logs.

---

# 50. Security Event Example

Example:

```json id="k6p2v8"
{
  "eventType": "AUTHORIZATION_FAILURE",
  "actor": "client-123",
  "endpoint": "POST /api/v1/transfers",
  "correlationId": "CORR-900",
  "timestamp": "2026-09-20T08:30:12Z"
}
```

The exact actor representation depends on the identity architecture.

---

# 51. Threat Model

The initial Corevia threat model considers:

| Threat                    | Example                            | Primary Control             |
| ------------------------- | ---------------------------------- | --------------------------- |
| Unauthorized API access   | invalid/missing JWT                | OAuth2/JWT                  |
| Privilege escalation      | read-only client performs transfer | scopes                      |
| Credential leakage        | secret committed to Git            | secret management           |
| SQL injection             | malicious account input            | parameterized queries       |
| Duplicate transfer        | client retry                       | idempotency                 |
| Replay                    | repeated financial request         | idempotency + authorization |
| Data interception         | HTTP traffic                       | TLS                         |
| T24 impersonation         | fake integration endpoint          | TLS/mTLS                    |
| Kafka unauthorized access | rogue producer                     | ACLs/authentication         |
| Sensitive log exposure    | full account number                | masking                     |
| DoS/resource exhaustion   | excessive requests                 | rate limiting               |
| Container compromise      | vulnerable/root container          | scanning + non-root         |
| Secret expiration         | expired certificate                | rotation/monitoring         |

---

# 52. Security Boundaries in the Transfer Flow

A transfer request crosses several controls:

```text
Client
  |
  | HTTPS
  v
API Gateway
  |
  | JWT
  v
Corevia API
  |
  | Authorization
  v
Application Service
  |
  | Validation
  v
Idempotency
  |
  | controlled request
  v
T24 Adapter
  |
  | TLS / mTLS
  v
T24
```

After the result:

```text
T24
 |
 v
Corevia
 |
 +--> PostgreSQL
 |      |
 |      +--> transaction
 |      +--> audit
 |      +--> outbox
 |
 +--> Kafka
        |
        +--> authenticated consumers
```

---

# 53. Security and Hexagonal Architecture

Security infrastructure should remain separated from domain logic.

Example:

```text
api
 |
 +--> authentication
 +--> authorization
 +--> request validation
 |
application
 |
 +--> business orchestration
 |
domain
 |
 +--> business rules
 |
infrastructure
 |
 +--> JWT infrastructure
 +--> T24 security
 +--> Kafka security
 +--> database security
```

The domain should not contain code such as:

```java
SecurityContextHolder
```

or JWT parsing logic.

Security concerns belong at the appropriate application/infrastructure boundary.

---

# 54. Security and T24 Adapter

The T24 adapter should hide integration security details from the application layer.

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
     +--> TLS
     +--> mTLS
     +--> authentication
     +--> timeout
```

The application should simply request:

```java id="n3v7q1"
coreBankingGateway.transfer(request);
```

It should not know:

```text
certificate path
client secret
T24 endpoint
authentication protocol
```

---

# 55. Security Configuration

Security-sensitive configuration should be externalized.

Example:

```yaml id="f8m2r5"
corevia:
  security:
    jwt:
      issuer-uri: ${JWT_ISSUER_URI}
      audience: ${JWT_AUDIENCE}

  t24:
    base-url: ${T24_BASE_URL}
    connect-timeout: ${T24_CONNECT_TIMEOUT}
    read-timeout: ${T24_READ_TIMEOUT}
```

Secrets should be supplied separately.

---

# 56. Development vs Production

The project should clearly distinguish development configuration from production security.

### Local development

May use:

```text
local JWT configuration
mock T24
Docker Compose
development credentials
HTTP localhost
```

### Production

Should use:

```text
HTTPS
real identity provider
TLS/mTLS
secret manager
private networking
Kafka authentication
database TLS
centralized logging
monitoring
security scanning
```

The POC must never imply that a local development configuration is production-ready.

---

# 57. Mock T24 Security

The Mock T24 service is a development component.

It may use simplified authentication for local testing.

Example:

```text
Corevia
   |
   | local network
   v
Mock T24
```

However, the adapter design should still support the production security model:

```text
T24Client
   |
   +--> authentication
   +--> TLS
   +--> timeout
```

This allows the mock to be replaced without changing business logic.

---

# 58. Security Testing

Security testing should include:

### Authentication

* missing token;
* expired token;
* invalid signature;
* invalid issuer;
* invalid audience.

### Authorization

* missing scope;
* wrong scope;
* unauthorized endpoint.

### Input validation

* malformed account;
* negative amount;
* oversized reference;
* oversized idempotency key.

### Injection

* SQL injection payloads;
* malicious JSON;
* unexpected characters.

### Secrets

* verify no secrets are committed;
* verify logs do not expose credentials.

### Transport

* TLS configuration;
* invalid certificate;
* mTLS failure where enabled.

---

# 59. Security Test Examples

Example:

```text
POST /api/v1/transfers
Authorization: none
```

Expected:

```text
401 Unauthorized
```

Example:

```text
POST /api/v1/transfers
Authorization: Bearer <read-only-token>
```

Expected:

```text
403 Forbidden
```

Example:

```text
POST /api/v1/transfers
Authorization: Bearer <valid-transfer-token>
```

Expected:

```text
request reaches application validation
```

---

# 60. Security Headers

Where Corevia is directly exposed to HTTP clients, appropriate security headers should be considered.

Depending on deployment:

```text
Strict-Transport-Security
Content-Security-Policy
X-Content-Type-Options
Referrer-Policy
```

For a JSON-only backend, the exact header policy should be aligned with the API gateway and client architecture.

---

# 61. CORS

If Corevia is called directly by browser-based applications, CORS must be explicitly configured.

Avoid:

```text
Access-Control-Allow-Origin: *
```

for sensitive production APIs unless there is a specific reason.

Prefer explicit trusted origins.

Example:

```text
https://app.example.com
```

For service-to-service APIs, CORS may not be necessary at all.

---

# 62. CSRF

If authentication uses bearer tokens in the Authorization header and the API is not using browser cookies for authentication, traditional CSRF exposure is reduced.

The final configuration should still follow the chosen authentication architecture.

The security model must not blindly disable CSRF without understanding how authentication is implemented.

---

# 63. Security Monitoring

Security-relevant metrics and alerts may include:

```text
authentication failures
authorization failures
rate-limit violations
unusual transaction failure rates
T24 authentication failures
Kafka authentication failures
database authentication failures
certificate expiration
secret rotation failures
```

Operational monitoring should correlate these events with:

```text
correlationId
service
timestamp
environment
```

where appropriate.

---

# 64. Incident Response Considerations

If a security incident occurs, Corevia should provide enough telemetry to answer:

```text
What happened?
When?
Which service?
Which client?
Which transaction?
Which dependency?
What data was affected?
```

Correlation and audit records are therefore important.

Incident handling should also consider whether any financial transactions entered:

```text
UNKNOWN
```

during the incident.

---

# 65. Security Checklist

### API

* [ ] HTTPS enabled
* [ ] JWT validation configured
* [ ] issuer validated
* [ ] audience validated
* [ ] expiration validated
* [ ] scopes enforced
* [ ] request validation enabled
* [ ] rate limiting configured

### Database

* [ ] dedicated application user
* [ ] least privilege
* [ ] TLS where required
* [ ] credentials externalized
* [ ] backups protected
* [ ] sensitive data minimized

### T24

* [ ] private network connectivity
* [ ] TLS
* [ ] mTLS where required
* [ ] credentials externalized
* [ ] timeout configured
* [ ] certificate monitoring

### Kafka

* [ ] TLS
* [ ] authentication
* [ ] topic ACLs
* [ ] least privilege
* [ ] consumer authorization

### Application

* [ ] secure error responses
* [ ] secrets excluded from logs
* [ ] account identifiers masked where appropriate
* [ ] idempotency enabled
* [ ] UNKNOWN handling implemented
* [ ] audit logging implemented

### Container

* [ ] non-root user
* [ ] minimal base image
* [ ] vulnerability scanning
* [ ] no embedded secrets
* [ ] resource limits

### Kubernetes / Cloud

* [ ] workload identity
* [ ] least-privilege IAM
* [ ] network policies
* [ ] secret manager
* [ ] security context

---

# 66. Summary

Corevia's security architecture is based on multiple layers:

```text
                    External Client
                          |
                       HTTPS
                          |
                          v
                  +---------------+
                  | API Gateway   |
                  +-------+-------+
                          |
                        JWT
                          |
                          v
                  +---------------+
                  |    Corevia    |
                  +-------+-------+
                          |
              +-----------+-----------+
              |           |           |
             TLS         TLS         TLS
              |           |           |
              v           v           v
             T24      PostgreSQL     Kafka
              |           |           |
             mTLS       ACL/TLS     ACL/TLS
```

The core principles are:

```text
1. Authenticate every protected API request.

2. Authorize operations using explicit scopes/roles.

3. Encrypt communication across trust boundaries.

4. Keep T24 credentials, database credentials and Kafka credentials
   outside source code.

5. Minimize sensitive financial data.

6. Mask sensitive information in logs.

7. Use idempotency to protect financial operations from duplicate requests.

8. Treat UNKNOWN as a financial integration state, not simply an HTTP error.

9. Apply least privilege to databases, Kafka, cloud IAM and workloads.

10. Keep security infrastructure outside the domain model.

11. Audit security-sensitive operations.

12. Distinguish local development security from production security.
```

This gives Corevia a security architecture appropriate for a **banking integration middleware showcase**, while keeping the implementation boundaries realistic and avoiding the claim that a portfolio POC is equivalent to a production bank security platform.
