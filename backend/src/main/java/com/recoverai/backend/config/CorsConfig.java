package com.recoverai.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Value("${cors.allowed-origins:http://localhost:5173}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // Patterns (not allowedOrigins) so a Vercel preview-deployment wildcard
        // like https://your-app-*.vercel.app still works alongside allowCredentials(true).
        registry.addMapping("/api/**")
                .allowedOriginPatterns(java.util.Arrays.stream(allowedOrigins.split(","))
                        .map(String::trim).toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
