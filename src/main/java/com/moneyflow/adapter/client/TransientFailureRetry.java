package com.moneyflow.adapter.client;

import java.time.Duration;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.retry.RetryListener;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryState;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.core.retry.Retryable;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Retries an external API call on transient failures only: I/O errors and timeouts, 5xx and 429. Anything else (404,
 * other 4xx, redirects, unreadable bodies, and 501/505/508/510/511, which no retry fixes) would fail the same way again, so it
 * is thrown straight away. When the retries run out, the last failure is thrown.
 */
public final class TransientFailureRetry {

    private static final Logger log = LoggerFactory.getLogger(TransientFailureRetry.class);


    private final RetryTemplate retryTemplate;

    public TransientFailureRetry(String api, long maxRetries, Duration delay) {
        retryTemplate = new RetryTemplate(RetryPolicy.builder()
                .maxRetries(maxRetries)
                .delay(delay)
                .predicate(TransientFailureRetry::isTransient)
                .build());

        retryTemplate.setRetryListener(new RetryListener() {
            @Override
            public void beforeRetry(RetryPolicy policy, Retryable<?> retryable, RetryState state) {
                log.warn("{} call failed ({}), retry {} of {} in {}",
                        api,
                        reason(state.getLastException()),
                        state.getRetryCount(),
                        maxRetries,
                        delay);
            }
        });
    }

    public <T> T call(Supplier<T> call) {
        return retryTemplate.invoke(call);
    }

    static boolean isTransient(Throwable failure) {
        if (failure instanceof ResourceAccessException
                || failure instanceof HttpClientErrorException.TooManyRequests) {
            return true;
        }
    
        return failure instanceof HttpServerErrorException e
                && switch (e.getStatusCode().value()) {
                    case 501, 505, 508, 510, 511 -> false;
                    default -> true;
                };
    }

    private static String reason(Throwable failure) {
        return failure instanceof HttpStatusCodeException e
                ? "HTTP " + e.getStatusCode().value()
                : failure.getClass().getSimpleName();
    }
}
