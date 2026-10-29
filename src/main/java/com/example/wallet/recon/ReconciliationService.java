package com.example.wallet.recon;

import com.example.wallet.common.ApiException;
import com.example.wallet.common.Pg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Nightly books check. Reports problems, NEVER fixes them.
 * All checks run in ONE REPEATABLE READ transaction (one snapshot), so concurrent transfers cannot make
 * the checks disagree with each other. Only one run at a time: FOR UPDATE NOWAIT on the job_locks row.
 */
@Service
public class ReconciliationService {
    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    private static final String INS = "INSERT INTO recon_issues (run_id, check_name, ref_id, expected, actual) ";

    /** name -> "INSERT ... SELECT" with a single ? = run id. Each is a plain, readable SQL check. */
    private static final Map<String, String> CHECKS = new LinkedHashMap<>();
    static {
        // 1. everything balances: total debits == total credits (ref 0; expected = debits, actual = credits)
        CHECKS.put("GLOBAL_BALANCE", INS +
            "SELECT ?, 'GLOBAL_BALANCE', 0, d::bigint, c::bigint FROM (" +
            " SELECT COALESCE(SUM(amount) FILTER (WHERE direction = 'D'), 0) AS d," +
            "        COALESCE(SUM(amount) FILTER (WHERE direction = 'C'), 0) AS c FROM ledger_entries) s WHERE d <> c");
        // 2. every transfer balances (GROUP BY ... HAVING)
        CHECKS.put("TRANSFER_BALANCE", INS +
            "SELECT ?, 'TRANSFER_BALANCE', transfer_id, d::bigint, c::bigint FROM (" +
            " SELECT transfer_id," +
            "        COALESCE(SUM(amount) FILTER (WHERE direction = 'D'), 0) AS d," +
            "        COALESCE(SUM(amount) FILTER (WHERE direction = 'C'), 0) AS c" +
            " FROM ledger_entries GROUP BY transfer_id" +
            " HAVING COALESCE(SUM(amount) FILTER (WHERE direction = 'D'), 0) <> COALESCE(SUM(amount) FILTER (WHERE direction = 'C'), 0)) x");
        // 3. stored balance == balance computed from entries (expected = computed, actual = stored)
        CHECKS.put("STORED_BALANCE", INS +
            "SELECT ?, 'STORED_BALANCE', a.id, COALESCE(b.bal, 0)::bigint, a.balance " +
            "FROM accounts a LEFT JOIN recon_balances b ON b.account_id = a.id WHERE a.balance <> COALESCE(b.bal, 0)");
        // 4. no user wallet is below zero (computed)
        CHECKS.put("OVERDRAFT", INS +
            "SELECT ?, 'OVERDRAFT', a.id, 0, b.bal::bigint " +
            "FROM accounts a JOIN recon_balances b ON b.account_id = a.id WHERE a.kind = 'USER_WALLET' AND b.bal < 0");
        // 5. nothing orphaned: settled transfers without entries, entries without a transfer
        CHECKS.put("ORPHAN_TRANSFER", INS +
            "SELECT ?, 'ORPHAN_TRANSFER', t.id, 2, 0 FROM transfers t " +
            "WHERE t.status IN ('COMPLETED','REVERSED') AND NOT EXISTS (SELECT 1 FROM ledger_entries e WHERE e.transfer_id = t.id)");
        CHECKS.put("ORPHAN_ENTRY", INS +
            "SELECT ?, 'ORPHAN_ENTRY', e.id, 1, 0 FROM ledger_entries e LEFT JOIN transfers t ON t.id = e.transfer_id WHERE t.id IS NULL");
    }

    private static class AlreadyRunning extends RuntimeException {}

    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager txm;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    public ReconciliationService(JdbcTemplate jdbc, PlatformTransactionManager txm) { this.jdbc = jdbc; this.txm = txm; }

    public long createRun() {
        return jdbc.queryForObject("INSERT INTO recon_runs (status) VALUES ('RUNNING') RETURNING id", Long.class);
    }

    /** POST /admin/reconciliation/run -> 202 with the run id; the run continues in the background. */
    public long startAsync() {
        long id = createRun();
        pool.submit(() -> execute(id));
        return id;
    }

    /** Synchronous run (scheduler and tests). */
    public long runNow() {
        long id = createRun();
        execute(id);
        return id;
    }

    public void execute(long runId) {
        TransactionTemplate rr = new TransactionTemplate(txm);
        rr.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try {
            rr.executeWithoutResult(s -> {                                         // BEGIN ISOLATION LEVEL REPEATABLE READ
                try {                                                              // first statement = the lock (and the snapshot)
                    jdbc.queryForObject("SELECT job_name FROM job_locks WHERE job_name = 'reconciliation' FOR UPDATE NOWAIT", String.class);
                } catch (RuntimeException e) {
                    if (Pg.isLockNotAvailable(e)) throw new AlreadyRunning();
                    throw e;
                }
                jdbc.execute("CREATE TEMP TABLE recon_balances ON COMMIT DROP AS " +
                    "SELECT account_id, SUM(CASE direction WHEN 'C' THEN amount ELSE -amount END) AS bal FROM ledger_entries GROUP BY account_id");
                int issues = 0;
                for (Map.Entry<String, String> c : CHECKS.entrySet()) {
                    int n = jdbc.update(c.getValue(), runId);
                    log.info("recon run {} check {}: {} issue(s)", runId, c.getKey(), n);
                    issues += n;
                }
                jdbc.update("UPDATE recon_runs SET finished_at = clock_timestamp(), status = ?, checks_run = ?, issue_count = ? WHERE id = ?",
                    issues == 0 ? "OK" : "ISSUES_FOUND", CHECKS.size(), issues, runId);
            });                                                                    // COMMIT
        } catch (AlreadyRunning e) {
            jdbc.update("UPDATE recon_runs SET finished_at = clock_timestamp(), status = 'SKIPPED', note = 'another run holds the lock' WHERE id = ?", runId);
        } catch (RuntimeException e) {
            log.error("reconciliation run {} failed", runId, e);
            jdbc.update("UPDATE recon_runs SET finished_at = clock_timestamp(), status = 'FAILED', note = ? WHERE id = ?",
                String.valueOf(e.getMessage()), runId);
        }
    }

    public Map<String, Object> report(long runId) {
        List<Map<String, Object>> r = jdbc.queryForList(
            "SELECT id, started_at, finished_at, status, checks_run, issue_count, note FROM recon_runs WHERE id = ?", runId);
        if (r.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND", "Run " + runId + " does not exist");
        Map<String, Object> m = new LinkedHashMap<>(r.get(0));
        m.put("issues", jdbc.queryForList(
            "SELECT check_name, ref_id, expected, actual FROM recon_issues WHERE run_id = ? ORDER BY id LIMIT 1000", runId));
        return m;
    }
}
