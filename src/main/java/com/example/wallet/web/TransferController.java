package com.example.wallet.web;

import com.example.wallet.common.AdminAuth;
import com.example.wallet.common.IdempotencyService;
import com.example.wallet.ledger.TransferService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import static com.example.wallet.web.UserController.need;

@RestController
@RequestMapping("/transfers")
public class TransferController {
    public record TopupRequest(Long walletId, Long amount, String note) {}
    public record PeerRequest(Long fromWalletId, Long toWalletId, Long amount, String note) {}
    public record WithdrawRequest(Long walletId, Long amount, String note) {}

    private final IdempotencyService idem;
    private final TransferService transfers;
    private final AdminAuth auth;

    public TransferController(IdempotencyService idem, TransferService transfers, AdminAuth auth) {
        this.idem = idem; this.transfers = transfers; this.auth = auth;
    }

    @PostMapping("/topup")
    public ResponseEntity<String> topup(@RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody TopupRequest r) {
        need(r.walletId(), r.amount());
        long owner = transfers.walletOwner(r.walletId());
        return idem.execute(owner, key, "POST /transfers/topup", r, () -> transfers.topup(r.walletId(), r.amount(), r.note()));
    }

    @PostMapping
    public ResponseEntity<String> peer(@RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody PeerRequest r) {
        need(r.fromWalletId(), r.toWalletId(), r.amount());
        long owner = transfers.walletOwner(r.fromWalletId());
        return idem.execute(owner, key, "POST /transfers", r, () -> transfers.peer(r.fromWalletId(), r.toWalletId(), r.amount(), r.note()));
    }

    @PostMapping("/withdraw")
    public ResponseEntity<String> withdraw(@RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody WithdrawRequest r) {
        need(r.walletId(), r.amount());
        long owner = transfers.walletOwner(r.walletId());
        return idem.execute(owner, key, "POST /transfers/withdraw", r, () -> transfers.withdraw(r.walletId(), r.amount(), r.note()));
    }

    /** Privileged (no end-user auth exists in the brief): requires X-Admin-Token. */
    @PostMapping("/{id}/reverse")
    public ResponseEntity<String> reverse(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                          @RequestHeader(value = "X-Admin-Token", required = false) String token,
                                          @RequestHeader(value = "X-Admin-Name", defaultValue = "admin") String admin,
                                          @PathVariable long id) {
        auth.require(token);
        return idem.execute(0, key, "POST /transfers/" + id + "/reverse", java.util.Map.of("id", id),
            () -> transfers.reverse(id, "admin:" + admin));
    }
}
