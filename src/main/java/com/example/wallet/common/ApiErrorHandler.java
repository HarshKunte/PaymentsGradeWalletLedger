package com.example.wallet.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

/** One error shape everywhere: {"code": "...", "message": "...", "request_id": "..."} */
@RestControllerAdvice
public class ApiErrorHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> api(ApiException e, HttpServletRequest r) {
        return body(e.getStatus(), e.getCode(), e.getMessage(), r);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String, Object>> badInput(Exception e, HttpServletRequest r) {
        return body(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Malformed request body or parameter", r);
    }

    /** Backstop: the CHECK (balance >= 0) fired even though application code should have caught it first. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String, Object>> integrity(DataIntegrityViolationException e, HttpServletRequest r) {
        String m = String.valueOf(e.getMostSpecificCause().getMessage());
        if (m.contains("accounts_no_overdraft_ck"))
            return body(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS", "Insufficient funds", r);
        log.warn("constraint violation: {}", m);
        return body(HttpStatus.CONFLICT, "CONSTRAINT_VIOLATION", "Request violates a data integrity rule", r);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> other(Exception e, HttpServletRequest r) {
        log.error("unhandled", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected error", r);
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus s, String code, String msg, HttpServletRequest r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("message", msg);
        m.put("request_id", r.getAttribute("request_id"));
        return ResponseEntity.status(s).body(m);
    }
}
