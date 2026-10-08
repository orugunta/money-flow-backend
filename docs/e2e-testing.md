# End-to-end testing with mock APIs

Mock versions of the two external APIs that money-flow-backend calls, for end-to-end testing on a laptop. Built on
WireMock (Docker), no code, and not part of the application build. Behaviour follows the contracts in
[docs/api/](api/). The WireMock stubs and data are in [mock-server/](../mock-server/).

| API           | URL                   | API key (`X-API-Key`) |
| ------------- | --------------------- | --------------------- |
| Statement API | http://localhost:9091 | `statement-key`       |
| Balance API   | http://localhost:9092 | `balance-key`         |

A missing or wrong key returns `401`.

## Start and stop

Both mocks are services in the root `docker-compose.yml`. From the repository root:

```bash
docker compose up -d --wait statement-api balance-api   # only the mocks
docker compose up -d --build --wait                     # the mocks and the app
docker compose down
```

## Run money-flow-backend against it

From the repository root:

```bash
export STATEMENT_API_BASE_URL=http://localhost:9091 STATEMENT_API_KEY=statement-key
export BALANCE_API_BASE_URL=http://localhost:9092 BALANCE_API_KEY=balance-key
export MONEYFLOW_API_KEY=local-dev-key
./mvnw spring-boot:run
```

```bash
curl -X POST -H 'X-API-Key: local-dev-key' localhost:8080/api/v1/accounts/ACC-123/balances
```

If money-flow-backend runs in Docker too, use `http://host.docker.internal:9091` and
`http://host.docker.internal:9092` as base URLs.

## Accounts

| Account          | Statement API (`GET /accounts/{id}/transactions`) | Balance API (`PUT`) | Expected result                       |
| ---------------- | ------------------------------------------------- | ------------------- | ------------------------------------- |
| `ACC-123`        | EUR, 3 months (Jul–Sep 2026), Aug spending > income | accepted          | `200`, 3 summaries, 3 PUTs            |
| `ACC-456`        | JPY (no minor unit), 1 month                      | accepted            | `200`, 1 summary                      |
| `ACC-EMPTY`      | No transactions                                   | –                   | `200`, `[]`, no PUTs                  |
| `ACC-INVALID`    | A negative amount (breaks the contract)           | –                   | `502`, not retried                    |
| `ACC-MALFORMED`  | Broken JSON                                       | –                   | `502`, not retried                    |
| `ACC-FLAKY`      | `503`, then OK on the next call                   | accepted            | `200` after 1 retry                   |
| `ACC-DOWN`       | Always `503`                                      | –                   | `502` after 3 retries (~15s)          |
| `ACC-SLOW`       | Answers after 10s (longer than the read timeout)  | –                   | `502` after 3 retries (~35s)          |
| `ACC-PUSH-FLAKY` | 1 month                                           | `503`, then OK      | `200` after 1 retry                   |
| `ACC-REJECT`     | 1 month                                           | Always `422`        | `502`, not retried                    |
| anything else    | `404`                                             | –                   | `404`                                 |

The flaky accounts alternate between failing and succeeding, so every run sees one failure followed by a success.

The Balance API accepts a PUT to `/monthly-balances/{accountId}/{yyyy-MM}` only with a JSON body that matches the
`MonthlyBalance` schema: all six fields, no extra fields, `currency` as 3 capital letters, and non-negative
`totalIncome`/`totalSpending`. Anything else gets `400`. It doesn't check that the body's account and month match
the path or that `balance = totalIncome - totalSpending`; `scripts/e2e.sh` checks the values instead.

## Automated checks

With the mocks and money-flow-backend running, from the repository root:

```bash
scripts/e2e.sh          # fast scenarios, a few seconds
scripts/e2e.sh --slow   # also ACC-DOWN and ACC-SLOW, about 1 minute
```

It needs `curl` and `jq`, resets the mocks before each scenario, and checks the status codes, the returned
summaries and how many calls each mock received. Override `APP_URL` or `APP_KEY` if the app runs elsewhere.

## See what was received

```bash
curl -s localhost:9092/__admin/requests | jq '.requests[] | {url: .request.url, body: .request.body}'
curl -s -X DELETE localhost:9092/__admin/requests          # clear the journal
curl -s -X POST localhost:9091/__admin/scenarios/reset     # restart the flaky accounts
```

## Change the data

- Transactions: `mock-server/statement-api/__files/{accountId}.json`
- Which account returns what: `mock-server/statement-api/mappings/*.json`
- Balance API rules: `mock-server/balance-api/mappings/*.json`

Restart the containers after editing (`docker compose restart statement-api balance-api`), or call
`POST /__admin/mappings/reset`.
