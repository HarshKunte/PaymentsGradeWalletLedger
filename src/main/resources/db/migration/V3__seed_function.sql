-- Bulk demo/test data generator:  SELECT seed_demo_data(1000, 2, 100000);
-- Builds consistent books: every wallet is topped up, then random peer transfers are posted,
-- stored balances are recomputed from the ledger. (The per-row balance trigger is disabled
-- only for the duration of this transaction; the DDL rolls back if anything fails.)
CREATE OR REPLACE FUNCTION seed_demo_data(p_users INT, p_wallets_per_user INT, p_transfers INT)
RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE
  v_batch   TEXT := to_char(clock_timestamp(), 'YYMMDDHH24MISSUS');
  v_cash_in BIGINT;
  v_before  BIGINT;
  v_wallets BIGINT[];
  v_n       INT;
BEGIN
  SELECT id INTO v_cash_in FROM accounts WHERE label = 'CASH_IN' AND user_id IS NULL;
  SELECT COALESCE(MAX(id), 0) INTO v_before FROM transfers;
  ALTER TABLE ledger_entries DISABLE TRIGGER trg_transfer_balanced;

  CREATE TEMP TABLE seed_wallets (id BIGINT) ON COMMIT DROP;
  WITH u AS (
    INSERT INTO users (name, email, phone, kyc_status)
    SELECT 'Seed User ' || g, 'seed-' || v_batch || '-' || g || '@example.com', 'seed-' || v_batch || '-' || g, 'VERIFIED'
    FROM generate_series(1, p_users) g
    RETURNING id
  ), a AS (
    INSERT INTO accounts (user_id, kind, label)
    SELECT u.id, 'USER_WALLET', CASE WHEN w = 1 THEN 'main' ELSE 'wallet-' || w END
    FROM u CROSS JOIN generate_series(1, p_wallets_per_user) w
    RETURNING id
  )
  INSERT INTO seed_wallets SELECT id FROM a;

  SELECT array_agg(id ORDER BY id) INTO v_wallets FROM seed_wallets;
  v_n := COALESCE(array_length(v_wallets, 1), 0);
  IF v_n < 2 THEN RAISE EXCEPTION 'need at least 2 wallets to seed transfers'; END IF;

  -- Rs 10,00,000 top-up per wallet, 40 days ago
  WITH t AS (
    INSERT INTO transfers (type, status, amount, from_account_id, to_account_id, created_at)
    SELECT 'TOPUP', 'COMPLETED', 100000000, v_cash_in, w.id, now() - interval '40 days' FROM seed_wallets w
    RETURNING id, from_account_id, to_account_id, amount, created_at
  )
  INSERT INTO ledger_entries (transfer_id, account_id, direction, amount, created_at)
  SELECT id, from_account_id, 'D', amount, created_at FROM t
  UNION ALL
  SELECT id, to_account_id,   'C', amount, created_at FROM t;

  -- random peer transfers over the last 30 days (Rs 0.01 .. Rs 500)
  WITH p AS (
    SELECT 1 + floor(random() * v_n)::int       AS ia,
           floor(random() * (v_n - 1))::int     AS off,
           1 + floor(random() * 50000)::int     AS amt,
           now() - random() * interval '30 days' AS ts
    FROM generate_series(1, p_transfers)
  ), q AS (
    SELECT amt, ts, v_wallets[ia] AS a, v_wallets[1 + ((ia - 1 + 1 + off) % v_n)] AS b FROM p
  ), t AS (
    INSERT INTO transfers (type, status, amount, from_account_id, to_account_id, created_at)
    SELECT 'PEER', 'COMPLETED', amt, a, b, ts FROM q
    RETURNING id, from_account_id, to_account_id, amount, created_at
  )
  INSERT INTO ledger_entries (transfer_id, account_id, direction, amount, created_at)
  SELECT id, from_account_id, 'D', amount, created_at FROM t
  UNION ALL
  SELECT id, to_account_id,   'C', amount, created_at FROM t;

  UPDATE accounts a SET balance = s.bal
  FROM (SELECT account_id, SUM(CASE direction WHEN 'C' THEN amount ELSE -amount END) AS bal
        FROM ledger_entries GROUP BY account_id) s
  WHERE s.account_id = a.id AND a.balance <> s.bal;

  INSERT INTO transfer_status_history (transfer_id, from_status, to_status, changed_by, reason)
  SELECT id, NULL, 'COMPLETED', 'seed', 'seed data' FROM transfers WHERE id > v_before;

  ALTER TABLE ledger_entries ENABLE TRIGGER trg_transfer_balanced;
END $$;
