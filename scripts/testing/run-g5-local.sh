#!/usr/bin/env bash
# Disposable PostgreSQL + local backend; never reads the project's .env.
set -euo pipefail
project_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
pg_bin=${G5_PG_BIN:-$(pg_config --bindir)}
pg_port=${G5_PG_PORT:-55439}
task_dir=$(mktemp -d /tmp/cinema-g5.XXXXXX)
backend_pid=''
cleanup() {
  if [[ -n "$backend_pid" ]]; then kill "$backend_pid" 2>/dev/null || true; fi
  "$pg_bin/pg_ctl" -D "$task_dir/pg" -m fast stop >/dev/null 2>&1 || true
  printf 'Test logs and artifacts: %s\n' "$task_dir"
}
trap cleanup EXIT
"$pg_bin/initdb" -D "$task_dir/pg" -U cinema_test --auth=trust > "$task_dir/initdb.log"
"$pg_bin/pg_ctl" -D "$task_dir/pg" -l "$task_dir/postgres.log" -o "-p $pg_port -k $task_dir -h 127.0.0.1" start
"$pg_bin/createdb" -h 127.0.0.1 -p "$pg_port" -U cinema_test cinema_g5_test
"$pg_bin/createdb" -h 127.0.0.1 -p "$pg_port" -U cinema_test cinema_g5_live
export DB_TEST_URL="jdbc:postgresql://127.0.0.1:$pg_port/cinema_g5_test" DB_TEST_USERNAME=cinema_test DB_TEST_PASSWORD=''
export DB_URL="jdbc:postgresql://127.0.0.1:$pg_port/cinema_g5_live" DB_USERNAME=cinema_test DB_PASSWORD=''
export DEMO_PASSWORD="G5-$(openssl rand -hex 12)" DEMO_MANAGER_PIN=123456 JWT_SECRET="$(openssl rand -hex 32)"
export TEST_VNPAY_HASH_SECRET="$(openssl rand -hex 32)" VNPAY_TMN_CODE=G5TEST
export VNPAY_HASH_SECRET="$TEST_VNPAY_HASH_SECRET" VNPAY_PAY_URL=https://sandbox.vnpayment.vn/paymentv2/vpcpay.html
export VNPAY_RETURN_URL=http://localhost:3003/payments/vnpay/return VNPAY_IPN_URL=http://localhost:8080/api/v1/payments/vnpay/ipn
export CINEMA_BANK_NAME=VietinBank CINEMA_BANK_BIN=970415 CINEMA_BANK_ACCOUNT=0123456789
(cd "$project_root/backend" && bash mvnw -B -ntp verify) > "$task_dir/backend-verify.log" 2>&1
java -jar "$project_root/backend/target/cms-0.1.0-SNAPSHOT.jar" --spring.profiles.active=local > "$task_dir/backend-live.log" 2>&1 &
backend_pid=$!
ready=false
for _ in {1..60}; do
  if curl -fsS http://localhost:8080/v3/api-docs -o /dev/null; then ready=true; break; fi
  if ! kill -0 "$backend_pid" 2>/dev/null; then cat "$task_dir/backend-live.log"; exit 1; fi
  sleep 1
done
if [[ "$ready" != true ]]; then printf 'Backend startup failed; see %s\n' "$task_dir/backend-live.log"; exit 1; fi
"$pg_bin/psql" -h 127.0.0.1 -p "$pg_port" -U cinema_test -d cinema_g5_live -f "$project_root/scripts/testing/seed-seat-demo.sql"
cd "$project_root/frontend"
npm test
npm run build
npm run test:e2e:mock
npm run test:e2e:live
if [[ -n "${CINEMA_EDGE_EXECUTABLE:-}" ]]; then npm run test:e2e:edge; fi
