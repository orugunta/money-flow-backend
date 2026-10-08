package com.moneyflow.adapter.auth;

import static java.nio.charset.StandardCharsets.UTF_8;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.MessageDigest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates requests that carry the configured key in {@code X-API-Key}. Requests without a valid key pass through
 * unauthenticated, and the authorization rules decide whether they get a 401.
 */
public final class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-API-Key";

    private final SecurityContextHolderStrategy securityContextHolder = SecurityContextHolder.getContextHolderStrategy();
    private final byte[] expectedKey;

    public ApiKeyAuthenticationFilter(String apiKey) {
        this.expectedKey = apiKey.getBytes(UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(API_KEY_HEADER);
        // Constant-time comparison so response timing doesn't reveal how much of a guessed key is right.
        if (key != null && MessageDigest.isEqual(expectedKey, key.getBytes(UTF_8))) {
            SecurityContext context = securityContextHolder.createEmptyContext();
            context.setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated("api-client", null, AuthorityUtils.NO_AUTHORITIES));
            securityContextHolder.setContext(context);
        }
        chain.doFilter(request, response);
    }
}
