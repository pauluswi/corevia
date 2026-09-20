# Corevia API Specification

## 1. Purpose

This document defines the external REST API contract for **Corevia**, a banking integration middleware connecting digital channels with a T24 / Temenos Transact-style core banking system.

The specification covers:

* API endpoints
* request and response models
* transaction lifecycle
* idempotency
* authentication
* correlation IDs
* HTTP status codes
* error handling
* validation rules
* pagination and filtering where applicable

The API is designed for digital banking channels such as:

* Mobile banking
* Internet banking
* Internal banking applications
* Partner applications
* API consumers

---

# 2. API Design Principles

Corevia follows these principles:

1. RESTful resource-oriented APIs
2. JSON request and response bodies
3. Versioned APIs
4. Explicit transaction states
5. Idempotency for financial operations
6. Correlation IDs for distributed tracing
7. Consistent error responses
8. No exposure of internal T24 data structures
9. Core banking remains the system of record
10. Technical failures are distinguished from business failures

---

# 3. Base URL

For local development:

```text
http://localhost:8080
```

All business APIs use:

```text
/api/v1
```

Example:

```text
http://localhost:8080/api/v1/accounts/1000012345
```

---

# 4. Common HTTP Headers

## 4.1 Authorization

Protected APIs require:

```http
Authorization: Bearer <JWT>
```

Authentication and authorization details are defined in `SECURITY.md`.

---

## 4.2 Correlation ID

Clients may provide:

```http
X-Correlation-Id: 8b7d6f21-7e4a-4b42-a2d8-1f8e3f5f1234
```

If omitted, Corevia generates a correlation ID.

The correlation ID is:

* returned in the response
* included in application logs
* propagated to downstream calls
* associated with audit records

Example:

```http
X-Correlation-Id: 8b7d6f21-7e4a-4b42-a2d8-1f8e3f5f1234
```

---

## 4.3 Idempotency Key

Financial transaction APIs require:

```http
Idempotency-Key: 7c8f2d10-8f4a-4c31-a2bd-123456789abc
```

The key must uniquely identify a logical client request.

It is mandatory for:

```text
POST /api/v1/transfers
```

---

# 5. API Overview

| Method | Endpoint                            | Purpose                    |
| ------ | ----------------------------------- | -------------------------- |
| GET    | `/api/v1/customers/{customerId}`    | Customer inquiry           |
| GET    | `/api/v1/accounts/{accountId}`      | Account inquiry            |
| POST   | `/api/v1/transfers`                 | Submit funds transfer      |
| GET    | `/api/v1/transfers/{transactionId}` | Transaction status inquiry |
| GET    | `/api/v1/health`                    | Application health         |

---

# 6. Customer Inquiry

## 6.1 Endpoint

```http
GET /api/v1/customers/{customerId}
```

Returns basic customer information.

Corevia retrieves the information through the core-banking adapter.

---

## 6.2 Path Parameters

| Parameter    | Type   | Required | Description                      |
| ------------ | ------ | -------: | -------------------------------- |
| `customerId` | string |      Yes | Core banking customer identifier |

Example:

```http
GET /api/v1/customers/CUST000123
```

---

## 6.3 Response — 200 OK

```json
{
  "customerId": "CUST000123",
  "fullName": "John Doe",
  "status": "ACTIVE"
}
```

---

## 6.4 Customer Response

```json
{
  "customerId": "string",
  "fullName": "string",
  "status": "ACTIVE"
}
```

Possible customer statuses:

```text
ACTIVE
BLOCKED
CLOSED
```

---

## 6.5 Errors

### Customer Not Found

```http
404 Not Found
```

```json
{
  "code": "CUSTOMER_NOT_FOUND",
  "message": "Customer was not found",
  "correlationId": "8b7d6f21-7e4a-4b42-a2d8-1f8e3f5f1234",
  "timestamp": "2026-09-20T08:00:00Z"
}
```

---

# 7. Account Inquiry

## 7.1 Endpoint

```http
GET /api/v1/accounts/{accountId}
```

Returns account information retrieved from the core banking system.

---

## 7.2 Path Parameters

| Parameter   | Type   | Required | Description                     |
| ----------- | ------ | -------: | ------------------------------- |
| `accountId` | string |      Yes | Core banking account identifier |

Example:

