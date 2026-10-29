package com.example.wallet.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
public class RequestIdFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String id = req.getHeader("X-Request-Id");
        if (id == null || id.isBlank() || id.length() > 100) id = UUID.randomUUID().toString();
        req.setAttribute("request_id", id);
        res.setHeader("X-Request-Id", id);
        MDC.put("request_id", id);
        try { chain.doFilter(req, res); } finally { MDC.remove("request_id"); }
    }
}
