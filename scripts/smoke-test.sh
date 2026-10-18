#!/bin/sh
# Smoke test for a started store container: waits for readiness, then exercises the API port and the management port.
set -eu

app=$1
mgmt=$2
body=$(mktemp)
trap 'rm -f "$body"' EXIT

request() {
  : > "$body"
  curl -s --max-time 5 -o "$body" -w '%{http_code}' "$@" || true
}

fail() {
  echo "FAIL $1"
  cat "$body"
  echo
  exit 1
}

expect() {
  name=$1
  want=$2
  shift 2
  code=$(request "$@")
  [ "$code" = "$want" ] || fail "$name: expected HTTP $want, got $code"
  echo "ok   $name -> $code"
}

contains() {
  grep -q -- "$2" "$body" || fail "$1: body does not contain $2"
}

echo "Waiting for $mgmt/actuator/health/readiness"
deadline=$(( $(date +%s) + 120 ))
until [ "$(request "$mgmt/actuator/health/readiness")" = 200 ] && grep -q '"status":"UP"' "$body"; do
  [ "$(date +%s)" -lt "$deadline" ] || fail "readiness: not UP within 120 s"
  sleep 2
done
echo "ok   readiness -> UP"

expect "GET /order/1" 200 "$app/order/1"
contains "GET /order/1" '"id":1'
contains "GET /order/1" '"products"'

expect "POST /customer" 201 -X POST -H 'Content-Type: application/json' -d '{"name":"Smoke Test"}' "$app/customer"

expect "GET /products/2" 200 "$app/products/2"
contains "GET /products/2" '"orders"'

expect "GET /order?after=0&size=2" 200 "$app/order?after=0&size=2"
contains "GET /order?after=0&size=2" '"nextCursor"'

expect "GET /actuator/health on the application port" 404 "$app/actuator/health"

expect "GET /actuator/health/liveness on the management port" 200 "$mgmt/actuator/health/liveness"

echo "Smoke test passed"
