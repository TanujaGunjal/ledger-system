package com.ledger.fraud.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS configuration for the fraud-service REST API.
 *
 * Allows all origins with the standard HTTP methods. This matches the identical
 * configuration used in ledger-api and reconciliation-worker, enabling the shared
 * React frontend to call all three services from the same browser origin without
 * triggering CORS preflight failures.
 *
 * In a production hardening pass this should be restricted to the specific
 * frontend origin (e.g. the CDN domain), but for a development/demo environment
 * a wildcard is acceptable and consistent with the rest of the project.
 */
@Configuration
public class CorsConfig {

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/**")
                        .allowedOrigins("*")
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                        .allowedHeaders("*");
            }
        };
    }
}
