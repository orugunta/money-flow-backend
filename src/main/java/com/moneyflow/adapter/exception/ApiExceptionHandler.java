package com.moneyflow.adapter.exception;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.util.DisconnectedClientHelper;

import com.moneyflow.service.AccountNotFoundException;
import com.moneyflow.service.UpstreamException;

/**
 * Maps errors to ProblemDetail. Extends ResponseEntityExceptionHandler so this is the only advice (Boot backs off its
 * own) and Spring MVC errors such as validation failures get ProblemDetail bodies too. With one advice, the handler
 * for the thrown exception wins over handlers for its causes, so an UpstreamException wrapping an
 * HttpMessageNotReadableException is a 502, not mistaken for a bad request.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(AccountNotFoundException.class)
    ProblemDetail handleAccountNotFound(AccountNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Account not found", e.getMessage());
    }

    @ExceptionHandler(UpstreamException.class)
    ProblemDetail handleUpstream(UpstreamException e) {
        // Messages anywhere in the cause chain can quote raw upstream content (error bodies, JSON tokens). The caller
        // gets a generic detail; the log gets the cause chain with sanitized messages.
        log.warn("Upstream failure: {}", LogSanitizer.describe(e));
        return problem(HttpStatus.BAD_GATEWAY, "Upstream service failure",
                "An external service failed or returned invalid data.");
    }

    /**
     * Anything not handled above or by ResponseEntityExceptionHandler (a bug, for example): a generic 500 ProblemDetail
     * instead of the container's error page, and a sanitized log instead of the raw cause chain.
     *
     * <p>This handler matches every exception, so Spring never falls back to matching causes. It does that itself:
     * a wrapped UpstreamException or AccountNotFoundException gets its usual response, and exceptions that Spring maps
     * to a status ({@code @ResponseStatus}, security exceptions) are rethrown, which hands them back to Spring's status
     * resolver and security filter (401/403). Both of those check the cause chain too.
     */
    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception e) throws Exception {
        if (DisconnectedClientHelper.isClientDisconnectedException(e)) {
            // The caller has gone (e.g. gave up during retries): nobody to answer, and not an application error.
            log.debug("Client disconnected: {}", LogSanitizer.sanitize(e.getMessage()));
            return null;
        }
        // No cycle guard needed: Spring's own handler lookup walks the same cause chain before this runs.
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof UpstreamException upstream) {
                return handleUpstream(upstream);
            }
            if (t instanceof AccountNotFoundException notFound) {
                return handleAccountNotFound(notFound);
            }
            if (AnnotatedElementUtils.hasAnnotation(t.getClass(), ResponseStatus.class)
                    || t instanceof AccessDeniedException
                    || t instanceof AuthenticationException) {
                throw e;
            }
        }
        log.error("Unexpected failure: {}", LogSanitizer.describe(e));
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", "An unexpected error occurred.");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
