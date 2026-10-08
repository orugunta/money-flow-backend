# Money Flow Backend — Design

## Answers to the Brief

| Question                                     | Short answer                                                                                                         | Details |
| -------------------------------------------- | -------------------------------------------------------------------------------------------------------------------- | ------- |
| What problem does the application solve?     | It turns an account's raw bank transactions into monthly income/spending/balance summaries and sends them on.        | §1      |
| What architecture is used?                   | Hexagonal (ports and adapters): a pure domain calculator, with each external API behind an interface.                | §2      |
| How is it tested?                            | Unit tests on the calculator, WireMock tests on the HTTP adapters, and one end-to-end test.                          | §6      |
| How would it be deployed to a remote server? | Docker image pushed to a registry, run as a stateless container (e.g. AWS ECS), configured via env vars.             | §7      |
| How do we start before the other APIs exist? | Agree API contracts first, code against interfaces, and use a `stub` profile and WireMock in place of the real APIs. | §5      |

## 1. Problem

A bank exposes an account's transactions as a raw list. Another system, such as a budgeting or financial
reporting application, needs a monthly summary of the account's financial activity without having to
understand the bank's transaction format.

This service fetches the transactions, groups them by month, calculates total income, total spending and the
monthly balance, and sends the calculated summary to another external system.

The monthly balance is defined as:

**monthly balance = total income − total spending**

The application does not maintain opening or closing account balances and does not persist data.

```text
External Statement API
         │ GET transactions
         ▼
  Fetch transactions
         │
         ▼
   Group by month
         │
         ▼
┌──────────────────────┐
│ Per month, calculate │
│  total income        │
│  total spending      │
│  monthly balance     │
└──────────────────────┘
         │
         ▼
Send each month's summary ──PUT──▶ External Balance API
```

There is no persistence layer. Every run recomputes the summaries from the source transactions.

## 2. Architecture: Hexagonal (Ports & Adapters)

The flow above is the use case. It lives in the service layer, and the grouping and calculation are plain
Java with no Spring or HTTP code. Each external API sits behind an interface (a port), so real HTTP clients,
stubs and test doubles can be swapped in without touching the logic.

```
 inbound adapters          service / domain                               outbound adapters
┌──────────────┐     ┌──────────────────────────────────────────┐
│ REST         │────▶│ MonthlyBalanceService                    │
│ controller   │     │   1. source.fetch(accountId)            ─┼──▶ TransactionSource ◀── HttpTransactionSource
└──────────────┘     │   2. calculator.summarize(history)       │                     ◀── StubTransactionSource
                     │   3. publisher.publish(summary) per month─┼──▶ SummaryPublisher  ◀── HttpSummaryPublisher
                     │                                          │                     ◀── LoggingSummaryPublisher
                     │ BalanceCalculator (pure, no I/O)         │
                     └──────────────────────────────────────────┘
```

```
com.moneyflow
├── domain          # records + BalanceCalculator, no framework imports
├── service         # MonthlyBalanceService, ports TransactionSource / SummaryPublisher, their exceptions
├── adapter
│   ├── auth        # API-key filter + 401 entry point
│   ├── client      # outbound: Statement/Balance API HTTP clients + DTOs + mappers, stub implementations
│   ├── controller  # REST controller + response DTO
│   └── exception   # error handling (ProblemDetail)
└── config          # all @Configuration: beans, RestClients, security chain, OpenAPI, @ConfigurationProperties
```

The adapters keep their own DTOs and map them to domain records. If an external contract changes, only the
adapter changes.

## 3. Domain Model & Calculation

```java
enum Direction {
    CREDIT,
    DEBIT
}

record Transaction(
        String id,
        LocalDate bookingDate,
        BigDecimal amount,
        Direction direction
) {}

record AccountHistory(
        String accountId,
        Currency currency,          // java.util.Currency; amounts are normalized to its decimal places
        List<Transaction> transactions
) {}

record MonthlySummary(
        String accountId,
        YearMonth month,
        String currency,
        BigDecimal totalIncome,
        BigDecimal totalSpending,
        BigDecimal balance
) {}
```

`BalanceCalculator.summarize(AccountHistory) → List<MonthlySummary>`, ordered by month from oldest to newest.

The calculation is:

1. Group transactions by `YearMonth.from(bookingDate)`.
2. For each month, sum all `CREDIT` transactions as total income.
3. Sum all `DEBIT` transactions as total spending.
4. Calculate:

```text
balance = total income − total spending
```

The calculation for each month is independent. A previous month's balance is not carried into the next month.

| Value           | Rule                               |
| --------------- | ---------------------------------- |
| `totalIncome`   | Sum of CREDIT amounts in the month |
| `totalSpending` | Sum of DEBIT amounts in the month  |
| `balance`       | `totalIncome − totalSpending`      |

Money is represented using `BigDecimal`, never `double`. Amounts use the currency's number of decimal places
(EUR 2, JPY 0), so totals are exact and consistently formatted. Nothing is rounded.

If there are no transactions, the calculator returns an empty list and there is nothing to send.