```http
GET /api/v1/accounts/1000012345
```

---

## 7.3 Response — 200 OK

```json
{
  "accountId": "1000012345",
  "customerId": "CUST000123",
  "currency": "IDR",
  "availableBalance": 25000000.00,
  "status": "ACTIVE"
}
```

---

## 7.4 Account Response

| Field              | Type    | Description            |
| ------------------ | ------- | ---------------------- |
| `accountId`        | string  | Account identifier     |
| `customerId`       | string  | Account owner          |
| `currency`         | string  | ISO 4217 currency code |
| `availableBalance` | decimal | Available balance      |
| `status`           | string  | Account status         |

Possible account statuses:

```text
ACTIVE
BLOCKED
CLOSED
```

---

## 7.5 Errors

### Account Not Found

```http
404 Not Found
```

```json
{
  "code": "ACCOUNT_NOT_FOUND",
  "message": "Account was not found",
  "correlationId": "8b7d6f21-7e4a-4b42-a2d8-1f8e3f5f1234",
  "timestamp": "2026-09-20T08:00:00Z"
}
```

---

# 8. Funds Transfer

Funds transfer is the primary financial operation in Corevia.

## 8.1 Endpoint

```http
POST /api/v1/transfers
```

---

## 8.2 Required Headers

```http
Authorization: Bearer <JWT>
Content-Type: application/json
Idempotency-Key: 7c8f2d10-8f4a-4c31-a2bd-123456789abc
X-Correlation-Id: 8b7d6f21-7e4a-4b42-a2d8-1f8e3f5f1234
```

`Idempotency-Key` is mandatory.

`X-Correlation-Id` is optional.

---

# 9. Transfer Request

```json
{
  "sourceAccount": "1000012345",
  "destinationAccount": "2000098765",
  "amount": 1500000.00,
  "currency": "IDR",
  "reference": "PAYROLL-202609"
}
```

---

## 9.1 Request Fields

| Field                | Type    | Required | Rules                          |
| -------------------- | ------- | -------: | ------------------------------ |
| `sourceAccount`      | string  |      Yes | Source account identifier      |
| `destinationAccount` | string  |      Yes | Destination account identifier |
| `amount`             | decimal |      Yes | Must be greater than zero      |
| `currency`           | string  |      Yes | ISO 4217 currency code         |
| `reference`          | string  |      Yes | Client/business reference      |

---

## 9.2 Validation Rules

Corevia validates:

### Source account

```text
sourceAccount != null
sourceAccount != blank
```

### Destination account

```text
destinationAccount != null
destinationAccount != blank
```

### Source and destination

```text
sourceAccount != destinationAccount
```

### Amount

```text
amount > 0
```

### Currency

Must be a supported ISO 4217 currency.

Initial supported currency:

```text
IDR
```

### Reference

Must not be blank.

Maximum length:

```text
50 characters
```

---

# 10. Successful Transfer

## 10.1 Response — 201 Created

```json
{
  "transactionId": "TXN-20260920-000001",
  "status": "SUCCESS",
  "sourceAccount": "1000012345",
  "destinationAccount": "2000098765",
  "amount": 1500000.00,
  "currency": "IDR",
  "reference": "PAYROLL-202609",
  "correlationId": "8b7d6f21-7e4a-4b42-a2d8-1f8e3f5f1234",
  "createdAt": "2026-09-20T08:00:00Z"
}
```

---

# 11. Transfer Transaction States

Corevia uses the following states:

```text
RECEIVED
VALIDATING
SUBMITTED
SUCCESS
FAILED
UNKNOWN
```

## State meanings

| State        | Meaning                                      |
| ------------ | -------------------------------------------- |
| `RECEIVED`   | Request has been accepted by Corevia         |
| `VALIDATING` | Request validation is in progress            |
| `SUBMITTED`  | Request has been submitted to core banking   |
| `SUCCESS`    | Core banking confirmed successful processing |
| `FAILED`     | Corevia has a confirmed failure              |
| `UNKNOWN`    | Final outcome cannot currently be determined |

The API must never incorrectly convert an uncertain financial outcome into `FAILED`.

---

# 12. Business Failure

Example: insufficient funds.

## Response

```http
422 Unprocessable Entity
```

