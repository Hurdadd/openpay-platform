#!/usr/bin/env bash
# End-to-end demo: create payment -> signed SETTLED callback -> ledger balance.
# Prereq: docker compose up -d --build
set -euo pipefail

API=${API:-http://localhost:8080}
SECRET=${SLOW_PSP_SECRET:-dev-secret-change-me}
MERCHANT=22222222-2222-2222-2222-222222222222
psql() { docker compose exec -T postgres psql -U openpay -d "$1" -tA -c "$2"; }

echo "1) create payment"
RESP=$(curl -fsS -X POST "$API/payments" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: demo-$(date +%s)" \
  -d "{\"amount\": 100.00, \"currency\": \"USD\", \"merchantId\": \"$MERCHANT\"}")
echo "$RESP"
PAYMENT_ID=$(echo "$RESP" | sed -E 's/.*"paymentId":"([^"]+)".*/\1/')
PSP_REF=$(psql openpay "SELECT psp_reference FROM payments WHERE payment_id = '$PAYMENT_ID'")

echo "2) signed SETTLED callback for psp reference $PSP_REF"
TS=$(date +%s); NONCE=$(uuidgen)
BODY="{\"pspReference\":\"$PSP_REF\",\"status\":\"SETTLED\"}"
SIG=$(printf '%s.%s.%s' "$TS" "$NONCE" "$BODY" | openssl dgst -sha256 -hmac "$SECRET" -hex | awk '{print $NF}')
curl -fsS -o /dev/null -w 'callback HTTP %{http_code}\n' -X POST "$API/callbacks/psp/slow-psp" \
  -H 'Content-Type: application/json' \
  -H "X-Psp-Timestamp: $TS" -H "X-Psp-Nonce: $NONCE" -H "X-Psp-Signature: $SIG" -d "$BODY"

echo "3) waiting for outbox -> Kafka -> ledger"
for _ in $(seq 1 30); do
  [ "$(psql openpay_ledger 'SELECT count(*) FROM journal_entry')" -gt 0 ] && break
  sleep 1
done

echo "4) ledger balances (minor units)"
psql openpay_ledger "SELECT a.name, a.type, a.currency, SUM(p.amount) FROM posting p JOIN account a ON a.id = p.account_id GROUP BY 1,2,3 ORDER BY 1"
