package com.moneyflow.adapter.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.moneyflow.config.WebSecurityConfig;
import com.moneyflow.service.AccountNotFoundException;
import com.moneyflow.service.MonthlyBalanceService;
import com.moneyflow.service.UpstreamException;
import com.moneyflow.domain.MonthlySummary;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.ResponseStatus;

@WebMvcTest(controllers = BalanceController.class, properties = "moneyflow.security.api-key=test-key")
@Import(WebSecurityConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class BalanceControllerTest {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String API_KEY = "test-key";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MonthlyBalanceService service;

    @Test
    void returnsTheSummaries() throws Exception {
        when(service.calculateAndPublish("ACC-123")).thenReturn(List.of(new MonthlySummary(
                "ACC-123",
                YearMonth.of(2026, 7),
                "EUR",
                new BigDecimal("3000.00"),
                new BigDecimal("1000.00"),
                new BigDecimal("2000.00"))));

        mockMvc.perform(post("/api/v1/accounts/ACC-123/balances").header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        [{"accountId":"ACC-123","month":"2026-07","currency":"EUR",
                          "totalIncome":3000.00,"totalSpending":1000.00,"balance":2000.00}]""", JsonCompareMode.STRICT));
    }

    @Test
    void unknownAccountIs404ProblemDetail() throws Exception {
        when(service.calculateAndPublish("NOPE")).thenThrow(new AccountNotFoundException("NOPE"));

        mockMvc.perform(post("/api/v1/accounts/NOPE/balances").header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Account not found"))
                .andExpect(jsonPath("$.detail").value("Account not found: NOPE"));
    }

    @Test
    void clientsAcceptingOnlyProblemJsonStillGetTheError() throws Exception {
        when(service.calculateAndPublish("NOPE")).thenThrow(new AccountNotFoundException("NOPE"));

        mockMvc.perform(post("/api/v1/accounts/NOPE/balances")
                        .header(API_KEY_HEADER, API_KEY)
                        .accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void upstreamFailureIs502WithAGenericDetail() throws Exception {
        when(service.calculateAndPublish("ACC-123"))
                .thenThrow(new UpstreamException("Statement API returned invalid data: secret raw value"));

        mockMvc.perform(post("/api/v1/accounts/ACC-123/balances").header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isBadGateway())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Upstream service failure"))
                .andExpect(jsonPath("$.detail").value("An external service failed or returned invalid data."));
    }

    @Test
    void logsUpstreamFailuresSanitized(CapturedOutput output) throws Exception {
        when(service.calculateAndPublish("ACC-123")).thenThrow(new UpstreamException(
                "Statement API request failed for account ACC-123",
                new IllegalStateException("body: {\"x\":1}\n2026-10-07 INFO forged entry")));

        mockMvc.perform(post("/api/v1/accounts/ACC-123/balances").header(API_KEY_HEADER, API_KEY)).andExpect(status().isBadGateway());

        // The cause chain on one line, with real class names and the line break escaped.
        assertThat(output)
                .contains("Upstream failure: com.moneyflow.service.UpstreamException: "
                        + "Statement API request failed for account ACC-123 <- java.lang.IllegalStateException: "
                        + "body: {\"x\":1}\\n2026-10-07 INFO forged entry")
                .doesNotContain("\n2026-10-07 INFO forged entry");
    }

    @Test
    void unexpectedFailureIs500WithAGenericDetailAndASanitizedLog(CapturedOutput output) throws Exception {
        when(service.calculateAndPublish("ACC-123"))
                .thenThrow(new IllegalStateException("bug\n2026-10-08 INFO forged entry"));

        mockMvc.perform(post("/api/v1/accounts/ACC-123/balances").header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Internal server error"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."));

        assertThat(output)
                .contains("Unexpected failure: java.lang.IllegalStateException: bug\\n2026-10-08 INFO forged entry")
                .doesNotContain("\n2026-10-08 INFO forged entry");
    }

    @Test
    void unexpectedFailureWithoutAMessageIsLoggedByClassName(CapturedOutput output) throws Exception {
        when(service.calculateAndPublish("ACC-123")).thenThrow(new NullPointerException());

        mockMvc.perform(post("/api/v1/accounts/ACC-123/balances").header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isInternalServerError());

        assertThat(output)
                .contains("Unexpected failure: java.lang.NullPointerException" + System.lineSeparator())
                .doesNotContain("NullPointerException: null");
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    static class ConflictException extends RuntimeException {}

    @Test
    void wrappedUpstreamFailureIsStillA502() throws Exception {
        when(service.calculateAndPublish("ACC-123"))
                .thenThrow(new IllegalStateException("wrapper", new UpstreamException("Balance API request failed")));

        mockMvc.perform(post("/api/v1/accounts/ACC-123/balances").header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.title").value("Upstream service failure"));
    }

    @Test
    void wrappedUnknownAccountIsStillA404() throws Exception {
        when(service.calculateAndPublish("NOPE"))
                .thenThrow(new IllegalStateException("wrapper", new AccountNotFoundException("NOPE")));

        mockMvc.perform(post("/api/v1/accounts/NOPE/balances").header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Account not found: NOPE"));
    }

    @Test
    void wrappedResponseStatusExceptionKeepsItsStatus() throws Exception {
        when(service.calculateAndPublish("ACC-123"))
                .thenThrow(new IllegalStateException("wrapper", new ConflictException()));

        mockMvc.perform(post("/api/v1/accounts/ACC-123/balances").header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isConflict());
    }

    @Test
    void disconnectedClientIsNotLoggedAsAnError(CapturedOutput output) throws Exception {
        when(service.calculateAndPublish("ACC-123")).thenAnswer(invocation -> {
            throw new IOException("Broken pipe");
        });

        mockMvc.perform(post("/api/v1/accounts/ACC-123/balances").header(API_KEY_HEADER, API_KEY));

        assertThat(output).doesNotContain("Unexpected failure");
    }

    @Test
    void exceptionsWithAResponseStatusKeepTheirStatus() throws Exception {
        when(service.calculateAndPublish("ACC-123")).thenThrow(new ConflictException());

        mockMvc.perform(post("/api/v1/accounts/ACC-123/balances").header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isConflict());
    }

    @Test
    void accessDeniedIsLeftToSpringSecurity() throws Exception {
        when(service.calculateAndPublish("ACC-123")).thenThrow(new AccessDeniedException("no"));

        mockMvc.perform(post("/api/v1/accounts/ACC-123/balances").header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isForbidden());
    }

    @Test
    void authenticationFailureIsLeftToSpringSecurity() throws Exception {
        when(service.calculateAndPublish("ACC-123")).thenThrow(new BadCredentialsException("no"));

        mockMvc.perform(post("/api/v1/accounts/ACC-123/balances").header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "ApiKey header=\"X-API-Key\""));
    }

    @Test
    void malformedAccountIdIs400AndNeverReachesTheService() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/bad_id!/balances").header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(service);
    }

    @Test
    void missingApiKeyIs401AndNeverReachesTheService() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/ACC-123/balances"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "ApiKey header=\"X-API-Key\""))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"type":"about:blank","title":"Unauthorized","status":401,
                         "detail":"A valid X-API-Key header is required."}""", JsonCompareMode.STRICT));

        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "wrong-key", "test-ke", "test-key2", "TEST-KEY", " test-key"})
    void wrongApiKeyIs401AndNeverReachesTheService(String key) throws Exception {
        mockMvc.perform(post("/api/v1/accounts/ACC-123/balances").header(API_KEY_HEADER, key))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    @Test
    void onlyPostIsSupported() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/ACC-123/balances").header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isMethodNotAllowed());
    }
}
