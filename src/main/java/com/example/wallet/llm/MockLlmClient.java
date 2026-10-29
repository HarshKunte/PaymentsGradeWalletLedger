package com.example.wallet.llm;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Deterministic stand-in: tests register question -> JSON; no network, no API key. */
public class MockLlmClient implements LlmClient {
    private final Map<String, String> answers = new ConcurrentHashMap<>();
    public volatile String lastSystem;
    public volatile String lastUser;

    public void when(String question, String jsonResponse) { answers.put(question, jsonResponse); }
    public void reset() { answers.clear(); }

    @Override
    public String complete(String system, String user) {
        lastSystem = system;
        lastUser = user;
        if (system.contains("TASK=RISK_EXPLAIN"))
            return "{\"explanation\":\"A risk rule fired for this transfer. A reviewer should check the recent activity.\",\"label\":\"review\"}";
        return answers.getOrDefault(user, "{\"type\":0}");
    }
}
