# money-flow-backend

Fetches an account's bank transactions from an external Statement API, groups them by month, calculates total
income, total spending and the monthly balance (`income − spending`), and sends one summary per month to an
external Balance API. Stateless, no persistence.

See [DESIGN.md](DESIGN.md) for the problem, architecture, testing and deployment approach, and
[docs/api/](docs/api/) for the assumed contracts of both external APIs.

## Requirements

- Java 21
- Docker (optional)

## Run without external APIs (`stub` profile)

Transactions are read from `src/main/resources/stubs/transactions/{accountId}.json` and summaries are logged
instead of sent.

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=stub
```

```bash
curl -X POST -H 'X-API-Key: local-dev-key' localhost:8080/api/v1/accounts/ACC-123/balances
```

```text
[
  {
    "accountId": "ACC-123",
    "month": "2026-07",
    "currency": "EUR",
    "totalIncome": 3000.00,
    "totalSpending": 1000.00,
    "balance": 2000.00
  },
  {
    "accountId": "ACC-123",
    "month": "2026-08",
    "currency": "EUR",
    "totalIncome": 2500.00,
    "totalSpending": 0.00,
    "balance": 2500.00
  }
]
```

Any account without a fixture returns `404`, for example `NOPE`. The stub profile accepts the API key
`local-dev-key` unless `MONEYFLOW_API_KEY` is set.

## Run against real APIs

| Variable                 | Description                   |
| ------------------------ | ----------------------------- |
| `MONEYFLOW_API_KEY`      | Key callers of this API send  |
| `STATEMENT_API_BASE_URL` | Base URL of the Statement API |
| `STATEMENT_API_KEY`      | API key, sent as `X-API-Key`  |
| `BALANCE_API_BASE_URL`   | Base URL of the Balance API   |
| `BALANCE_API_KEY`        | API key, sent as `X-API-Key`  |

```bash
export MONEYFLOW_API_KEY=...
export STATEMENT_API_BASE_URL=https://statement-api.example.com STATEMENT_API_KEY=...
export BALANCE_API_BASE_URL=https://balance-api.example.com BALANCE_API_KEY=...
./mvnw spring-boot:run
```

The app refuses to start if any of these are missing. Timeouts default to 2s (connect) and 5s (read) and can be
changed with `moneyflow.{statement-api,balance-api}.{connect-timeout,read-timeout}`.

Transient failures (timeouts, connection errors, `5xx` except `501`/`505`/`508`/`510`/`511`, `429`) are retried up to 3 times, 5s
apart, for both fetching transactions and sending each month. Other errors such as `404`, other `4xx` or invalid
data aren't retried. Change with `moneyflow.{statement-api,balance-api}.{max-retries,retry-delay}`. A call that
keeps failing takes up to about 35s (4 calls × 5s read timeout + 3 × 5s wait) before the endpoint returns `502`.

## API

`POST /api/v1/accounts/{accountId}/balances` fetches, calculates, sends every month and returns the summaries.
Requires the `X-API-Key` header with the value of `MONEYFLOW_API_KEY`.

| Status | When                                                        |
| ------ | ----------------------------------------------------------- |
| `200`  | All months calculated and sent                              |
| `400`  | `accountId` doesn't match `[A-Za-z0-9-]{1,64}`              |
| `401`  | `X-API-Key` header missing or wrong                         |
| `404`  | The Statement API doesn't know the account                  |
| `500`  | Unexpected error (generic detail; the cause is only logged) |
| `502`  | An external API failed, timed out or returned invalid data  |

Errors use RFC 9457 `ProblemDetail` bodies. Health check: `GET /actuator/health` (no key needed).

### API documentation

Generated from the code by springdoc-openapi. On in the `stub` profile; elsewhere off unless enabled with
`SPRINGDOC_API_DOCS_ENABLED=true` and `SPRINGDOC_SWAGGER_UI_ENABLED=true`. No key is needed to view them:

- Swagger UI: http://localhost:8080/swagger-ui.html (use **Authorize** with the API key to try requests)
- OpenAPI document: http://localhost:8080/v3/api-docs (JSON) or `/v3/api-docs.yaml`

## Build and test

```bash
./mvnw verify
```

Runs unit tests, WireMock adapter tests and end-to-end tests (no Docker or external services needed), and fails
the build below 100% line and branch coverage (JaCoCo). The report is written to `target/site/jacoco/index.html`.

## End-to-end testing with mock APIs

[mock-server/](mock-server/) holds WireMock versions of both external APIs, run in Docker, with test accounts for
normal data, invalid data and transient failures. [scripts/e2e.sh](scripts/e2e.sh) checks every scenario against
the running app.

Everything in Docker (app built from the `Dockerfile`, plus both mocks). This stack turns the API docs on, so
Swagger UI is at http://localhost:8080/swagger-ui.html (authorize with `local-dev-key`):

```bash
docker compose up -d --build --wait
scripts/e2e.sh          # add --slow for the retry/timeout cases
docker compose down
```

Or only the mocks in Docker, with the app running from Maven:

```bash
docker compose up -d --wait statement-api balance-api
export STATEMENT_API_BASE_URL=http://localhost:9091 STATEMENT_API_KEY=statement-key
export BALANCE_API_BASE_URL=http://localhost:9092 BALANCE_API_KEY=balance-key
export MONEYFLOW_API_KEY=local-dev-key
./mvnw spring-boot:run
# in another terminal
scripts/e2e.sh
```

See [docs/e2e-testing.md](docs/e2e-testing.md) for the accounts and expected results.

## Docker

```bash
docker build -t money-flow-backend .
docker run --rm -p 8080:8080 -e SPRING_PROFILES_ACTIVE=stub money-flow-backend
```
