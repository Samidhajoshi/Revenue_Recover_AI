package com.recoverai.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Groq's chat-completions API is OpenAI-compatible; there is no official
 * Groq Java SDK, so this is raw HTTP via WebClient (same pattern as
 * WebClientConfig for the ML service). The API key comes from the GROQ_API_KEY
 * environment variable - if unset, requests will 401 and LlmService falls
 * back to templated output, same graceful-degradation contract as every
 * other external dependency in this app.
 */
@Configuration
public class GroqClientConfig {

    @Bean
    public WebClient groqWebClient(@Value("${recoverai.llm.base-url}") String baseUrl) {
        String apiKey = System.getenv("GROQ_API_KEY");
        WebClient.Builder builder = WebClient.builder().baseUrl(baseUrl);
        if (apiKey != null && !apiKey.isBlank()) {
            builder = builder.defaultHeader("Authorization", "Bearer " + apiKey);
        }
        return builder.build();
    }
}
