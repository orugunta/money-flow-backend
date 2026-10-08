package com.moneyflow.adapter.auth;

import static java.nio.charset.StandardCharsets.UTF_8;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/** 401 with a ProblemDetail body, the same for a missing and a wrong key so callers can't tell them apart. */
public final class ApiKeyAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final String CHALLENGE = "ApiKey header=\"" + ApiKeyAuthenticationFilter.API_KEY_HEADER + "\"";
    private static final byte[] BODY = """
            {"type":"about:blank","title":"Unauthorized","status":401,"detail":"A valid %s header is required."}"""
            .formatted(ApiKeyAuthenticationFilter.API_KEY_HEADER)
            .getBytes(UTF_8);

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, CHALLENGE);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setContentLength(BODY.length);
        response.getOutputStream().write(BODY);
    }
}
