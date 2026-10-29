# Digital Wallet Backend

A payments-grade wallet backend: double-entry ledger, safe concurrent transfers, idempotent retries, nightly reconciliation, fraud rules, and an LLM that explains flags and answers statement questions.

**Stack:** Java 21 · Spring Boot 3.3 · PostgreSQL 16 · Flyway · plain SQL through `JdbcTemplate` (no ORM, so the queries, transaction boundaries and row locks are visible in the code).

> Status note: the code, migrations and tests were written without being executed in the authoring environment (no Docker/Gradle/Postgres available there). Run `docker compose --profile test run --rm tests` first. If anything fails, it is a bug to fix, not a documentation problem. Every "measure it yourself" item below is left blank on purpose.

---

## 1. Setup and run

### Prerequisites
Docker + Docker Compose. (Java 21 and Gradle 8.10 only if you want to run outside Docker; `curl` + `jq` for the demo script.)

### Start everything (one command)
```bash
docker compose up --build
```
This starts three containers:

| Service | What it does | Port |
|---|---|---|
| `db` | PostgreSQL 16 (also creates a separate `wallet_test` database for tests) | 5432 |
| `api` | REST API; runs the Flyway migrations on boot | 8080 |
| `worker` | Same image with the scheduler on: nightly reconciliation (01:30 IST), LLM flag explanations, 24h idempotency-key cleanup | (8081, internal) |

Health check: `curl localhost:8080/actuator/health`

### Seed data (1,000 users · 2,000 wallets · 100,000 transfers)
```bash
scripts/seed.sh                 # defaults above
scripts/seed.sh 1000 2 500000   # ~1M ledger entries, for the EXPLAIN ANALYZE exercises
```

### Run the demo (one transfer, one retry, one held transfer, one reconciliation, one Q&A)
```bash
scripts/demo.sh
```

### Run all tests
```bash
docker compose --profile test run --rm tests     # uses the compose Postgres (wallet_test DB)
# or, with Docker available locally (Testcontainers starts its own Postgres):
./gradlew test
```
CI (`.github/workflows/ci.yml`) runs `./gradlew test` on every push.

| Test class | What it proves |
|---|---|
| `StampedeTest` | ₹500 + 100 concurrent ₹10 transfers → exactly 50 succeed, wallet ends at ₹0, debits = credits. **Repeated 20×.** |
| `IdempotencyTest` | Same request ×5 → 1 transfer, 5 identical responses. Same key ×10 in parallel → 1 transfer. Same key + different amount → 422, nothing moves. Missing key → 400. |
| `ReconciliationTest` | Seeds 10,000 transfers, corrupts 3 things, the job reports exactly those and nothing else. A second concurrent run is skipped (`NOWAIT`). |
| `RiskFlowTest` | Velocity rule holds the 6th transfer (202, no money moves) → admin release → reversal → double-reversal 409. Ledger immutability. Unbalanced transfer rejected at commit. |
| `StatementQaTest` | 10 sample questions with expected answers (mock LLM), unsupported/garbled model output refused, **prompt-injection test**. |
| `NaiveRaceDemoTest` | Milestone 2 Step 2: the lock-free transfer, prints the overspend. Asserts nothing. |

### Configuration (environment variables)

| Variable | Default | Meaning |
|---|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | compose values | Postgres connection |
| `ADMIN_TOKEN` | `dev-admin-token` | Sent as `X-Admin-Token` to privileged endpoints |
| `LLM_PROVIDER` | `mock` | `mock` (no network) or `anthropic` |
| `ANTHROPIC_API_KEY`, `LLM_MODEL` | – / `claude-haiku-5-5` | Needed when provider is `anthropic` |
| `WALLET_SCHEDULER_ENABLED` / `WALLET_SCHEDULER_CRON` | `false` (api), `true` (worker) / `0 30 1 * * *` | Nightly job |
| `WALLET_ZONE` | `Asia/Kolkata` | Time zone for date ranges in Q&A and the cron |

To use real Q&A in the demo: `LLM_PROVIDER=anthropic ANTHROPIC_API_KEY=sk-... docker compose up --build`.
With the default `mock` provider, Q&A answers only questions pre-registered in tests and otherwise replies with the "I can only answer four kinds of question" message.

