package com.example.wallet.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LlmConfig {
    @Bean
    @ConditionalOnProperty(name = "wallet.llm.provider", havingValue = "openai")
    LlmClient anthropicLlm(@Value("${wallet.llm.api-key}") String key, @Value("${wallet.llm.model}") String model) {
        if (key == null || key.isBlank()) throw new IllegalStateException("ANTHROPIC_API_KEY is required when LLM_PROVIDER=anthropic");
        return new AnthropicLlmClient(key, model);
    }

    @Bean
    @ConditionalOnProperty(name = "wallet.llm.provider", havingValue = "mock", matchIfMissing = true)
    MockLlmClient mockLlm() { return new MockLlmClient(); }
}
