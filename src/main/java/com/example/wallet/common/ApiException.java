package com.example.wallet.common;

import org.springframework.http.HttpStatus;

/** Thrown anywhere; rendered as {"code","message","request_id"}. Thrown inside a transaction it rolls it back. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
}