## 4. External Contracts (assumed)

These are our initial assumptions, written as OpenAPI specifications in `docs/api/` so they can be agreed with
the API owners before implementation.

The brief refers to "monthly bank statements". The imaginary Statement API is assumed to expose an account's
transactions, and this service derives the monthly summaries from them by grouping on `bookingDate`.

### Statement API

```http
GET {statement-api.base-url}/accounts/{accountId}/transactions
```

Example response:

```text
{
  "accountId": "ACC-123",
  "currency": "EUR",
  "transactions": [
    {
      "id": "t1",
      "bookingDate": "2026-07-01",
      "amount": 3000.00,
      "type": "CREDIT",
      "description": "Salary"
    },
    {
      "id": "t2",
      "bookingDate": "2026-07-31",
      "amount": 1000.00,
      "type": "DEBIT",
      "description": "Rent"
    },
    {
      "id": "t3",
      "bookingDate": "2026-08-01",
      "amount": 2500.00,
      "type": "CREDIT",
      "description": "Salary"
    }
  ]
}
```

Assumptions:

- Transaction amounts are always positive.
- `type` determines the transaction direction:
  - `CREDIT` represents income.
  - `DEBIT` represents spending.
- The application performs the subtraction when calculating the monthly balance, rather than relying on
  signed transaction amounts.
- The Statement API adapter validates the response when mapping it to the domain:
  - `accountId` must match the requested account.
  - `currency` must be an ISO 4217 code.
  - Each transaction needs a unique `id`, a present and non-negative `amount` with no more decimal places than
    the currency allows, a `type` of `CREDIT` or `DEBIT`, and a valid `bookingDate`.

  Anything else is treated as invalid upstream data (`502`). The caller gets a generic error message; the
  details are logged.
- Transactions can arrive in any order.
- The application groups transactions by `bookingDate`.
- Authentication is assumed to use an API-key header.

For the example above, the calculated summaries are:

| Month   |  Income | Spending | Balance |
| ------- | ------: | -------: | ------: |
| 2026-07 | 3000.00 |  1000.00 | 2000.00 |
| 2026-08 | 2500.00 |     0.00 | 2500.00 |

### Balance API

One PUT is sent per month:

```http
PUT {balance-api.base-url}/monthly-balances/{accountId}/{yyyy-MM}
```

Example:

```text
{
  "accountId": "ACC-123",
  "month": "2026-07",
  "currency": "EUR",
  "totalIncome": 3000.00,
  "totalSpending": 1000.00,
  "balance": 2000.00
}
```

The Balance API is assumed to implement PUT idempotently for the same account and month. Re-sending a summary
replaces the previous value, so a month still in progress is corrected by the next run.

### Our trigger

```http
POST /api/v1/accounts/{accountId}/balances
```

The endpoint:

1. Fetches the account transactions.
2. Groups them by month.
3. Calculates the monthly summaries.
4. Sends each summary to the Balance API.
5. Returns the calculated summaries.

The primary trigger is a REST endpoint. A scheduled trigger can be added later if periodic processing is required.

Callers authenticate with an API key in the `X-API-Key` header, the same scheme the external APIs use. A missing
or wrong key returns `401` (`ProblemDetail`, `WWW-Authenticate: ApiKey`), and nothing is fetched or sent.
`/actuator/health` and `/actuator/info` stay public for container health checks.

The OpenAPI document is generated from the code (springdoc-openapi) at `/v3/api-docs`, with Swagger UI at
`/swagger-ui.html`. They contain no secrets but are public when on, so they are off by default and enabled in the
`stub` profile or explicitly per environment.

Errors are returned as `ProblemDetail`:

- Missing or wrong API key → `401`.
- Account not found at the Statement API → `404`.
- Other upstream failures, such as error responses, timeouts or invalid data → `502`.
- Anything unexpected → `500` with a generic detail.

Transient failures are retried before giving up: timeouts, connection errors, `5xx` (except `501`/`505`/`508`/`510`/`511`) and
`429` get up to 3 retries, 5 seconds apart (configurable per API). This applies to the transaction fetch and to each
monthly PUT, which are both safe to repeat. `404`, other `4xx`, redirects and invalid data fail the same way every
time, so they aren't retried. Because the endpoint answers synchronously, a call that keeps failing delays the
response by up to about 35 seconds (4 calls × 5s read timeout + 3 × 5s wait).

If sending fails for a month, processing stops at that month and returns `502`. Re-running is safe, because
months that were already sent are simply replaced.

Known limitation: only months that currently have transactions are sent. If the bank later moves every
transaction out of a month that was already sent, that month's earlier summary stays in the Balance API. Clearing
it would require remembering what was sent, which needs persistence and is out of scope.

## 5. Building Before the Other APIs Exist

1. **Contract first:** write the two OpenAPI specs above and share them with the API owners as the proposal to agree on.
2. **Code against interfaces:** the calculator and service are built and tested without any HTTP.
3. **`stub` profile:** `StubTransactionSource` reads JSON fixtures from `src/main/resources/stubs/`, and
   `LoggingSummaryPublisher` logs each summary instead of sending it. The app runs end to end with no external
   systems: `./mvnw spring-boot:run -Dspring-boot.run.profiles=stub`.
