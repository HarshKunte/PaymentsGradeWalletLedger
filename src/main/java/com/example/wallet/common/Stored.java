package com.example.wallet.common;

/** An HTTP response (status + exact JSON body) that can be saved on an idempotency key and replayed. */
public record Stored(int status, String body) {}
