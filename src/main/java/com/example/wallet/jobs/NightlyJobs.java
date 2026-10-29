package com.example.wallet.jobs;

import com.example.wallet.recon.ReconciliationService;
import com.example.wallet.risk.RiskExplainService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Active only in the worker container (WALLET_SCHEDULER_ENABLED=true). */
@Component
@ConditionalOnProperty(name = "wallet.scheduler.enabled", havingValue = "true")
public class NightlyJobs {
    private static final Logger log = LoggerFactory.getLogger(NightlyJobs.class);
    private final ReconciliationService recon;
    private final RiskExplainService explain;
    private final JdbcTemplate jdbc;

    public NightlyJobs(ReconciliationService recon, RiskExplainService explain, JdbcTemplate jdbc) {
        this.recon = recon; this.explain = explain; this.jdbc = jdbc;
    }

    @Scheduled(cron = "${wallet.scheduler.cron}", zone = "${wallet.zone}")
    public void nightly() {
        long run = recon.runNow();
        log.info("nightly reconciliation finished: run {}", run);
        log.info("explained {} risk flag(s)", explain.explainRecentFlags());
        log.info("deleted {} idempotency key(s) older than 24h",
            jdbc.update("DELETE FROM idempotency_keys WHERE created_at < now() - interval '24 hours'"));
    }
}
