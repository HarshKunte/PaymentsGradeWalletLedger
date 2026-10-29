package com.example.wallet;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rs 500 in one wallet, 100 concurrent Rs 10 transfers out of it. Repeated 20 times:
 * a race that fails once in 20 runs is still a bug.
 * (The VELOCITY fraud rule is switched off here on purpose: it would - correctly - hold transfers after the 5th.
 *  This test targets the money path; RiskFlowTest covers the rule.)
 */
class StampedeTest extends IntegrationTestBase {

    @Test
    void hundredConcurrentTransfers_twentyTimes() throws Exception {
        jdbc.update("UPDATE risk_rules SET enabled = false WHERE code = 'VELOCITY'");
        for (int run = 1; run <= 20; run++) oneRun(run);
    }

    private void oneRun(int run) throws Exception {
        long wa = api.createWallet(api.createUser("Asha"));
        long wb = api.createWallet(api.createUser("Rahul"));
        api.topup(wa, 50_000);                                   // Rs 500

        ExecutorService pool = Executors.newFixedThreadPool(100);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return api.post("/transfers", Map.of("from_wallet_id", wa, "to_wallet_id", wb, "amount", 1_000), api.newKey())
                          .getStatusCode().value();
            }));
        }
        go.countDown();
        int ok = 0, insufficient = 0;
        for (Future<Integer> f : results) {
            int s = f.get(60, TimeUnit.SECONDS);
            if (s == 201) ok++; else if (s == 422) insufficient++;
        }
        pool.shutdown();

        String msg = "run " + run;
        assertEquals(50, ok, msg + ": exactly 50 transfers must succeed");
        assertEquals(50, insufficient, msg + ": the other 50 must be rejected with 422");
        assertEquals(0, api.balance(wa), msg + ": sender ends at Rs 0");
        assertEquals(50_000, api.balance(wb), msg + ": receiver got exactly Rs 500");
        assertTrue(jdbc.queryForObject("SELECT balance >= 0 FROM accounts WHERE id = ?", Boolean.class, wa));

        String where = "transfer_id IN (SELECT id FROM transfers WHERE from_account_id = ? OR to_account_id = ?)";
        assertEquals(sumEntries("D", where, wa, wa), sumEntries("C", where, wa, wa), msg + ": debits == credits");
    }
}
