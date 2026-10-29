-- Pick the busiest wallet, then compare OFFSET vs keyset pagination.
SELECT account_id AS busy FROM ledger_entries GROUP BY account_id ORDER BY count(*) DESC LIMIT 1 \gset
\echo '--- OFFSET (deep page)'
EXPLAIN (ANALYZE, BUFFERS) SELECT id, created_at FROM ledger_entries WHERE account_id = :busy
  ORDER BY created_at DESC, id DESC LIMIT 50 OFFSET 100000;
\echo '--- KEYSET (same page, using the (created_at, id) of the last row seen)'
SELECT created_at AS c_at, id AS c_id FROM ledger_entries WHERE account_id = :busy
  ORDER BY created_at DESC, id DESC OFFSET 100000 LIMIT 1 \gset
EXPLAIN (ANALYZE, BUFFERS) SELECT id, created_at FROM ledger_entries WHERE account_id = :busy
  AND (created_at, id) < (:'c_at', :c_id) ORDER BY created_at DESC, id DESC LIMIT 50;
