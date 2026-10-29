package com.example.wallet;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RiskFlowTest extends IntegrationTestBase {

    @Test
    void velocityHold_adminRelease_thenReverse() {
        long wa = api.createWallet(api.createUser("Asha")), wb = api.createWallet(api.createUser("Rahul"));
        api.topup(wa, 1_000_000);

        long first = 0;
        for (int i = 0; i < 5; i++) {
            ResponseEntity<String> r = api.post("/transfers", Map.of("from_wallet_id", wa, "to_wallet_id", wb, "amount", 100), api.newKey());
            assertEquals(201, r.getStatusCode().value());
            if (i == 0) first = api.json(r).get("transfer_id").asLong();
        }
        ResponseEntity<String> held = api.post("/transfers", Map.of("from_wallet_id", wa, "to_wallet_id", wb, "amount", 100), api.newKey());
        assertEquals(202, held.getStatusCode().value());
        long heldId = api.json(held).get("transfer_id").asLong();
        assertEquals(999_500, api.balance(wa), "held transfer moves no money");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE transfer_id = ?", Long.class, heldId));

        assertEquals(401, api.get("/admin/risk/queue").getStatusCode().value());
        assertTrue(api.get("/admin/risk/queue", "X-Admin-Token", Api.ADMIN).getBody().contains("\"transfer_id\":" + heldId));

        ResponseEntity<String> rel = api.post("/admin/risk/" + heldId + "/decision", Map.of("decision", "release"), api.newKey(), "X-Admin-Token", Api.ADMIN);
        assertEquals(200, rel.getStatusCode().value());
        assertEquals(999_400, api.balance(wa));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM transfer_status_history WHERE transfer_id = ?", Long.class, heldId));

        ResponseEntity<String> rev = api.post("/transfers/" + first + "/reverse", null, api.newKey(), "X-Admin-Token", Api.ADMIN);
        assertEquals(201, rev.getStatusCode().value());
        assertEquals(999_500, api.balance(wa));
        assertEquals("REVERSED", jdbc.queryForObject("SELECT status FROM transfers WHERE id = ?", String.class, first));
        assertEquals(409, api.post("/transfers/" + first + "/reverse", null, api.newKey(), "X-Admin-Token", Api.ADMIN).getStatusCode().value());
    }

    @Test
    void ledgerEntriesCannotBeUpdatedOrDeleted() {
        long wa = api.createWallet(api.createUser("Asha"));
        api.topup(wa, 1_000);
        assertThrows(Exception.class, () -> jdbc.update("UPDATE ledger_entries SET amount = 1 WHERE account_id = ?", wa));
        assertThrows(Exception.class, () -> jdbc.update("DELETE FROM ledger_entries WHERE account_id = ?", wa));
    }

    @Test
    void unbalancedTransferIsRejectedAtCommit() {
        long wa = api.createWallet(api.createUser("Asha"));
        long tid = jdbc.queryForObject("INSERT INTO transfers (type,status,amount,from_account_id,to_account_id) " +
            "VALUES ('PEER','COMPLETED',100,?,(SELECT id FROM accounts WHERE label='CASH_OUT')) RETURNING id", Long.class, wa);
        assertThrows(Exception.class, () -> jdbc.update("INSERT INTO ledger_entries (transfer_id, account_id, direction, amount) VALUES (?, ?, 'D', 100)", tid, wa));
    }
}
