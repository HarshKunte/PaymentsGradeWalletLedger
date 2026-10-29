-- Run after seeding ~1M entries:  scripts/seed.sh 1000 2 500000
-- docker compose exec -T db psql -U wallet -d wallet -f /dev/stdin < scripts/explain_recon.sql
\echo '--- 1 GLOBAL_BALANCE'
EXPLAIN (ANALYZE, BUFFERS) SELECT COALESCE(SUM(amount) FILTER (WHERE direction='D'),0), COALESCE(SUM(amount) FILTER (WHERE direction='C'),0) FROM ledger_entries;
\echo '--- 2 TRANSFER_BALANCE'
EXPLAIN (ANALYZE, BUFFERS) SELECT transfer_id FROM ledger_entries GROUP BY transfer_id
  HAVING COALESCE(SUM(amount) FILTER (WHERE direction='D'),0) <> COALESCE(SUM(amount) FILTER (WHERE direction='C'),0);
\echo '--- 3/4 computed balances (STORED_BALANCE, OVERDRAFT)'
EXPLAIN (ANALYZE, BUFFERS) SELECT a.id FROM accounts a LEFT JOIN
  (SELECT account_id, SUM(CASE direction WHEN 'C' THEN amount ELSE -amount END) bal FROM ledger_entries GROUP BY account_id) b
  ON b.account_id = a.id WHERE a.balance <> COALESCE(b.bal,0);
\echo '--- 5a ORPHAN_TRANSFER'
EXPLAIN (ANALYZE, BUFFERS) SELECT t.id FROM transfers t WHERE t.status IN ('COMPLETED','REVERSED')
  AND NOT EXISTS (SELECT 1 FROM ledger_entries e WHERE e.transfer_id = t.id);
\echo '--- 5b ORPHAN_ENTRY'
EXPLAIN (ANALYZE, BUFFERS) SELECT e.id FROM ledger_entries e LEFT JOIN transfers t ON t.id = e.transfer_id WHERE t.id IS NULL;
