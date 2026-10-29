package com.example.wallet;

import com.example.wallet.recon.ReconciliationService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class ReconciliationTest extends IntegrationTestBase {
    @Autowired ReconciliationService recon;
    @Autowired Flyway flyway;
    @Autowired TransactionTemplate tx;

    // start and end on an empty database: this test deliberately corrupts the ledger
    @BeforeEach void resetBefore() { flyway.clean(); flyway.migrate(); }
    @AfterEach void resetAfter() { flyway.clean(); flyway.migrate(); }

    @Test
    void seed10k_corruptThreeThings_reportsExactlyThoseProblems() {
        jdbc.execute("SELECT seed_demo_data(100, 1, 10000)");
        assertEquals(10_000, jdbc.queryForObject("SELECT count(*) FROM transfers WHERE type = 'PEER'", Long.class));

        long clean = recon.runNow();
        assertEquals("OK", jdbc.queryForObject("SELECT status FROM recon_runs WHERE id = ?", String.class, clean));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM recon_issues WHERE run_id = ?", Long.class, clean));

        long t1 = jdbc.queryForObject("SELECT min(id) FROM transfers WHERE type = 'PEER'", Long.class);
        long t2 = jdbc.queryForObject("SELECT max(id) FROM transfers WHERE type = 'PEER'", Long.class);
        long e1 = jdbc.queryForObject("SELECT id FROM ledger_entries WHERE transfer_id = ? AND direction = 'D'", Long.class, t1);
        long a1 = jdbc.queryForObject("SELECT account_id FROM ledger_entries WHERE id = ?", Long.class, e1);
        long e2 = jdbc.queryForObject("SELECT id FROM ledger_entries WHERE transfer_id = ? AND direction = 'C'", Long.class, t2);
        long a2 = jdbc.queryForObject("SELECT account_id FROM ledger_entries WHERE id = ?", Long.class, e2);
        long w = jdbc.queryForObject("SELECT id FROM accounts WHERE kind = 'USER_WALLET' AND id NOT IN (?, ?) ORDER BY id LIMIT 1", Long.class, a1, a2);

        // The ledger is immutable, so we have to disable the trigger to simulate corruption
        jdbc.execute("ALTER TABLE ledger_entries DISABLE TRIGGER trg_ledger_immutable");
        jdbc.update("UPDATE ledger_entries SET amount = amount + 1 WHERE id = ?", e1);   // 1. changed amount
        jdbc.update("DELETE FROM ledger_entries WHERE id = ?", e2);                       // 2. deleted credit
        jdbc.execute("ALTER TABLE ledger_entries ENABLE TRIGGER trg_ledger_immutable");
        jdbc.update("UPDATE accounts SET balance = balance + 123 WHERE id = ?", w);      // 3. wrong stored balance

        long run = recon.runNow();
        assertEquals("ISSUES_FOUND", jdbc.queryForObject("SELECT status FROM recon_runs WHERE id = ?", String.class, run));
        List<String> actual = jdbc.queryForList("SELECT check_name || ':' || ref_id FROM recon_issues WHERE run_id = ?", String.class, run);

        // One corruption can trip several independent checks (e.g. a deleted credit unbalances its transfer,
        // the global total AND the credited wallet's stored balance). Every reported issue must trace back to
        // exactly the three things we broke, and nothing else may appear.
        Set<String> expected = new TreeSet<>(List.of(
            "TRANSFER_BALANCE:" + t1, "TRANSFER_BALANCE:" + t2, "GLOBAL_BALANCE:0",
            "STORED_BALANCE:" + a1, "STORED_BALANCE:" + a2, "STORED_BALANCE:" + w));
        assertEquals(actual.size(), new HashSet<>(actual).size(), "no duplicate issues");
        assertEquals(expected, new TreeSet<>(actual));
    }

    @Test
    void secondConcurrentRunIsSkipped() throws Exception {
        tx.executeWithoutResult(s -> {
            jdbc.queryForObject("SELECT job_name FROM job_locks WHERE job_name = 'reconciliation' FOR UPDATE", String.class);
            // another thread = another connection = another transaction
            long id = CompletableFuture.supplyAsync(() -> recon.runNow()).join();
            assertEquals("SKIPPED", jdbc.queryForObject("SELECT status FROM recon_runs WHERE id = ?", String.class, id));
        });
    }
}