```json
{
  "transactionId": "TXN-20260920-000002",
  "status": "FAILED",
  "error": {
    "code": "INSUFFICIENT_FUNDS",
    "message": "Insufficient funds"
  },
  "correlationId": "8b7d6f21-7e4a-4b42-a2d8-1f8e3f5f1234",
  "timestamp": "2026-09-20T08:01:00Z"
}
```

Business failures represent a known financial outcome.

Examples:

```text
INSUFFICIENT_FUNDS
ACCOUNT_NOT_FOUND
ACCOUNT_BLOCKED
INVALID_ACCOUNT
TRANSACTION_NOT_ALLOWED
```

---

# 13. Unknown Transaction Outcome

A particularly important banking scenario occurs when Corevia cannot determine whether T24 processed the transaction.

Example:

```text
Corevia
   │
   │ Transfer
   ▼
T24 / Transact
   │
   │ Transaction processed
   │
   X Network failure
   │
   ▼
Corevia
```

Corevia cannot safely assume the transaction failed.

The transaction therefore enters:

```text
UNKNOWN
```

---

## 13.1 Response

```http
202 Accepted
```

```json
{
  "transactionId": "TXN-20260920-000003",
  "status": "UNKNOWN",
  "message": "Transaction outcome is currently unknown",
  "correlationId": "8b7d6f21-7e4a-4b42-a2d8-1f8e3f5f1234",
  "createdAt": "2026-09-20T08:02:00Z"
}
```

The client should use:

```http
GET /api/v1/transfers/{transactionId}
```

to determine the final state.

---

# 14. Transaction Status Inquiry

## 14.1 Endpoint

```http
GET /api/v1/transfers/{transactionId}
```

Used to retrieve the current transaction state.

---

## 14.2 Response — 200 OK

```json
{
  "transactionId": "TXN-20260920-000003",
  "status": "SUCCESS",
  "sourceAccount": "1000012345",
  "destinationAccount": "2000098765",
  "amount": 1500000.00,
  "currency": "IDR",
  "reference": "PAYROLL-202609",
  "createdAt": "2026-09-20T08:02:00Z",
  "updatedAt": "2026-09-20T08:02:08Z"
}
```

---

## 14.3 Transaction Not Found

```http
404 Not Found
```

```json
{
  "code": "TRANSACTION_NOT_FOUND",
  "message": "Transaction was not found",
  "correlationId": "8b7d6f21-7e4a-4b42-a2d8-1f8e3f5f1234",
  "timestamp": "2026-09-20T08:03:00Z"
}
```

---

# 15. Idempotency

Idempotency protects against duplicate financial transactions caused by:

* client retries
* network failures
* user double-clicks
* mobile application retries
* gateway retries
* timeout handling

---

## 15.1 First Request

```http
POST /api/v1/transfers
Idempotency-Key: ABC-123
```

Corevia creates:

```text
Idempotency-Key: ABC-123
Transaction: TXN-001
Status: SUCCESS
```

---

## 15.2 Same Request Again

```http
POST /api/v1/transfers
Idempotency-Key: ABC-123
```

with identical request data.

Corevia returns the existing transaction.

```http
200 OK
```

```json
{
  "transactionId": "TXN-001",
  "status": "SUCCESS",
  "sourceAccount": "1000012345",
  "destinationAccount": "2000098765",
  "amount": 1500000.00,
  "currency": "IDR",
  "reference": "PAYROLL-202609"
}
```

No second T24 transaction is created.

---

# 16. Idempotency Conflict

The same idempotency key must not be reused for a different request.

Example:

First request:

```json
{
  "sourceAccount": "1000012345",
  "destinationAccount": "2000098765",
  "amount": 1500000,
  "currency": "IDR",
  "reference": "PAYROLL-202609"
}
```

Second request:

```json
{
  "sourceAccount": "1000012345",
  "destinationAccount": "3000011111",
  "amount": 5000000,
  "currency": "IDR",
  "reference": "OTHER-PAYMENT"
}
```

Both use:

```text
Idempotency-Key: ABC-123
```

Corevia rejects the second request.

```http
409 Conflict
```

```json
{
  "code": "IDEMPOTENCY_CONFLICT",
  "message": "Idempotency key has already been used with a different request",
  "correlationId": "8b7d6f21-7e4a-4b42-a2d8-1f8e3f5f1234",
  "timestamp": "2026-09-20T08:05:00Z"
}
```

