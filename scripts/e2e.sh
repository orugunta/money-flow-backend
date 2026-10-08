#!/usr/bin/env bash
# End-to-end checks for money-flow-backend against the mock APIs in mock-server/ (see docs/e2e-testing.md).
#
#   docker compose up -d --build --wait     # from the repo root: the app and both mocks
#   scripts/e2e.sh                          # fast scenarios
#   scripts/e2e.sh --slow                   # also the ones that wait for retries/timeouts (~1 minute)
#
# Needs curl and jq. APP_URL and APP_KEY can be overridden.
set -uo pipefail

APP_URL=${APP_URL:-http://localhost:8080}
APP_KEY=${APP_KEY:-local-dev-key}
STATEMENT=${STATEMENT_ADMIN:-http://localhost:9091}
BALANCE=${BALANCE_ADMIN:-http://localhost:9092}
SLOW=false
[[ ${1:-} == "--slow" ]] && SLOW=true

passed=0
failed=0

reset_mocks() {
    for api in "$STATEMENT" "$BALANCE"; do
        curl -fsS -X DELETE "$api/__admin/requests" > /dev/null
        curl -fsS -X POST "$api/__admin/scenarios/reset" > /dev/null
    done
}

# count <admin-url> <method> <url-path-pattern>
count() {
    curl -fsS -X POST "$1/__admin/requests/count" \
        -d "{\"method\": \"$2\", \"urlPathPattern\": \"$3\"}" | jq -r .count
}

check() {
    local name=$1 expected=$2 actual=$3
    if [[ "$expected" == "$actual" ]]; then
        echo "  ok   $name"
        passed=$((passed + 1))
    else
        echo "  FAIL $name: expected $expected, got $actual"
        failed=$((failed + 1))
    fi
}

# trigger <accountId> [api-key]: sets $status and $body
trigger() {
    local response
    response=$(curl -sS -w '\n%{http_code}' -X POST -H "X-API-Key: ${2:-$APP_KEY}" \
        "$APP_URL/api/v1/accounts/$1/balances")
    status=$(tail -n1 <<< "$response")
    body=$(sed '$d' <<< "$response")
}

scenario() {
    echo "$1"
    reset_mocks
}

if ! curl -fsS "$APP_URL/actuator/health" > /dev/null; then
    echo "money-flow-backend is not reachable at $APP_URL" >&2
    exit 1
fi

scenario "ACC-123: three EUR months, one with spending above income"
trigger ACC-123
check "status" 200 "$status"
check "summaries" \
    '[{"month":"2026-07","totalIncome":3000.00,"totalSpending":1285.40,"balance":1714.60},{"month":"2026-08","totalIncome":3000.00,"totalSpending":4500.00,"balance":-1500.00},{"month":"2026-09","totalIncome":3000.00,"totalSpending":0.00,"balance":3000.00}]' \
    "$(jq -c '[.[] | {month, totalIncome, totalSpending, balance}]' <<< "$body")"
check "one PUT per month" 3 "$(count "$BALANCE" PUT "/monthly-balances/ACC-123/.*")"
# The raw request text, so the currency's two decimals are checked exactly as sent.
check "PUT body keeps two decimals" 1 "$(curl -fsS -X POST "$BALANCE/__admin/requests/find" \
    -d '{"method": "PUT", "url": "/monthly-balances/ACC-123/2026-07"}' \
    | jq -r '[.requests[].body | select(contains("\"balance\":1714.60"))] | length')"

scenario "ACC-456: JPY has no minor unit"
trigger ACC-456
check "status" 200 "$status"
check "summary" '{"currency":"JPY","totalIncome":350000,"totalSpending":120000,"balance":230000}' \
    "$(jq -c '.[0] | {currency, totalIncome, totalSpending, balance}' <<< "$body")"

scenario "ACC-EMPTY: no transactions, nothing sent"
trigger ACC-EMPTY
check "status" 200 "$status"
check "body" "[]" "$body"
check "no PUTs" 0 "$(count "$BALANCE" PUT "/monthly-balances/.*")"

scenario "NOPE: unknown account"
trigger NOPE
check "status" 404 "$status"
check "no PUTs" 0 "$(count "$BALANCE" PUT "/monthly-balances/.*")"

scenario "Wrong API key on our endpoint"
trigger ACC-123 wrong-key
check "status" 401 "$status"
check "nothing fetched" 0 "$(count "$STATEMENT" GET "/.*")"

scenario "ACC-INVALID: negative amount, not retried"
trigger ACC-INVALID
check "status" 502 "$status"
check "one GET" 1 "$(count "$STATEMENT" GET "/accounts/ACC-INVALID/transactions")"

scenario "ACC-MALFORMED: broken JSON, not retried"
trigger ACC-MALFORMED
check "status" 502 "$status"
check "one GET" 1 "$(count "$STATEMENT" GET "/accounts/ACC-MALFORMED/transactions")"

scenario "ACC-FLAKY: Statement API returns 503 once, retry succeeds"
trigger ACC-FLAKY
check "status" 200 "$status"
check "two GETs" 2 "$(count "$STATEMENT" GET "/accounts/ACC-FLAKY/transactions")"

scenario "ACC-PUSH-FLAKY: Balance API returns 503 once, retry succeeds"
trigger ACC-PUSH-FLAKY
check "status" 200 "$status"
check "two PUTs" 2 "$(count "$BALANCE" PUT "/monthly-balances/ACC-PUSH-FLAKY/.*")"

scenario "ACC-REJECT: Balance API answers 422, not retried"
trigger ACC-REJECT
check "status" 502 "$status"
check "one PUT" 1 "$(count "$BALANCE" PUT "/monthly-balances/ACC-REJECT/.*")"

if $SLOW; then
    scenario "ACC-DOWN: Statement API always 503, gives up after 3 retries (~15s)"
    trigger ACC-DOWN
    check "status" 502 "$status"
    check "four GETs" 4 "$(count "$STATEMENT" GET "/accounts/ACC-DOWN/transactions")"

    scenario "ACC-SLOW: Statement API slower than the read timeout (~35s)"
    trigger ACC-SLOW
    check "status" 502 "$status"
    check "four GETs" 4 "$(count "$STATEMENT" GET "/accounts/ACC-SLOW/transactions")"
fi

echo
echo "$passed passed, $failed failed"
[[ $failed -eq 0 ]]
