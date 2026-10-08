package com.moneyflow.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Connection settings for the external APIs. Only bound when the real HTTP adapters are active. */
@Validated
@ConfigurationProperties("moneyflow")
public record ApiClientProperties(@Valid @NotNull Client statementApi, @Valid @NotNull Client balanceApi) {

    public record Client(
            @NotNull URI baseUrl,
            @NotBlank String apiKey,
            @DefaultValue("2s") Duration connectTimeout,
            @DefaultValue("5s") Duration readTimeout,
            // Retries after the first call on transient failures (timeouts, connection errors, 5xx, 429).
            @DefaultValue("3") @PositiveOrZero int maxRetries,
            @DefaultValue("5s") Duration retryDelay) {}
}
