package com.example.wallet.ledger;

import com.example.wallet.common.ApiException;
import com.example.wallet.common.Json;
import com.example.wallet.common.Stored;
import com.example.wallet.risk.RiskService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * THE MONEY PATH. Every public method that moves money must be called inside a transaction
 * (IdempotencyService opens it: READ COMMITTED + explicit row locks, see README "Isolation level").
 *
 *   1. SELECT ... FROM accounts WHERE id IN (a,b) ORDER BY id FOR UPDATE   (ascending id => no deadlocks)
 *   2. re-check the balance while holding the lock
 *   3. run fraud rules (held transfers stop here: no money moves)
 *   4. INSERT transfer, INSERT the D and C ledger entries, UPDATE both balances, INSERT audit row
 *   5. caller commits (deferred trigger verifies debits = credits at commit)
 */
@Service
public class TransferService {
    private record Acct(long id, String kind, long balance) {
        boolean userWallet() { return "USER_WALLET".equals(kind); }
    }
    private record Posting(String type, long from, long to, long amount, String note, Long reverses, String actor, boolean screen) {}
    private record NewTransfer(long id, OffsetDateTime createdAt) {}

    private final JdbcTemplate jdbc;
    private final RiskService risk;
    private final Map<String, Long> systemIds = new ConcurrentHashMap<>();

    public TransferService(JdbcTemplate jdbc, RiskService risk) { this.jdbc = jdbc; this.risk = risk; }

    // ---------------------------------------------------------------- lookups
    public long walletOwner(long walletId) {
        List<Long> r = jdbc.queryForList("SELECT user_id FROM accounts WHERE id = ? AND kind = 'USER_WALLET'", Long.class, walletId);
        if (r.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "Wallet " + walletId + " does not exist");
        return r.get(0);
    }

    private long systemAccount(String label) {
        return systemIds.computeIfAbsent(label, l ->
            jdbc.queryForObject("SELECT id FROM accounts WHERE kind = 'SYSTEM' AND label = ?", Long.class, l));
    }

    // ---------------------------------------------------------------- use cases
    public Stored topup(long walletId, long amount, String note) {
        long owner = walletOwner(walletId);
        return post(new Posting("TOPUP", systemAccount("CASH_IN"), walletId, amount, note, null, "user:" + owner, false));
    }

    public Stored peer(long fromWallet, long toWallet, long amount, String note) {
        long owner = walletOwner(fromWallet);
        walletOwner(toWallet);
        return post(new Posting("PEER", fromWallet, toWallet, amount, note, null, "user:" + owner, true));
    }

    public Stored withdraw(long walletId, long amount, String note) {
        long owner = walletOwner(walletId);
        return post(new Posting("WITHDRAWAL", walletId, systemAccount("CASH_OUT"), amount, note, null, "user:" + owner, true));
    }

    /** Reversal = a NEW transfer with swapped legs that points at the original. Entries are never edited. */
    public Stored reverse(long transferId, String actor) {
        Map<String, Object> o = lockTransfer(transferId);
        String status = (String) o.get("status");
        if (status.equals("REVERSED")) throw new ApiException(HttpStatus.CONFLICT, "ALREADY_REVERSED", "Transfer was already reversed");
        if (!status.equals("COMPLETED") || o.get("type").equals("REVERSAL"))
            throw new ApiException(HttpStatus.CONFLICT, "NOT_REVERSIBLE", "Only completed, non-reversal transfers can be reversed");
        Stored s = post(new Posting("REVERSAL", ((Number) o.get("to_account_id")).longValue(), ((Number) o.get("from_account_id")).longValue(),
            ((Number) o.get("amount")).longValue(), "reversal of #" + transferId, transferId, actor, false));
        setStatus(transferId, "COMPLETED", "REVERSED", actor, "reversed");
        return s;
    }

    /** Admin decision on a risk-queue item. */
    public Stored decide(long transferId, String decision, String actor, String reason) {
        Map<String, Object> t = lockTransfer(transferId);
        String status = (String) t.get("status");
        long from = ((Number) t.get("from_account_id")).longValue();
        long to = ((Number) t.get("to_account_id")).longValue();
        long amount = ((Number) t.get("amount")).longValue();

        if ("release".equals(decision)) {
            if (!status.equals("HELD")) throw new ApiException(HttpStatus.CONFLICT, "NOT_HELD", "Transfer is not held");
            Map<Long, Acct> locked = lockAccounts(from, to);
            requireFunds(locked.get(from), amount);               // balance may have changed while it was held
            applyEntries(transferId, from, to, amount);
            setStatus(transferId, "HELD", "COMPLETED", actor, reason == null ? "released by admin" : reason);
            return new Stored(200, Json.write(Map.of("transfer_id", transferId, "status", "COMPLETED")));
        }
        if ("reverse".equals(decision)) {
            if (status.equals("HELD")) {                           // no money ever moved: just reject it
                setStatus(transferId, "HELD", "REJECTED", actor, reason == null ? "rejected by admin" : reason);
                return new Stored(200, Json.write(Map.of("transfer_id", transferId, "status", "REJECTED")));
            }
            Boolean flagged = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM risk_flags WHERE transfer_id = ?)", Boolean.class, transferId);
            if (status.equals("COMPLETED") && Boolean.TRUE.equals(flagged)) {
                Stored s = reverse(transferId, actor);
                return new Stored(200, s.body());
            }
            throw new ApiException(HttpStatus.CONFLICT, "NOT_REVIEWABLE", "Transfer is neither held nor a flagged completed transfer");
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DECISION", "decision must be 'release' or 'reverse'");
    }