---

# 17. Missing Idempotency Key

A transfer without an idempotency key is rejected.

```http
400 Bad Request
```

```json
{
  "code": "MISSING_IDEMPOTENCY_KEY",
  "message": "Idempotency-Key header is required",
  "correlationId": "8b7d6f21-7e4a-4b42-a2d8-1f8e3f5f1234",
  "timestamp": "2026-09-20T08:06:00Z"
}
```

---

# 18. Validation Error

Invalid request example:

```json
{
  "sourceAccount": "1000012345",
  "destinationAccount": "1000012345",
  "amount": -500,
  "currency": "INVALID",
  "reference": ""
}
```

Response:

```http
400 Bad Request
```

```json
{
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "errors": [
    {
      "field": "destinationAccount",
      "message": "Source and destination accounts must be different"
    },
    {
      "field": "amount",
      "message": "Amount must be greater than zero"
    },
    {
      "field": "currency",
      "message": "Unsupported currency"
    },
    {
      "field": "reference",
      "message": "Reference must not be blank"
    }
  ],
  "correlationId": "8b7d6f21-7e4a-4b42-a2d8-1f8e3f5f1234",
  "timestamp": "2026-09-20T08:07:00Z"
}
```

---

# 19. Standard Error Model

All API errors follow a common structure.

```json
{
  "code": "ERROR_CODE",
  "message": "Human-readable description",
  "errors": [],
  "correlationId": "UUID",
  "timestamp": "ISO-8601 timestamp"
}
```

`errors` is optional and is primarily used for validation failures.

---

# 20. Error Classification

Corevia distinguishes three major categories.

## 20.1 Client Errors

Examples:

```text
VALIDATION_ERROR
MISSING_IDEMPOTENCY_KEY
IDEMPOTENCY_CONFLICT
INVALID_REQUEST
```

Typical HTTP status:

```text
400
409
```

---

## 20.2 Business Errors

Examples:

```text
ACCOUNT_NOT_FOUND
INSUFFICIENT_FUNDS
ACCOUNT_BLOCKED
TRANSACTION_NOT_ALLOWED
```

Typical HTTP status:

```text
422
```

---

## 20.3 Technical Errors

Examples:

```text
CORE_BANKING_UNAVAILABLE
CORE_BANKING_TIMEOUT
DEPENDENCY_FAILURE
INTERNAL_ERROR
```

Technical errors must not automatically imply that a financial transaction failed.

---

# 21. Core Banking Timeout

If T24 does not respond within the configured timeout:

```text
T24 timeout
     ↓
Can outcome be determined?
     │
     └── No
          ↓
       UNKNOWN
```

Corevia returns:

```http
202 Accepted
```

when the transaction may have been submitted but its final outcome is unknown.

The transaction can subsequently be queried.

---

# 22. Retry Policy

Not every failure is retryable.

| Failure                                  | Retry                                    |
| ---------------------------------------- | ---------------------------------------- |
| Validation error                         | No                                       |
| Insufficient funds                       | No                                       |
| Account not found                        | No                                       |
| Authentication failure                   | No                                       |
| T24 HTTP 500 before submission certainty | Controlled                               |
| Connection timeout                       | Do not blindly retry financial operation |
| Unknown outcome                          | Status inquiry                           |
| T24 unavailable                          | Circuit breaker                          |

The principle is:

> **Never perform a blind retry when it could create a duplicate financial transaction.**

---

# 23. Circuit Breaker

Corevia protects itself from repeated failures of the core banking system.

Conceptually:

```text
CLOSED
   │
   │ repeated failures
   ▼
OPEN
   │
   │ recovery interval
   ▼
HALF_OPEN
   │
   ├── success ──► CLOSED
   │
   └── failure ─► OPEN
```

When the circuit is open, Corevia fails fast instead of continuously sending requests to an unavailable dependency.

---

# 24. Health API

## Endpoint

```http
GET /api/v1/health
```

Example:

```json
{
  "status": "UP",
  "service": "corevia",
  "version": "0.1.0"
}
```

Infrastructure-specific health checks may be exposed separately through Spring Boot Actuator.

---

# 25. HTTP Status Code Summary