---

## 2. API

Every `POST` that writes data needs an `Idempotency-Key: <uuid>` header. Amounts are integer **paise** (₹1 = 100). JSON fields are `snake_case`. Errors always look like `{"code":"INSUFFICIENT_FUNDS","message":"…","request_id":"…"}`; the `request_id` is also returned as the `X-Request-Id` header.

| Method | Path | Purpose | Responses |
|---|---|---|---|
| POST | `/users` | `{name,email,phone,kyc_status?}` | 201, 409 |
| POST | `/users/{id}/wallets` | `{label?}` (default `main`) | 201, 404, 409 |
| GET | `/wallets/{id}/balance` | Current balance | 200 |
| GET | `/wallets/{id}/statement?limit=50&offset=0` or `?cursor=…` | Entries newest first (`ORDER BY created_at DESC, id DESC`). `next_cursor` enables keyset pagination | 200 |
| POST | `/transfers/topup` | `{wallet_id, amount, note?}` CASH_IN → wallet | 201, 409, 422 |
| POST | `/transfers` | `{from_wallet_id, to_wallet_id, amount, note?}` | 201, **202 (held)**, 409, 422 |
| POST | `/transfers/withdraw` | `{wallet_id, amount, note?}` wallet → CASH_OUT | 201, 422 |
| POST | `/transfers/{id}/reverse` | New REVERSAL transfer pointing at the original. **Needs `X-Admin-Token`** | 201, 409 |
| GET | `/transfers/{id}` | Transfer, its entries, status history, flags | 200 |
| POST | `/admin/reconciliation/run` | Manual run, returns `{run_id}` | 202 |
| GET | `/admin/reconciliation/runs/{id}` | Run report and issues | 200 |
| GET | `/admin/risk/queue` | Held and flagged transfers (+ LLM label/explanation) | 200 |
| POST | `/admin/risk/{transfer_id}/decision` | `{decision: "release"\|"reverse", reason?}` | 200 |
| POST | `/users/{id}/statement/ask` | `{question}` | 200 |

All `/admin/*` endpoints need `X-Admin-Token`; an optional `X-Admin-Name` is recorded in the audit log.

**Idempotency behaviour** (`IdempotencyService`)

| Situation | Server does | Response |
|---|---|---|
| New key | Inserts the key and runs the transfer in one transaction, saves the response, commits | 201 |
| Key seen, same body, completed | Returns the saved response exactly (plus header `Idempotent-Replayed: true`) | original status |
| Key seen, same body, still running | The unique-index insert waits; if the first request is still running after 2 s → | 409 |
| Key seen, different body | Rejects; no money moves | 422 |

**Fraud rules** (thresholds live in the `risk_rules` table; edit them with SQL, no redeploy)

| Rule | Fires when | Action |
|---|---|---|
| `VELOCITY` | More than 5 outgoing transfers from one wallet in 10 minutes | Hold (202) |
| `AMOUNT_SPIKE` | Amount > 5× the wallet's 30-day average transfer | Hold (202) |
| `NEW_PAYEE_LARGE` | First transfer to this recipient, above ₹10,000 | Flag, allow (201) |

---

## 3. Architecture in one page

```
client ──HTTP──► Controller ──► IdempotencyService ──BEGIN──► insert key
                                                      │        TransferService.post():
                                                      │          1 lock both accounts (FOR UPDATE, ascending id)
                                                      │          2 re-check balance
                                                      │          3 fraud rules (HOLD → no money moves)
                                                      │          4 INSERT transfer + D/C entries + UPDATE balances + audit row
                                                      └─COMMIT◄─ save response on key; deferred trigger checks debits = credits

worker ──cron──► ReconciliationService (REPEATABLE READ, FOR UPDATE NOWAIT) ──► recon_runs / recon_issues
              └► RiskExplainService (LLM explains flags) ──► risk_flags
              └► delete idempotency keys > 24 h
```
Code map: `common/` (errors, idempotency, request id), `ledger/` (**`TransferService` is the money path**), `risk/`, `recon/`, `llm/`, `qa/`, `web/`, `jobs/`. Schema: `docs/schema.md`. Isolation lab: `docs/isolation.md`.

---

## 4. Design questions

