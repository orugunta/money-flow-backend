package com.moneyflow.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** The key callers of our API must send. The app refuses to start without one. */
@Validated
@ConfigurationProperties("moneyflow.security")
record ApiSecurityProperties(@NotBlank String apiKey) {}