4. **WireMock in tests:** the real HTTP clients are tested against WireMock servers that behave like the agreed contracts.
5. **Mock APIs for manual end-to-end runs:** `mock-server/` runs both APIs as WireMock containers with test accounts
   (normal, invalid data, transient failures). The app uses its real HTTP clients against them, and
   `scripts/e2e.sh` checks each scenario. The root `docker-compose.yml` starts the app and both mocks together.
   See `docs/e2e-testing.md`.
6. When the real APIs are ready, only the config changes (base URLs, API keys).

## 6. Testing

| Level      | What                                                                                                                                                                   | Tools                       |
| ---------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------- |
| Unit       | `BalanceCalculator`: normal month, spending greater than income, multiple months calculated independently, multiple transactions in one month, month boundary, no data | JUnit 5, AssertJ            |
| Unit       | `MonthlyBalanceService`: fetch → calculate → publish, publishing each month's result, stops on first failure                                                           | Mockito                     |
| Adapter    | HTTP clients: request path/headers, JSON mapping, 404 and 5xx handling                                                                                                 | WireMock                    |
| End-to-end | Whole application with both external APIs mocked; verify calculated PUT requests and request bodies                                                                    | `@SpringBootTest`, WireMock |

Example test cases:

### Normal month

```text
Income   = 3000
Spending = 1000
Balance  = 2000
```

### Spending greater than income

```text
Income   = 500
Spending = 800
Balance  = -300
```

### Only spending in a month

```text
Income   = 0
Spending = 800
Balance  = -800
```

### Several months calculated independently

```text
July:
3000 - 1000 = 2000

August:
2500 - 0 = 2500
```

August's balance is calculated independently and does not include July's balance.

### Several transactions in one month

All income transactions are summed together and all spending transactions are summed together.

### Month boundary

```text
2026-07-31 → July
2026-08-01 → August
```

### No data

```text
[] → []
```

No Balance API requests are made.

## 7. Deployment

1. `./mvnw verify` runs all tests and builds the application.
2. A multi-stage `Dockerfile` builds the application image (`eclipse-temurin:21-jre`, non-root user).
3. A CI pipeline pushes the image to a container registry such as Amazon ECR.
4. The image runs as a stateless container on a remote platform such as AWS ECS Fargate or Kubernetes.
5. Runtime configuration is supplied through environment variables.
6. Secrets such as API keys are supplied through a secret-management system such as AWS Secrets Manager.
7. `/actuator/health` is used for container health checks.

Configuration environment variables:

```text
MONEYFLOW_API_KEY
STATEMENT_API_BASE_URL
STATEMENT_API_KEY
BALANCE_API_BASE_URL
BALANCE_API_KEY
```

No external API URLs or credentials are hardcoded in the application.

## 8. Tech Stack

Java 21 · Spring Boot 4.1.1 · Maven · Spring Web MVC (`RestClient`) · Spring Security · springdoc-openapi · Actuator · JUnit 5 · AssertJ · Mockito ·
WireMock · Docker.

## 9. Decisions

1. **Flow:** Fetch transactions, group them by month, calculate income, spending and monthly balance, then send one summary per month.
2. **Balance:** `monthlyBalance = totalIncome − totalSpending`.
3. **No opening/closing balance:** The application does not maintain or calculate account opening or closing balances.
4. **Independent months:** Each month's balance is calculated independently. A previous month's balance is not carried into the next month.
5. **No persistence:** The application is stateless and recomputes summaries from the source transactions on every run.
6. **Trigger:** REST endpoint only.
7. **Send semantics:** PUT one summary per account and month.
8. **External APIs:** API contracts are defined before the real APIs are available. Stub implementations and WireMock allow development and testing to proceed independently.
9. **Build tool:** Maven, with the Maven wrapper committed.
10. **Authentication:** a shared API key, because callers are services and there is no identity provider yet.

### Requirement interpretation

The phrase "monthly balance with income and spending" is interpreted as:

**monthly balance = total income − total spending**

Opening and closing account balances are outside the scope of this application because they are not required
by the brief.

## 10. Possible Improvements

None of these are required by the brief. They are things to consider as usage grows.

- **Performance and scalability:** add a `?from=&to=` range to the transaction fetch, so the amount of history
  fetched doesn't grow without limit. Add pagination if the Statement API needs it.
- **Resilience:** exponential backoff and a circuit breaker on top of the fixed-delay retries, and an overall time
  limit for the endpoint, if the upstream APIs fail often.
- **Security:** OAuth2 JWT bearer tokens (Spring Security resource server) once callers have an identity
  provider, for per-caller identity, scopes and key rotation without a redeploy.
- **Contract safety:** consumer-driven contract tests with the API owners.
- **Automation:** a scheduled trigger, using an external scheduler (e.g. Amazon EventBridge calling the
  trigger endpoint) or distributed locking (e.g. ShedLock) if several instances run.
