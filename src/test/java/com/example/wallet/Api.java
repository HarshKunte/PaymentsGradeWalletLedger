package com.example.wallet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Tiny HTTP helper for tests. */
public class Api {
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    public static final String ADMIN = "test-admin";
    private final TestRestTemplate rest;
    private final ObjectMapper om;

    public Api(TestRestTemplate rest, ObjectMapper om) { this.rest = rest; this.om = om; }

    public ResponseEntity<String> post(String path, Object body, String key, String... headers) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (key != null) h.set("Idempotency-Key", key);
        for (int i = 0; i + 1 < headers.length; i += 2) h.set(headers[i], headers[i + 1]);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, h), String.class);
    }

    public ResponseEntity<String> get(String path, String... headers) {
        HttpHeaders h = new HttpHeaders();
        for (int i = 0; i + 1 < headers.length; i += 2) h.set(headers[i], headers[i + 1]);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(h), String.class);
    }

    public JsonNode json(ResponseEntity<String> r) {
        try { return om.readTree(r.getBody()); } catch (Exception e) { throw new RuntimeException(r.getBody(), e); }
    }

    public String newKey() { return UUID.randomUUID().toString(); }

    public long createUser(String name) {
        long n = SEQ.incrementAndGet();
        var r = post("/users", Map.of("name", name, "email", name.toLowerCase().replace(' ', '.') + n + "@example.com", "phone", "t" + n), newKey());
        if (r.getStatusCode().value() != 201) throw new IllegalStateException(r.getBody());
        return json(r).get("user_id").asLong();
    }

    public long createWallet(long userId) {
        var r = post("/users/" + userId + "/wallets", Map.of("label", "main"), newKey());
        if (r.getStatusCode().value() != 201) throw new IllegalStateException(r.getBody());
        return json(r).get("wallet_id").asLong();
    }

    public void topup(long wallet, long amount) {
        var r = post("/transfers/topup", Map.of("wallet_id", wallet, "amount", amount), newKey());
        if (r.getStatusCode().value() != 201) throw new IllegalStateException(r.getBody());
    }

    public long balance(long wallet) { return json(get("/wallets/" + wallet + "/balance")).get("balance").asLong(); }
}