    // ---------------------------------------------------------------- the core
    private Stored post(Posting p) {
        if (p.amount() <= 0)
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT", "amount must be a positive integer number of paise");
        if (p.from() == p.to())
            throw new ApiException(HttpStatus.BAD_REQUEST, "SAME_ACCOUNT", "source and destination must differ");

        Map<Long, Acct> locked = lockAccounts(p.from(), p.to());                 // step 1
        Acct src = locked.get(p.from());
        if (src == null || locked.get(p.to()) == null)
            throw new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account not found");
        requireFunds(src, p.amount());                                           // step 2

        List<RiskService.Hit> hits = p.screen() ? risk.evaluate(p.type(), p.from(), p.to(), p.amount()) : List.of();  // step 3
        boolean hold = hits.stream().anyMatch(h -> h.action().equals("HOLD"));
        String status = hold ? "HELD" : "COMPLETED";

        NewTransfer t = jdbc.queryForObject(                                     // step 4
            "INSERT INTO transfers (type, status, amount, from_account_id, to_account_id, reverses_transfer_id, note) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id, created_at",
            (rs, i) -> new NewTransfer(rs.getLong(1), rs.getObject(2, OffsetDateTime.class)),
            p.type(), status, p.amount(), p.from(), p.to(), p.reverses(), p.note());
        history(t.id(), null, status, p.actor(), hold ? "held by risk rule" : "created");
        for (RiskService.Hit h : hits) risk.recordFlag(t.id(), h);
        if (!hold) applyEntries(t.id(), p.from(), p.to(), p.amount());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("transfer_id", t.id());
        m.put("type", p.type());
        m.put("status", status);
        m.put("amount", p.amount());
        m.put("from_account_id", p.from());
        m.put("to_account_id", p.to());
        m.put("created_at", t.createdAt().toString());
        if (!hits.isEmpty()) m.put("risk_flags", hits.stream().map(RiskService.Hit::ruleCode).toList());
        return new Stored(hold ? 202 : 201, Json.write(m));
    }

    /** Lock rows in ASCENDING id order: two opposite transfers (A->B and B->A) can never deadlock. */
    private Map<Long, Acct> lockAccounts(long a, long b) {
        List<Acct> rows = jdbc.query(
            "SELECT id, kind, balance FROM accounts WHERE id IN (?, ?) ORDER BY id FOR UPDATE",
            (rs, i) -> new Acct(rs.getLong("id"), rs.getString("kind"), rs.getLong("balance")), a, b);
        Map<Long, Acct> m = new LinkedHashMap<>();
        rows.forEach(r -> m.put(r.id(), r));
        return m;
    }

    private void requireFunds(Acct src, long amount) {
        if (src.userWallet() && src.balance() < amount)            // system accounts may go negative
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS", "Insufficient funds");
    }

    private void applyEntries(long transferId, long from, long to, long amount) {
        jdbc.update("INSERT INTO ledger_entries (transfer_id, account_id, direction, amount) VALUES (?, ?, 'D', ?), (?, ?, 'C', ?)",
            transferId, from, amount, transferId, to, amount);
        jdbc.update("UPDATE accounts SET balance = balance - ? WHERE id = ?", amount, from);
        jdbc.update("UPDATE accounts SET balance = balance + ? WHERE id = ?", amount, to);
    }

    private Map<String, Object> lockTransfer(long id) {
        List<Map<String, Object>> r = jdbc.queryForList(
            "SELECT id, type, status, amount, from_account_id, to_account_id FROM transfers WHERE id = ? FOR UPDATE", id);
        if (r.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", "Transfer " + id + " does not exist");
        return r.get(0);
    }

    private void setStatus(long id, String from, String to, String actor, String reason) {
        jdbc.update("UPDATE transfers SET status = ? WHERE id = ?", to, id);
        history(id, from, to, actor, reason);
    }

    private void history(long id, String from, String to, String actor, String reason) {
        jdbc.update("INSERT INTO transfer_status_history (transfer_id, from_status, to_status, changed_by, reason) VALUES (?, ?, ?, ?, ?)",
            id, from, to, actor, reason);
    }

    // ---------------------------------------------------------------- read side
    public Map<String, Object> get(long id) {
        List<Map<String, Object>> t = jdbc.queryForList(
            "SELECT id, type, status, amount, from_account_id, to_account_id, reverses_transfer_id, note, created_at FROM transfers WHERE id = ?", id);
        if (t.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", "Transfer " + id + " does not exist");
        Map<String, Object> m = new LinkedHashMap<>(t.get(0));
        m.put("entries", jdbc.queryForList("SELECT id, account_id, direction, amount, created_at FROM ledger_entries WHERE transfer_id = ? ORDER BY id", id));
        m.put("status_history", jdbc.queryForList("SELECT from_status, to_status, changed_by, reason, changed_at FROM transfer_status_history WHERE transfer_id = ? ORDER BY id", id));
        m.put("risk_flags", jdbc.queryForList("SELECT rule_code, action, llm_label, llm_explanation FROM risk_flags WHERE transfer_id = ? ORDER BY id", id));
        return m;
    }
}
