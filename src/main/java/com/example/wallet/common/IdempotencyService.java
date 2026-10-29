package com.example.wallet.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Idempotent POSTs. ONE transaction wraps: insert key -> run the work -> save response on the key -> commit.
 *
 *  new key                    -> INSERT wins, work runs, response saved, commit           (original status)
 *  seen, same body, completed -> INSERT does nothing, saved response returned exactly     (original status)
 *  seen, same body, running   -> INSERT waits on the unique index; if the first request is still
 *                                running after 2s we give up with 409 (retry shortly)
 *  seen, different body       -> 422, nothing runs, no money moves
 *
 * If the work throws (e.g. INSUFFICIENT_FUNDS) everything rolls back including the key, so the client may retry.
 */
@Service
public class IdempotencyService {
    private record Outcome(Stored stored, boolean replay) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper om;

    public IdempotencyService(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper om) {
        this.jdbc = jdbc; this.tx = tx; this.om = om;
    }

    public ResponseEntity<String> execute(long scopeUserId, String keyHeader, String route, Object body, Supplier<Stored> work) {
        UUID key = parseKey(keyHeader);
        String hash = sha256(route + "\n" + canonical(body));

        Outcome out = tx.execute(status -> {                       // <-- BEGIN (READ COMMITTED)
            jdbc.queryForObject("SELECT set_config('lock_timeout','2s',true)", String.class);
            List<Long> inserted;
            try {
                inserted = jdbc.queryForList(
                    "INSERT INTO idempotency_keys (user_id, idempotency_key, request_hash) VALUES (?, ?, ?) " +
                    "ON CONFLICT (user_id, idempotency_key) DO NOTHING RETURNING id",
                    Long.class, scopeUserId, key, hash);
            } catch (DataAccessException e) {
                if (Pg.isLockNotAvailable(e))
                    throw new ApiException(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS",
                        "A request with this Idempotency-Key is still running; retry shortly");
                throw e;
            }
            // the short lock_timeout was only for the key insert; row locks on wallets may legitimately wait longer
            jdbc.queryForObject("SELECT set_config('lock_timeout','0',true)", String.class);

            if (inserted.isEmpty()) {                               // seen before
                Map<String, Object> row = jdbc.queryForMap(
                    "SELECT request_hash, status, response_code, response_body FROM idempotency_keys " +
                    "WHERE user_id = ? AND idempotency_key = ?", scopeUserId, key);
                if (!hash.equals(String.valueOf(row.get("request_hash")).trim()))
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED",
                        "This Idempotency-Key was already used with a different request body");
                if (!"COMPLETED".equals(row.get("status")))
                    throw new ApiException(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS",
                        "A request with this Idempotency-Key is still running; retry shortly");
                return new Outcome(new Stored(((Number) row.get("response_code")).intValue(), (String) row.get("response_body")), true);
            }

            Stored s = work.get();                                  // the money path
            jdbc.update("UPDATE idempotency_keys SET status = 'COMPLETED', response_code = ?, response_body = ? WHERE id = ?",
                s.status(), s.body(), inserted.get(0));
            return new Outcome(s, false);
        });                                                         // <-- COMMIT

        ResponseEntity.BodyBuilder b = ResponseEntity.status(out.stored().status()).contentType(MediaType.APPLICATION_JSON);
        if (out.replay()) b.header("Idempotent-Replayed", "true");
        return b.body(out.stored().body());
    }

    private static UUID parseKey(String h) {
        if (h == null || h.isBlank())
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key header (UUID) is required");
        try { return UUID.fromString(h.trim()); }
        catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_INVALID", "Idempotency-Key must be a UUID");
        }
    }

    private String canonical(Object body) {
        try { return om.writeValueAsString(body); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    private static String sha256(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
