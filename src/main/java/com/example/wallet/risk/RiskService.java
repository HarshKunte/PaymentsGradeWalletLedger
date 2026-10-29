package com.example.wallet.risk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fraud rules. Every rule is one SQL query that runs INSIDE the transfer transaction, after the source
 * wallet row is locked, so concurrent transfers from the same wallet see each other's effects.
 * Thresholds come from the risk_rules table.
 */
@Service
public class RiskService {
    public record Hit(String ruleCode, String action) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper om;

    public RiskService(JdbcTemplate jdbc, ObjectMapper om) { this.jdbc = jdbc; this.om = om; }

    public List<Hit> evaluate(String type, long from, long to, long amount) {
        List<Hit> hits = new ArrayList<>();
        if (!type.equals("PEER") && !type.equals("WITHDRAWAL")) return hits;

        List<Map<String, Object>> rules = jdbc.queryForList(
            "SELECT code, action, params::text AS params FROM risk_rules WHERE enabled ORDER BY id");
        for (Map<String, Object> r : rules) {
            String code = (String) r.get("code");
            String action = (String) r.get("action");
            JsonNode p;
            try { p = om.readTree((String) r.get("params")); }
            catch (Exception e) { throw new IllegalStateException("bad params for rule " + code, e); }

            boolean fired = switch (code) {
                // index: transfers_from_time_idx (from_account_id, created_at DESC)
                case "VELOCITY" -> {
                    Long prior = jdbc.queryForObject(
                        "SELECT count(*) FROM transfers WHERE from_account_id = ? AND type IN ('PEER','WITHDRAWAL') " +
                        "AND status <> 'REJECTED' AND created_at > now() - make_interval(mins => ?)",
                        Long.class, from, p.get("window_minutes").asInt());
                    yield prior != null && prior >= p.get("max_count").asInt();   // this one would be the (max+1)th
                }
                // index: transfers_from_time_idx INCLUDE (amount)  -> index-only scan over the lookback window
                case "AMOUNT_SPIKE" -> {
                    Double avg = jdbc.queryForObject(
                        "SELECT avg(amount)::float8 FROM transfers WHERE from_account_id = ? AND type IN ('PEER','WITHDRAWAL') " +
                        "AND status IN ('COMPLETED','REVERSED') AND created_at > now() - make_interval(days => ?)",
                        Double.class, from, p.get("lookback_days").asInt());
                    yield avg != null && amount > p.get("multiplier").asDouble() * avg;
                }
                // index: transfers_from_to_idx (from_account_id, to_account_id)
                case "NEW_PAYEE_LARGE" -> {
                    if (!type.equals("PEER") || amount <= p.get("min_amount_paise").asLong()) yield false;
                    Boolean seen = jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM transfers WHERE from_account_id = ? AND to_account_id = ? " +
                        "AND status IN ('COMPLETED','REVERSED'))", Boolean.class, from, to);
                    yield Boolean.FALSE.equals(seen);
                }
                default -> false;
            };
            if (fired) hits.add(new Hit(code, action));
        }
        return hits;
    }

    public void recordFlag(long transferId, Hit h) {
        jdbc.update("INSERT INTO risk_flags (transfer_id, rule_code, action) VALUES (?, ?, ?)", transferId, h.ruleCode(), h.action());
    }
}
