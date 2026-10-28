package com.chernandez.gatewayservice.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Settings shared by every circuit breaker of the gateway (one per downstream service, named in the
 * route filters). Values come from {@code application.resilience.circuit-breaker.*}.
 */
@Configuration
public class ResilienceConfig {

    @Bean
    public Customizer<ReactiveResilience4JCircuitBreakerFactory> defaultCircuitBreakerCustomizer(
        @Value("${application.resilience.circuit-breaker.sliding-window-size:10}") int slidingWindowSize,
        @Value("${application.resilience.circuit-breaker.minimum-calls:5}") int minimumCalls,
        @Value("${application.resilience.circuit-breaker.failure-rate-threshold:50}") float failureRateThreshold,
        @Value("${application.resilience.circuit-breaker.open-state-duration:10s}") Duration openStateDuration,
        @Value("${application.resilience.circuit-breaker.half-open-calls:3}") int halfOpenCalls,
        @Value("${application.resilience.circuit-breaker.time-limit:12s}") Duration timeLimit
    ) {
        CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
            .slidingWindowSize(slidingWindowSize)
            .minimumNumberOfCalls(minimumCalls)
            .failureRateThreshold(failureRateThreshold)
            .waitDurationInOpenState(openStateDuration)
            .permittedNumberOfCallsInHalfOpenState(halfOpenCalls)
            .build();

        TimeLimiterConfig timeLimiterConfig = TimeLimiterConfig.custom()
            .timeoutDuration(timeLimit)
            .build();

        return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
            .circuitBreakerConfig(circuitBreakerConfig)
            .timeLimiterConfig(timeLimiterConfig)
            .build());
    }
}
