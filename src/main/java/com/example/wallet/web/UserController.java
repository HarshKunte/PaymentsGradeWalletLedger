package com.example.wallet.web;

import com.example.wallet.common.ApiException;
import com.example.wallet.common.IdempotencyService;
import com.example.wallet.ledger.AccountService;
import com.example.wallet.ledger.TransferService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class UserController {
    public record CreateUserRequest(String name, String email, String phone, String kycStatus) {}
    public record CreateWalletRequest(String label) {}

    private final IdempotencyService idem;
    private final AccountService accounts;
    private final TransferService transfers;

    public UserController(IdempotencyService idem, AccountService accounts, TransferService transfers) {
        this.idem = idem; this.accounts = accounts; this.transfers = transfers;
    }

    @PostMapping("/users")
    public ResponseEntity<String> createUser(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                             @RequestBody CreateUserRequest r) {
        return idem.execute(0, key, "POST /users", r, () -> accounts.createUser(r.name(), r.email(), r.phone(), r.kycStatus()));
    }

    @PostMapping("/users/{id}/wallets")
    public ResponseEntity<String> createWallet(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                               @PathVariable long id, @RequestBody(required = false) CreateWalletRequest r) {
        CreateWalletRequest body = r == null ? new CreateWalletRequest(null) : r;
        return idem.execute(id, key, "POST /users/" + id + "/wallets", body, () -> accounts.createWallet(id, body.label()));
    }

    @GetMapping("/wallets/{id}/balance")
    public Map<String, Object> balance(@PathVariable long id) { return accounts.balance(id); }

    @GetMapping("/wallets/{id}/statement")
    public Map<String, Object> statement(@PathVariable long id,
                                         @RequestParam(defaultValue = "50") int limit,
                                         @RequestParam(defaultValue = "0") int offset,
                                         @RequestParam(required = false) String cursor) {
        return accounts.statement(id, limit, offset, cursor);
    }

    @GetMapping("/transfers/{id}")
    public Map<String, Object> transfer(@PathVariable long id) { return transfers.get(id); }

    static void need(Object... values) {
        for (Object v : values)
            if (v == null) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Missing required field(s)");
    }
}
