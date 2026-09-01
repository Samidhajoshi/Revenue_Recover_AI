package com.recoverai.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "recoverai.policy")
@Data
public class PolicyProperties {

    private int maxRetries = 3;
    private QuietHours quietHours = new QuietHours();
    private double minimumRecoveryProbability = 0.4;
    private double highValueThreshold = 100000;

    @Data
    public static class QuietHours {
        private String start = "20:00";
        private String end = "09:00";
    }
}
