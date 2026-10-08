package com.moneyflow.adapter.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

class TransientFailureRetryTest {

    private final TransientFailureRetry retry = new TransientFailureRetry("Test API", 3, Duration.ofSeconds(5));

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void interruptedWaitStopsRetryingWithTheLastFailure() {
        AtomicInteger calls = new AtomicInteger();
        Thread.currentThread().interrupt();

        // The adapters wrap RestClientException in UpstreamException, so an interrupted retry is still a 502.
        assertThatThrownBy(() -> retry.call(() -> {
                    calls.incrementAndGet();
                    throw new ResourceAccessException("connection reset");
                }))
                .isInstanceOf(ResourceAccessException.class)
                .hasMessage("connection reset");
        assertThat(calls).hasValue(1);
        assertThat(Thread.currentThread().isInterrupted()).as("interrupt flag kept").isTrue();
    }
}
