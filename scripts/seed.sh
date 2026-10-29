#!/usr/bin/env bash
# Usage: scripts/seed.sh [users] [wallets_per_user] [peer_transfers]
# Default = 1,000 users, 2,000 wallets, 100,000 transfers (+2,000 top-ups).
set -euo pipefail
U=${1:-1000}; W=${2:-2}; T=${3:-100000}
docker compose exec -T db psql -U wallet -d wallet -v ON_ERROR_STOP=1 \
  -c "SELECT seed_demo_data($U, $W, $T);" \
  -c "ANALYZE users; ANALYZE accounts; ANALYZE transfers; ANALYZE ledger_entries;"
echo "seeded: $U users x $W wallets, $T peer transfers"
