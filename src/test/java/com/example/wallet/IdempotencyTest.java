package com.example.wallet;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class IdempotencyTest extends IntegrationTestBase {

    private long transfersFrom(long wallet) {
        return jdbc.queryForObject("SELECT count(*) FROM transfers WHERE type = 'PEER' AND from_account_id = ?", Long.class, wallet);
    }

    @Test
    void sameRequestFiveTimes_oneTransfer_fiveIdenticalResponses() {
        long wa = api.createWallet(api.createUser("Asha")), wb = api.createWallet(api.createUser("Rahul"));
        api.topup(wa, 100_000);
        String key = api.newKey();
        Map<String, Object> body = Map.of("from_wallet_id", wa, "to_wallet_id", wb, "amount", 5_000);

        Set<String> bodies = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            ResponseEntity<String> r = api.post("/transfers", body, key);
            assertEquals(201, r.getStatusCode().value());
            bodies.add(r.getBody());
        }
        assertEquals(1, bodies.size(), "all five responses must be byte-identical");
        assertEquals(1, transfersFrom(wa));
        assertEquals(95_000, api.balance(wa));
    }

    @Test
    void sameKeyTenTimesInParallel_exactlyOneTransfer() throws Exception {
        long wa = api.createWallet(api.createUser("Asha")), wb = api.createWallet(api.createUser("Rahul"));
        api.topup(wa, 100_000);
        String key = api.newKey();
        Map<String, Object> body = Map.of("from_wallet_id", wa, "to_wallet_id", wb, "amount", 5_000);

        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<ResponseEntity<String>>> fs = new ArrayList<>();
        for (int i = 0; i < 10; i++) fs.add(pool.submit(() -> { go.await(); return api.post("/transfers", body, key); }));
        go.countDown();
        Set<String> created = new HashSet<>();
        for (var f : fs) {
            ResponseEntity<String> r = f.get(30, TimeUnit.SECONDS);
            assertTrue(r.getStatusCode().value() == 201 || r.getStatusCode().value() == 409, "got " + r.getStatusCode());
            if (r.getStatusCode().value() == 201) created.add(r.getBody());
        }
        pool.shutdown();
        assertEquals(1, created.size(), "every 201 carries the same original transfer");
        assertEquals(1, transfersFrom(wa));
        assertEquals(95_000, api.balance(wa));
    }

    @Test
    void sameKeyDifferentAmount_422_andNoMoneyMoves() {
        long wa = api.createWallet(api.createUser("Asha")), wb = api.createWallet(api.createUser("Rahul"));
        api.topup(wa, 100_000);
        String key = api.newKey();
        assertEquals(201, api.post("/transfers", Map.of("from_wallet_id", wa, "to_wallet_id", wb, "amount", 5_000), key).getStatusCode().value());

        ResponseEntity<String> r = api.post("/transfers", Map.of("from_wallet_id", wa, "to_wallet_id", wb, "amount", 6_000), key);
        assertEquals(422, r.getStatusCode().value());
        assertEquals("IDEMPOTENCY_KEY_REUSED", api.json(r).get("code").asText());
        assertEquals(1, transfersFrom(wa));
        assertEquals(95_000, api.balance(wa));
    }

    @Test
    void missingKey_400() {
        ResponseEntity<String> r = api.post("/users", Map.of("name", "X", "email", "x@example.com", "phone", "1"), null);
        assertEquals(400, r.getStatusCode().value());
        assertEquals("IDEMPOTENCY_KEY_REQUIRED", api.json(r).get("code").asText());
        assertNotNull(api.json(r).get("request_id"));
    }
}
