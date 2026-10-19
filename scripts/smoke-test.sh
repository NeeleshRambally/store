#!/bin/sh
# Smoke test for a started store container: readiness, the API and management ports, and with "compose" the migration job, hardening, port bindings, runtime role and access log.
set -eu

app=$1
mgmt=$2
mode=${3:-}
body=$(mktemp)
oversized=$(mktemp)
trap 'rm -f "$body" "$oversized"' EXIT

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

as_store_app() {
  docker compose exec -T postgres psql -U store_app -d store -v ON_ERROR_STOP=1 -tAc "$1" > "$body" 2>&1
}

expect_denied() {
  name=$1
  want=$2
  shift 2
  if as_store_app "$1"; then fail "$name: statement succeeded"; fi
  contains "$name" "$want"
  echo "ok   $name -> denied"
}

expect_setting() {
  docker inspect -f "{{json .HostConfig.$3}}" "$2" > "$body" 2>&1 || fail "$1: docker inspect failed"
  grep -q -- "$4" "$body" || fail "$1: $5"
}

echo "Waiting for $mgmt/actuator/health/readiness"
deadline=$(( $(date +%s) + 120 ))
until [ "$(request "$mgmt/actuator/health/readiness")" = 200 ] && grep -q '"status":"UP"' "$body"; do
  [ "$(date +%s)" -lt "$deadline" ] || fail "readiness: not UP within 120 s"
  sleep 2
done
contains "readiness" '"db"'
echo "ok   readiness -> UP with db"

expect "GET /order/1" 200 "$app/order/1"
contains "GET /order/1" '"id":1'
contains "GET /order/1" '"products"'

expect "POST /customer" 201 -X POST -H 'Content-Type: application/json' -d '{"name":"Smoke Test"}' "$app/customer"

head -c 70000 /dev/zero | tr '\0' a > "$oversized"
expect "POST /order with a 70000 byte body" 413 -X POST -H 'Content-Type: application/json' --data-binary "@$oversized" "$app/order"
contains "POST /order with a 70000 byte body" 'Request body exceeds 65536 bytes'

expect "GET /products/2" 200 "$app/products/2"
contains "GET /products/2" '"orders"'

expect "GET /order?after=0&size=2" 200 "$app/order?after=0&size=2"
contains "GET /order?after=0&size=2" '"nextCursor"'

expect "GET /actuator/health on the application port" 404 "$app/actuator/health"

expect "GET /actuator/health/liveness on the management port" 200 "$mgmt/actuator/health/liveness"

if [ "$mode" = compose ]; then
  migrate=$(docker compose ps -aq migrate 2>/dev/null || true)
  [ -n "$migrate" ] || fail "migrate: no container for the migrate service"
  code=$(docker inspect -f '{{.State.ExitCode}}' "$migrate")
  [ "$code" = 0 ] || fail "migrate: exit code $code"
  echo "ok   migrate -> exit 0"

  for service in migrate store; do
    id=$(docker compose ps -aq "$service")
    expect_setting "$service" "$id" ReadonlyRootfs '^true$' 'root filesystem is not read-only'
    expect_setting "$service" "$id" Tmpfs '"/tmp"' '/tmp is not a tmpfs'
    expect_setting "$service" "$id" CapDrop '"ALL"' 'capabilities are not all dropped'
    expect_setting "$service" "$id" SecurityOpt '"no-new-privileges:true"' 'no-new-privileges is not set'
    expect_setting "$service" "$id" Memory '^536870912$' 'memory limit is not 512 MiB'
    expect_setting "$service" "$id" MemorySwap '^536870912$' 'memory plus swap limit is not 512 MiB, so the container can swap'
    expect_setting "$service" "$id" LogConfig '"Type":"json-file","Config":{"max-file":"3","max-size":"10m"}' 'logs are not rotated at 3 files of 10 MB'
    echo "ok   $service hardening -> read-only root, tmpfs /tmp, no capabilities, no-new-privileges, 512 MiB without swap, logs rotated at 3 x 10 MB"
  done

  bind_address=${STORE_BIND_ADDRESS:-127.0.0.1}
  published=0
  for service in postgres store; do
    docker inspect -f '{{range $port, $list := .HostConfig.PortBindings}}{{range $list}}{{$port}}={{.HostIp}} {{end}}{{end}}' \
      "$(docker compose ps -q "$service")" > "$body" 2>&1 || fail "$service: docker inspect failed"
    for binding in $(cat "$body"); do
      address=${binding#*=}
      [ "$address" = "$bind_address" ] || fail "$service: port ${binding%%=*} is published on ${address:-all interfaces}, expected $bind_address"
      published=$((published + 1))
    done
  done
  [ "$published" -gt 0 ] || fail "published ports: none found on postgres or store"
  echo "ok   published ports -> $published, all on $bind_address"

  as_store_app 'select count(*) from customer' || fail "store_app select: psql failed"
  grep -qE '^[0-9]+$' "$body" || fail "store_app select: no count in the output"
  echo "ok   store_app select customer -> $(cat "$body")"

  expect_denied "store_app create table" 'permission denied for schema public' 'create table smoke_probe(id int)'
  expect_denied "store_app update customer" 'permission denied for table customer' 'update customer set name = name where id = 1'

  expect "DELETE /order/1" 405 -X DELETE "$app/order/1"
  contains "DELETE /order/1" '"status":405'

  expect "GET /order/1 with X-Request-Id smoke-$$" 200 -H "X-Request-Id: smoke-$$" "$app/order/1"
  attempts=0
  until docker compose logs --no-log-prefix store | grep -F '"logger":"com.example.store.access"' \
      | grep -F "\"requestId\":\"smoke-$$\"" | grep -F '"route":"/order/{id}"' \
      | grep -Fq '"service":{"name":"store","version":"'; do
    attempts=$((attempts + 1))
    if [ "$attempts" -ge 5 ]; then
      docker compose logs --no-log-prefix --tail 20 store > "$body" 2>&1
      fail "store logs: no ECS JSON access line for GET /order/1 with requestId smoke-$$, its route and the service name and version"
    fi
    sleep 1
  done
  echo "ok   store logs -> ECS JSON access line for GET /order/1 with requestId smoke-$$"

  if docker compose logs --no-log-prefix store 2>&1 | grep -F 'Picked up JAVA_TOOL_OPTIONS' > "$body"; then
    fail "store logs: the JVM printed a Picked up JAVA_TOOL_OPTIONS line"
  fi
  echo "ok   store logs -> no Picked up JAVA_TOOL_OPTIONS line"
fi

echo "Smoke test passed"