### Which isolation level does the transfer run at, and why?
**READ COMMITTED (the Postgres default) plus explicit row locks.** The transfer takes `SELECT … FOR UPDATE` on both account rows (always ascending `id`), then re-reads the balance *while holding the lock*. Because every competing transfer on that wallet must take the same lock first, they run one after another for that wallet and each sees the previous one's committed balance. That is enough for the invariants here: the only shared state a transfer reads-then-writes is the two account rows, and both are locked.

Alternative: **SERIALIZABLE** without locks. It is simpler to reason about ("as if one at a time" for *any* read/write pattern, including ones I forgot to lock), but it works by aborting transactions (`40001`) when it detects a dangerous pattern. Under the stampede test most of the 100 transfers hit the same wallet, so a large share would be aborted and need retry; throughput drops, tail latency grows and every client path needs retry logic. Row locks queue the conflicting transactions instead of aborting them, so the hot-wallet case degrades gracefully. I would reach for SERIALIZABLE when an invariant spans rows that cannot be cheaply locked (e.g. "total across a user's wallets ≤ limit"). The reconciliation job uses **REPEATABLE READ** because it only reads and needs one consistent snapshot across many tables.

### Deadlock avoidance
Two opposite transfers A→B and B→A would lock `A,B` and `B,A` and wait forever. Locking in ascending `id` makes every transaction acquire locks in the same global order, so a wait cycle cannot form. Other lock order rule: a transaction that locks a *transfer* row (reverse, admin decision) does so before locking accounts, and nothing locks accounts then a transfer row.

### Where idempotency and the transfer meet
The idempotency key is inserted **inside the same transaction** as the transfer. If the transfer fails, the key insert rolls back with it, so a failed request (e.g. 422 insufficient funds) can be retried with the same key. If it commits, the saved response commits atomically with the money movement, so there is no window where money moved but the key does not exist.

### Trade-offs I made
* **Stored balance + ledger** (see `docs/schema.md`): fast funds check and a hard CHECK constraint, at the price of a second write and a drift risk that reconciliation watches.
* **Held transfers reserve nothing.** The brief says a held transfer moves no money, so funds are re-checked on release and the release can fail with 422 if the wallet was spent meanwhile. Reserving funds would need a "pending" state and is listed under next steps.
* **Fraud rules read live tables** inside the transfer transaction (simple, always correct, runs under the wallet lock) instead of a precomputed counter; cost is measured below.
* **OFFSET pagination is offered because the brief asks for it**, but it is the slow path (see below). Keyset (`cursor`) is the one to use.
* **LLM output is never trusted:** whitelisted JSON, validated in code, own parameterised SQL. The model never sees ledger rows or transfer notes in the Q&A path, and in the flag-explanation path notes are passed only inside a JSON data blob with an explicit "data, not instructions" rule, and the model's output can only set a label/explanation, never a decision.

