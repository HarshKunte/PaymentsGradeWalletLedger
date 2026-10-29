package com.example.wallet.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Minimal shared-secret guard for privileged endpoints (the brief specifies no auth; see README assumptions). */
@Component
public class AdminAuth {
    private final String token;
    public AdminAuth(@Value("${wallet.admin-token}") String token) { this.token = token; }

    public void require(String header) {
        if (header == null || !MessageDigest.isEqual(header.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8)))
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Missing or invalid X-Admin-Token");
    }
}
