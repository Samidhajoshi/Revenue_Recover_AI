package com.recoverai.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "recoverai.llm")
@Data
public class LlmProperties {

    /** Master switch; also auto-disabled at runtime if GROQ_API_KEY is unset. */
    private boolean enabled = true;

    private String baseUrl = "https://api.groq.com/openai/v1";

    private String model = "openai/gpt-oss-120b";

    private String defaultLanguage = "hinglish";
}
