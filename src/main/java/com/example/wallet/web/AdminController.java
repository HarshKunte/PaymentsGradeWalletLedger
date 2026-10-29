package com.example.wallet.web;

import com.example.wallet.common.AdminAuth;
import com.example.wallet.common.ApiException;
import com.example.wallet.common.IdempotencyService;
import com.example.wallet.ledger.TransferService;
import com.example.wallet.qa.StatementQaService;
import com.example.wallet.recon.ReconciliationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class AdminController {
    public record DecisionRequest(String decision, String reason) {}
    public record AskRequest(String question) {}

    private final AdminAuth auth;
    private final ReconciliationService recon;
    private final JdbcTemplate jdbc;
    private final ObjectMapper om;
    private final IdempotencyService idem;
    private final TransferService transfers;
    private final StatementQaService qa;

    public AdminController(AdminAuth auth, ReconciliationService recon, JdbcTemplate jdbc, ObjectMapper om,
                           IdempotencyService idem, TransferService transfers, StatementQaService qa) {
        this.auth = auth; this.recon = recon; this.jdbc = jdbc; this.om = om; this.idem = idem; this.transfers = transfers; this.qa = qa;
    }

    @PostMapping("/admin/reconciliation/run")
    public ResponseEntity<Map<String, Object>> runRecon(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        auth.require(token);
        return ResponseEntity.accepted().body(Map.of("run_id", recon.startAsync(), "status", "RUNNING"));
    }

    @GetMapping("/admin/reconciliation/runs/{id}")
    public Map<String, Object> report(@RequestHeader(value = "X-Admin-Token", required = false) String token, @PathVariable long id) {
        auth.require(token);
        return recon.report(id);
    }

    @GetMapping("/admin/risk/queue")
    public List<Map<String, Object>> queue(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        auth.require(token);
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT t.id AS transfer_id, t.type, t.status, t.amount, t.from_account_id, t.to_account_id, t.created_at, " +
            " (SELECT json_agg(json_build_object('rule', f.rule_code, 'action', f.action, 'llm_label', f.llm_label, 'llm_explanation', f.llm_explanation) ORDER BY f.id)::text " +
            "  FROM risk_flags f WHERE f.transfer_id = t.id) AS flags " +
            "FROM transfers t WHERE t.status = 'HELD' " +
            "   OR (t.status = 'COMPLETED' AND EXISTS (SELECT 1 FROM risk_flags f WHERE f.transfer_id = t.id)) " +
            "ORDER BY t.created_at DESC, t.id DESC LIMIT 200");
        for (Map<String, Object> r : rows) {
            try { Object f = r.get("flags"); r.put("flags", f == null ? List.of() : om.readTree((String) f)); }
            catch (Exception e) { throw new IllegalStateException(e); }
        }
        return rows;
    }

    @PostMapping("/admin/risk/{transferId}/decision")
    public ResponseEntity<String> decision(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                           @RequestHeader(value = "X-Admin-Token", required = false) String token,
                                           @RequestHeader(value = "X-Admin-Name", defaultValue = "admin") String admin,
                                           @PathVariable long transferId, @RequestBody DecisionRequest r) {
        auth.require(token);
        if (r.decision() == null) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "decision is required");
        return idem.execute(0, key, "POST /admin/risk/" + transferId + "/decision", r,
            () -> transfers.decide(transferId, r.decision(), "admin:" + admin, r.reason()));
    }

    /** Read-only (it moves no money), so it does not take an Idempotency-Key. */
    @PostMapping("/users/{id}/statement/ask")
    public Map<String, Object> ask(@PathVariable long id, @RequestBody AskRequest r) {
        return qa.ask(id, r.question());
    }
}
