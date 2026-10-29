package com.example.wallet.ledger;

import com.example.wallet.common.ApiException;
import com.example.wallet.common.Json;
import com.example.wallet.common.Stored;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AccountService {
    private static final Set<String> KYC = Set.of("PENDING", "VERIFIED", "REJECTED");
    private final JdbcTemplate jdbc;

    public AccountService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Stored createUser(String name, String email, String phone, String kyc) {
        if (name == null || name.isBlank() || email == null || !email.contains("@") || phone == null || phone.isBlank())
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "name, a valid email and phone are required");
        String k = kyc == null ? "PENDING" : kyc;
        if (!KYC.contains(k)) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "kyc_status must be one of " + KYC);
        try {
            Map<String, Object> row = jdbc.queryForMap(
                "INSERT INTO users (name, email, phone, kyc_status) VALUES (?, ?, ?, ?) RETURNING id, created_at", name.trim(), email.trim(), phone.trim(), k);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("user_id", row.get("id"));
            m.put("name", name.trim());
            m.put("email", email.trim());
            m.put("phone", phone.trim());
            m.put("kyc_status", k);
            m.put("created_at", String.valueOf(row.get("created_at")));
            return new Stored(201, Json.write(m));
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "USER_EXISTS", "A user with this email or phone already exists");
        }
    }

    public Stored createWallet(long userId, String label) {
        if (!jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM users WHERE id = ?)", Boolean.class, userId))
            throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User " + userId + " does not exist");
        String l = (label == null || label.isBlank()) ? "main" : label.trim();
        try {
            Long id = jdbc.queryForObject("INSERT INTO accounts (user_id, kind, label) VALUES (?, 'USER_WALLET', ?) RETURNING id", Long.class, userId, l);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("wallet_id", id);
            m.put("user_id", userId);
            m.put("label", l);
            m.put("currency", "INR");
            m.put("balance", 0);
            return new Stored(201, Json.write(m));
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "WALLET_EXISTS", "User already has a wallet labelled '" + l + "'");
        }
    }

    public Map<String, Object> balance(long walletId) {
        List<Map<String, Object>> r = jdbc.queryForList(
            "SELECT id AS wallet_id, balance, currency FROM accounts WHERE id = ? AND kind = 'USER_WALLET'", walletId);
        if (r.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "Wallet " + walletId + " does not exist");
        return r.get(0);
    }

    private static final String STATEMENT_SQL =
        "SELECT e.id, e.transfer_id, e.direction, e.amount, e.created_at, t.type, t.status, t.note " +
        "FROM ledger_entries e JOIN transfers t ON t.id = e.transfer_id WHERE e.account_id = ? ";

    /** OFFSET pagination (simple, slow when deep) or keyset pagination via `cursor` (constant cost). Newest first. */
    public Map<String, Object> statement(long walletId, int limit, int offset, String cursor) {
        balance(walletId);                                                     // 404 if no such wallet
        int lim = Math.min(Math.max(limit, 1), 200);
        RowMapper<Map<String, Object>> rm = (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("entry_id", rs.getLong("id"));
            m.put("transfer_id", rs.getLong("transfer_id"));
            m.put("direction", rs.getString("direction"));
            m.put("amount", rs.getLong("amount"));
            m.put("created_at", rs.getObject("created_at", OffsetDateTime.class).toString());
            m.put("transfer_type", rs.getString("type"));
            m.put("transfer_status", rs.getString("status"));
            m.put("note", rs.getString("note"));
            return m;
        };
        List<Map<String, Object>> rows;
        if (cursor != null && !cursor.isBlank()) {
            String[] c = decode(cursor);
            rows = jdbc.query(STATEMENT_SQL + "AND (e.created_at, e.id) < (?, ?) ORDER BY e.created_at DESC, e.id DESC LIMIT ?",
                rm, walletId, OffsetDateTime.parse(c[0]), Long.parseLong(c[1]), lim);
        } else {
            rows = jdbc.query(STATEMENT_SQL + "ORDER BY e.created_at DESC, e.id DESC LIMIT ? OFFSET ?", rm, walletId, lim, Math.max(offset, 0));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("wallet_id", walletId);
        out.put("entries", rows);
        if (rows.size() == lim) {
            Map<String, Object> last = rows.get(rows.size() - 1);
            out.put("next_cursor", Base64.getUrlEncoder().withoutPadding()
                .encodeToString((last.get("created_at") + "|" + last.get("entry_id")).getBytes(StandardCharsets.UTF_8)));
        }
        return out;
    }

    private static String[] decode(String cursor) {
        try {
            String[] p = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|");
            OffsetDateTime.parse(p[0]);
            Long.parseLong(p[1]);
            return p;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "cursor is not valid");
        }
    }
}
