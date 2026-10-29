-- ===== fraud rules: thresholds live in data, not code =====
CREATE TABLE risk_rules (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code        TEXT NOT NULL UNIQUE,
  description TEXT NOT NULL,
  action      TEXT NOT NULL CHECK (action IN ('HOLD','FLAG')),
  params      JSONB NOT NULL,
  enabled     BOOLEAN NOT NULL DEFAULT true,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO risk_rules (code, description, action, params) VALUES
 ('VELOCITY',        'More than max_count outgoing transfers from one wallet in window_minutes', 'HOLD', '{"max_count":5,"window_minutes":10}'),
 ('AMOUNT_SPIKE',    'Amount is over multiplier x the wallet''s lookback_days average transfer',  'HOLD', '{"multiplier":5,"lookback_days":30}'),
 ('NEW_PAYEE_LARGE', 'First transfer to this recipient, above min_amount_paise (Rs 10,000)',      'FLAG', '{"min_amount_paise":1000000}');

CREATE TABLE risk_flags (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  transfer_id     BIGINT NOT NULL REFERENCES transfers(id),
  rule_code       TEXT NOT NULL,
  action          TEXT NOT NULL CHECK (action IN ('HOLD','FLAG')),
  llm_label       TEXT CHECK (llm_label IN ('likely_ok','review','likely_fraud')),
  llm_explanation TEXT,
  explained_at    TIMESTAMPTZ,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX risk_flags_transfer_idx    ON risk_flags (transfer_id);
CREATE INDEX risk_flags_unexplained_idx ON risk_flags (created_at) WHERE llm_label IS NULL;

-- ===== nightly reconciliation =====
CREATE TABLE recon_runs (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  started_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  finished_at TIMESTAMPTZ,
  status      TEXT NOT NULL CHECK (status IN ('RUNNING','OK','ISSUES_FOUND','SKIPPED','FAILED')),
  checks_run  INT NOT NULL DEFAULT 0,
  issue_count INT NOT NULL DEFAULT 0,
  note        TEXT
);
CREATE TABLE recon_issues (
  id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  run_id     BIGINT NOT NULL REFERENCES recon_runs(id),
  check_name TEXT   NOT NULL,
  ref_id     BIGINT NOT NULL,         -- transfer / account / entry id (0 for the global check)
  expected   BIGINT,
  actual     BIGINT
);
CREATE INDEX recon_issues_run_idx ON recon_issues (run_id);

-- one row per job; a run starts with SELECT ... FOR UPDATE NOWAIT on its row
CREATE TABLE job_locks (job_name TEXT PRIMARY KEY);
INSERT INTO job_locks (job_name) VALUES ('reconciliation');
