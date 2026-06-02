package com.fachat.agent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fachat.agent.OpenRouterClient;

@Configuration
public class ClientConfiguration {

    @Bean
    public OpenRouterClient openRouterClient(
        @Value("${OPENROUTER_API_KEY:}") String openRouterApiKey
    ) {
        return new OpenRouterClient(openRouterApiKey);
    }
}
