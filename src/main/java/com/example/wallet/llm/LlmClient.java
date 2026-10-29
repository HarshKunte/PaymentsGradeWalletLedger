package com.example.wallet.llm;

/** The only thing the backend knows about an LLM provider. */
public interface LlmClient {
    String complete(String system, String user);
}
