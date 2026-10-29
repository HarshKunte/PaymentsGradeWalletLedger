package com.example.wallet;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Milestone 2 / Step 2 - "Break it first". NO locks, NO transaction: read the balance, check it in code,
 * then write. Run it and paste the printed numbers into the README. It asserts nothing on purpose:
 * it demonstrates the bug the real TransferService fixes.
 */
class NaiveRaceDemoTest extends IntegrationTestBase {

    @Test
    void naiveTransferOverspends() throws Exception {
        long wa = api.createWallet(api.createUser("Asha")), wb = api.createWallet(api.createUser("Rahul"));
        api.topup(wa, 50_000);
        AtomicInteger ok = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(25);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 100; i++) fs.add(pool.submit(() -> {
            Long bal = jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, wa);   // read
            if (bal >= 1_000) {                                                                               // check in code
                try { Thread.sleep(5); } catch (InterruptedException ignored) {}
                jdbc.update("UPDATE accounts SET balance = ? WHERE id = ?", bal - 1_000, wa);                // write absolute value: lost update
                jdbc.update("UPDATE accounts SET balance = balance + 1000 WHERE id = ?", wb);
                long t = jdbc.queryForObject("INSERT INTO transfers (type,status,amount,from_account_id,to_account_id) VALUES ('PEER','COMPLETED',1000,?,?) RETURNING id", Long.class, wa, wb);
                jdbc.update("INSERT INTO ledger_entries (transfer_id,account_id,direction,amount) VALUES (?,?,'D',1000),(?,?,'C',1000)", t, wa, t, wb);
                ok.incrementAndGet();
            }
        }));
        for (Future<?> f : fs) f.get();
        pool.shutdown();

        long stored = api.balance(wa);
        long fromLedger = sumEntries("C", "account_id = ?", wa) - sumEntries("D", "account_id = ?", wa);
        System.out.println("=== NAIVE TRANSFER (no locks) ===");
        System.out.println("successful transfers : " + ok.get() + "   (correct answer: 50)");
        System.out.println("stored balance       : " + stored + "   (correct answer: 0)");
        System.out.println("balance per ledger   : " + fromLedger + "   (money spent beyond what existed if negative)");
    }
}
