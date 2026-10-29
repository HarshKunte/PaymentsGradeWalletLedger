#!/usr/bin/env bash
# End-to-end walkthrough for the demo video. Needs: curl, jq. API on :8080.
set -euo pipefail
B=${BASE:-http://localhost:8080}; ADMIN=${ADMIN_TOKEN:-dev-admin-token}
k() { uuidgen | tr 'A-Z' 'a-z'; }
post() { curl -s -X POST "$B$1" -H 'Content-Type: application/json' -H "Idempotency-Key: ${3:-$(k)}" "${@:4}" -d "$2"; }
S=$RANDOM
A=$(post /users "{\"name\":\"Asha\",\"email\":\"asha$S@x.com\",\"phone\":\"a$S\"}" | jq .user_id)
R=$(post /users "{\"name\":\"Rahul\",\"email\":\"rahul$S@x.com\",\"phone\":\"r$S\"}" | jq .user_id)
WA=$(post /users/$A/wallets '{"label":"main"}' | jq .wallet_id)
WR=$(post /users/$R/wallets '{"label":"main"}' | jq .wallet_id)

echo "== 1. top up Rs 5,000 then send Rs 500 (one transfer end to end)"
post /transfers/topup "{\"wallet_id\":$WA,\"amount\":500000}" | jq .
KEY=$(k)
post /transfers "{\"from_wallet_id\":$WA,\"to_wallet_id\":$WR,\"amount\":50000,\"note\":\"rent\"}" "$KEY" | jq .
echo "== 2. client retries with the SAME key: same response, no second charge"
post /transfers "{\"from_wallet_id\":$WA,\"to_wallet_id\":$WR,\"amount\":50000,\"note\":\"rent\"}" "$KEY" | jq .
curl -s $B/wallets/$WA/balance | jq .

echo "== 3. trip the velocity rule: 6th rapid transfer is HELD (202)"
for i in 1 2 3 4 5; do post /transfers "{\"from_wallet_id\":$WA,\"to_wallet_id\":$WR,\"amount\":100}" >/dev/null; done
HELD=$(post /transfers "{\"from_wallet_id\":$WA,\"to_wallet_id\":$WR,\"amount\":100}" | tee /dev/stderr | jq .transfer_id)
curl -s $B/admin/risk/queue -H "X-Admin-Token: $ADMIN" | jq '.[0]'
post /admin/risk/$HELD/decision '{"decision":"release"}' "$(k)" -H "X-Admin-Token: $ADMIN" | jq .

echo "== 4. reconciliation run"
RUN=$(curl -s -X POST $B/admin/reconciliation/run -H "X-Admin-Token: $ADMIN" | jq .run_id); sleep 2
curl -s $B/admin/reconciliation/runs/$RUN -H "X-Admin-Token: $ADMIN" | jq .

echo "== 5. statement Q&A (needs LLM_PROVIDER=anthropic + ANTHROPIC_API_KEY)"
curl -s -X POST $B/users/$A/statement/ask -H 'Content-Type: application/json' -d '{"question":"How much did I send to Rahul this month?"}' | jq .