### Assumptions (written down as the brief asks)
1. **No authentication is specified.** The acting user is the owner of the source wallet. Privileged operations (reverse, all `/admin/*`) require a shared `X-Admin-Token`. Real auth (JWT/OAuth) is out of scope.
2. `POST /users/{id}/statement/ask` is read-only and `POST /admin/reconciliation/run` only triggers a job, so neither requires an `Idempotency-Key`. Every other POST does. Keys are scoped per user; calls without a user (create user, admin actions) use scope `0`.
3. **Failed requests are not cached** under their key: they roll back fully and can be retried.
4. KYC status is stored but not enforced on transfers.
5. `FEE` is a valid transfer type in the schema and a `FEE_REVENUE` account exists, but no endpoint charges fees yet.
6. A **held transfer has no ledger entries** until released (the brief's "moves no money"). Reconciliation therefore only expects entries for `COMPLETED`/`REVERSED` transfers. Rejected holds stay entry-less.
7. Q&A counts only `COMPLETED` **peer** transfers between users (top-ups/withdrawals are not "sent to a person"). "Largest" and "number of" consider both directions. Counterparty names are matched case-insensitively as substrings of the recipient's name; date ranges are inclusive and interpreted in `Asia/Kolkata`.
8. "The wallet's last 10 transfers" for flag explanations means its last 10 *outgoing* transfers (the activity the rules look at).
9. Reversing a transfer needs the original recipient to still have the money (INSUFFICIENT_FUNDS otherwise); system accounts may go negative.
10. One reconciliation corruption can trip several independent checks (a deleted credit unbalances its transfer, the global total, and the credited wallet's stored balance). The acceptance test therefore asserts that every reported issue traces back to the three corrupted objects and nothing else appears.

---

## 5. Performance exercises (fill in after running)

### Reconciliation checks at ~1M entries
```bash
scripts/seed.sh 1000 2 500000
docker compose exec -T db psql -U wallet -d wallet < scripts/explain_recon.sql
```
| Check | Plan you got | ms | Full scan OK? |
|---|---|---|---|
| 1 Global balance | | | Expected yes: must read every row anyway. It is a nightly job. |
| 2 Per-transfer balance (`GROUP BY … HAVING`) | | | Expected yes (hash/sort aggregate). The `transfer_id` index can help the sort. |
| 3/4 Stored balance / overdraft | | | Expected yes for the entry aggregate, and one pass over `accounts`. |
| 5a Orphan transfer (`NOT EXISTS`) | | | **Needs `ledger_entries(transfer_id)`**: without it, an anti-join per transfer is quadratic. |
| 5b Orphan entry | | | Uses the `transfers` primary key; hash anti-join. |

### Fraud-rule overhead
Measure transfers/sec with all rules enabled vs. disabled (`UPDATE risk_rules SET enabled=false`), e.g. with `scripts/demo.sh` in a loop or `hey`/`wrk` against `POST /transfers`:

| Rule | Index | Time added per transfer |
|---|---|---|
| VELOCITY | `transfers_from_time_idx` | |
| AMOUNT_SPIKE | `transfers_from_time_idx INCLUDE (amount)` | |
| NEW_PAYEE_LARGE | `transfers_from_to_idx` | |

### Why OFFSET gets slow (and the keyset fix)
```bash
docker compose exec -T db psql -U wallet -d wallet < scripts/explain_pagination.sql
```
`OFFSET 100000` makes Postgres walk the index and **discard** 100,000 entries before returning the 50 you asked for, so cost grows linearly with page depth (and each discarded row may need a heap fetch / the join to `transfers`). Keyset pagination (`WHERE (created_at, id) < (?, ?) ORDER BY created_at DESC, id DESC LIMIT 50`) jumps straight into the `(account_id, created_at DESC, id DESC)` index at the last row seen, so every page costs the same. Record both plans here:

| | Plan | ms |
|---|---|---|
| OFFSET 100000 | | |
| Keyset | | |

### Isolation lab, stampede and naive-race numbers
* Isolation lab transcript → `docs/isolation.md`
* Naive (lock-free) result: run `./gradlew test --tests com.example.wallet.NaiveRaceDemoTest` and paste the printed lines: successes (should be 50), stored balance (should be 0).
* Stampede: `./gradlew test --tests com.example.wallet.StampedeTest` (20 rounds).

---

## 6. Stretch milestone: sharding with consistent hashing (design sketch)
Shard by **wallet owner (`user_id`)** so a user's wallets and statements live on one shard. Place shards on a consistent-hash ring with ~100–200 virtual nodes each, so adding a shard moves only ≈ 1/N of the keys. Peer transfers across shards then need either a **saga** (debit on shard A, credit on shard B, compensating reversal on failure) or two-phase commit; the double-entry ledger makes the saga natural because each leg is its own balanced pair against a per-shard "in-flight" system account. System accounts (cash-in/out) are replicated per shard. Reconciliation runs per shard plus a cross-shard in-flight-must-be-zero check. Not implemented.

## 7. What I would do next
* Real authentication and per-user authorisation (users may only read/spend their own wallets).
* Reserve funds for held transfers; expiry for stale holds.
* Cap/rate-limit the statement Q&A endpoint and log LLM calls (cost, latency).
* Split DB roles (migration owner vs. app role with no UPDATE/DELETE on the ledger).
* Fee transfers (`FEE` type) on withdrawals.
* Metrics (transfer latency, lock wait, held count) and alerting on `recon_runs.status <> 'OK'`.
* Retry-on-`40001` wrapper if any path moves to SERIALIZABLE.
* Outbox table for notifications/webhooks emitted in the same transaction as the transfer.
