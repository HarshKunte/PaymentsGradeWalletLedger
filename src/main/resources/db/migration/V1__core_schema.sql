-- ===== users =====
CREATE TABLE users (
  id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name       TEXT NOT NULL CHECK (length(trim(name)) > 0),
  email      TEXT NOT NULL,
  phone      TEXT NOT NULL,
  kyc_status TEXT NOT NULL DEFAULT 'PENDING' CHECK (kyc_status IN ('PENDING','VERIFIED','REJECTED')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- email/phone are data we do not control: unique (natural keys) but never primary keys
CREATE UNIQUE INDEX users_email_uq ON users (lower(email));
CREATE UNIQUE INDEX users_phone_uq ON users (phone);

-- ===== accounts: user wallets AND system accounts in one table =====
CREATE TABLE accounts (
  id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id    BIGINT REFERENCES users(id),
  kind       TEXT NOT NULL CHECK (kind IN ('USER_WALLET','SYSTEM')),
  label      TEXT NOT NULL,                       -- 'main', 'savings', ... or CASH_IN / CASH_OUT / FEE_REVENUE
  currency   CHAR(3) NOT NULL DEFAULT 'INR' CHECK (currency = 'INR'),
  balance    BIGINT NOT NULL DEFAULT 0,           -- paise; credits - debits; kept in step with the ledger
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT accounts_owner_ck CHECK ((kind = 'USER_WALLET' AND user_id IS NOT NULL)
                                   OR (kind = 'SYSTEM'      AND user_id IS NULL)),
  CONSTRAINT accounts_no_overdraft_ck CHECK (kind = 'SYSTEM' OR balance >= 0)
);
CREATE UNIQUE INDEX accounts_user_label_uq   ON accounts (user_id, label) WHERE user_id IS NOT NULL;
CREATE UNIQUE INDEX accounts_system_label_uq ON accounts (label)          WHERE user_id IS NULL;
INSERT INTO accounts (kind, label) VALUES ('SYSTEM','CASH_IN'), ('SYSTEM','CASH_OUT'), ('SYSTEM','FEE_REVENUE');

-- ===== transfers: the business event =====
CREATE TABLE transfers (
  id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  type                 TEXT NOT NULL CHECK (type IN ('TOPUP','PEER','WITHDRAWAL','FEE','REVERSAL')),
  status               TEXT NOT NULL CHECK (status IN ('HELD','COMPLETED','REVERSED','REJECTED')),
  amount               BIGINT NOT NULL CHECK (amount > 0),
  from_account_id      BIGINT NOT NULL REFERENCES accounts(id),
  to_account_id        BIGINT NOT NULL REFERENCES accounts(id),
  reverses_transfer_id BIGINT REFERENCES transfers(id),
  note                 TEXT,
  created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT transfers_distinct_accounts_ck CHECK (from_account_id <> to_account_id),
  CONSTRAINT transfers_reversal_ck CHECK ((type = 'REVERSAL') = (reverses_transfer_id IS NOT NULL))
);
-- a transfer can be reversed at most once, enforced by the database
CREATE UNIQUE INDEX transfers_one_reversal_uq ON transfers (reverses_transfer_id) WHERE reverses_transfer_id IS NOT NULL;
-- fraud rules (velocity, amount spike) + statement Q&A
CREATE INDEX transfers_from_time_idx ON transfers (from_account_id, created_at DESC) INCLUDE (amount);
-- fraud rule: new payee
CREATE INDEX transfers_from_to_idx   ON transfers (from_account_id, to_account_id);
-- admin queue
CREATE INDEX transfers_held_idx      ON transfers (id) WHERE status = 'HELD';

-- ===== audit log of status changes =====
CREATE TABLE transfer_status_history (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  transfer_id BIGINT NOT NULL REFERENCES transfers(id),
  from_status TEXT,
  to_status   TEXT NOT NULL,
  changed_by  TEXT NOT NULL,          -- 'user:42', 'admin:priya', 'system', 'seed'
  reason      TEXT,
  changed_at  TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX transfer_status_history_transfer_idx ON transfer_status_history (transfer_id, id);

-- ===== ledger entries: append-only, positive amount + direction =====
CREATE TABLE ledger_entries (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  transfer_id BIGINT NOT NULL REFERENCES transfers(id),
  account_id  BIGINT NOT NULL REFERENCES accounts(id),
  direction   CHAR(1) NOT NULL CHECK (direction IN ('D','C')),
  amount      BIGINT NOT NULL CHECK (amount > 0),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- statement: WHERE account_id = ? ORDER BY created_at DESC, id DESC  (also serves keyset pagination)
CREATE INDEX ledger_entries_account_time_idx ON ledger_entries (account_id, created_at DESC, id DESC);
-- per-transfer balance check, GET /transfers/{id}, orphan check
CREATE INDEX ledger_entries_transfer_idx ON ledger_entries (transfer_id);

-- never UPDATE / DELETE / TRUNCATE a ledger entry
CREATE FUNCTION ledger_entries_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'ledger_entries is append-only (% is not allowed); post a reversal transfer instead', TG_OP
    USING ERRCODE = 'integrity_constraint_violation';
END $$;
CREATE TRIGGER trg_ledger_immutable   BEFORE UPDATE OR DELETE ON ledger_entries FOR EACH ROW       EXECUTE FUNCTION ledger_entries_immutable();
CREATE TRIGGER trg_ledger_no_truncate BEFORE TRUNCATE         ON ledger_entries FOR EACH STATEMENT EXECUTE FUNCTION ledger_entries_immutable();

-- debits must equal credits for every transfer, checked at COMMIT (deferred), so the
-- application can insert the two lines in any order inside one transaction
CREATE FUNCTION assert_transfer_balanced() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE diff BIGINT;
BEGIN
  SELECT COALESCE(SUM(CASE direction WHEN 'D' THEN amount ELSE -amount END), 0)
    INTO diff FROM ledger_entries WHERE transfer_id = NEW.transfer_id;
  IF diff <> 0 THEN
    RAISE EXCEPTION 'transfer % is unbalanced (debits - credits = %)', NEW.transfer_id, diff
      USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER trg_transfer_balanced AFTER INSERT ON ledger_entries
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION assert_transfer_balanced();

-- ===== idempotency keys =====
-- user_id 0 = platform scope (create-user, admin calls); deliberately no FK so it can hold 0.
CREATE TABLE idempotency_keys (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id         BIGINT NOT NULL,
  idempotency_key UUID   NOT NULL,
  request_hash    CHAR(64) NOT NULL,
  status          TEXT NOT NULL DEFAULT 'IN_PROGRESS' CHECK (status IN ('IN_PROGRESS','COMPLETED')),
  response_code   INT,
  response_body   TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT idempotency_user_key_uq UNIQUE (user_id, idempotency_key)
);
CREATE INDEX idempotency_created_idx ON idempotency_keys (created_at);