| HTTP Status | Usage                                                   |
| ----------: | ------------------------------------------------------- |
|       `200` | Successful inquiry or idempotent replay                 |
|       `201` | New transfer successfully created                       |
|       `202` | Transaction accepted but final outcome is not yet known |
|       `400` | Invalid request                                         |
|       `401` | Authentication required/failed                          |
|       `403` | Insufficient authorization                              |
|       `404` | Resource not found                                      |
|       `409` | Idempotency conflict                                    |
|       `422` | Known business failure                                  |
|       `429` | Rate limit exceeded                                     |
|       `500` | Unexpected internal error                               |
|       `502` | Invalid/failed downstream response                      |
|       `503` | Dependency/service unavailable                          |
|       `504` | Gateway/dependency timeout where appropriate            |

For financial transactions, HTTP status must be interpreted together with the transaction status.

---

# 26. Security Requirements

All business APIs require authentication.

Minimum requirements:

```text
HTTPS
JWT authentication
Authorization
Input validation
Secure headers
Rate limiting
Audit logging
Secrets externalization
```

Sensitive information must not be written to application logs.

Examples of information that should not be logged in plaintext:

```text
Passwords
JWT tokens
Secrets
Full authentication credentials
Sensitive customer information
```

---

# 27. API Versioning

The initial API version is:

```text
/api/v1
```

Breaking changes require a new major API version.

Example:

```text
/api/v2
```

Backward-compatible changes may be introduced within the existing version.

---

# 28. Amount and Currency Handling

Monetary amounts are represented as decimal values.

Example:

```json
{
  "amount": 1500000.00,
  "currency": "IDR"
}
```

The Java implementation must use:

```java
BigDecimal
```

rather than:

```java
double
```

for monetary calculations.

Currency follows ISO 4217 conventions.

---

# 29. Date and Time

All timestamps use ISO-8601 format.

Example:

```text
2026-09-20T08:00:00Z
```

The backend stores timestamps in UTC.

Client applications may convert timestamps to their local timezone for presentation.

---

# 30. API Contract Example

A complete transfer flow:

### Request

```http
POST /api/v1/transfers
Authorization: Bearer <JWT>
Idempotency-Key: ABC-123
X-Correlation-Id: CORR-001
Content-Type: application/json
```

```json
{
  "sourceAccount": "1000012345",
  "destinationAccount": "2000098765",
  "amount": 1500000.00,
  "currency": "IDR",
  "reference": "PAYROLL-202609"
}
```

### Successful response

```http
HTTP/1.1 201 Created
```

```json
{
  "transactionId": "TXN-20260920-000001",
  "status": "SUCCESS",
  "sourceAccount": "1000012345",
  "destinationAccount": "2000098765",
  "amount": 1500000.00,
  "currency": "IDR",
  "reference": "PAYROLL-202609",
  "correlationId": "CORR-001",
  "createdAt": "2026-09-20T08:00:00Z"
}
```

---

# 31. API Contract and Architecture Boundary

The external API intentionally does not expose T24-specific implementation details.

For example, clients should not need to know:

```text
T24 field names
T24 internal request formats
T24 adapter implementation
T24 connection details
T24-specific error structures
```

Instead:

```text
Digital Channel
       │
       │ Corevia API
       ▼
Application / Domain
       │
       │ CoreBankingGateway
       ▼
T24 Adapter
       │
       ▼
T24 / Transact
```

This protects the API contract from changes in the underlying core banking implementation.

---

# 32. Future API Evolution

The initial API intentionally remains small.

Potential future capabilities include:

```text
GET  /customers/{id}/accounts
GET  /accounts/{id}/transactions
POST /payments
POST /beneficiaries
POST /scheduled-transfers
GET  /transfers?status=...
```

These are outside the initial implementation scope.

---

# 33. Implementation Mapping

The API contract will map to the following Spring Boot components:

```text
HTTP
 │
 ▼
Controller
 │
 ▼
Request DTO
 │
 ▼
Application Service
 │
 ▼
Domain
 │
 ▼
Output Port
 │
 ▼
T24 Adapter
 │
 ▼
T24 / Transact
```

Example:

```java
@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

    @PostMapping
    public ResponseEntity<TransferResponse> createTransfer(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody TransferRequest request) {

        // delegate to application service
    }
}
```

The controller should remain thin.

Business rules belong in the application/domain layers rather than inside the controller.

---
