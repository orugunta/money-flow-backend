package com.moneyflow;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

/** The app runs end to end without any external API or configuration. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("stub")
class StubProfileTest {

    private static final String API_KEY_HEADER = "X-API-Key";
    // The stub profile's default, so the app runs locally without setup.
    private static final String API_KEY = "local-dev-key";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void calculatesTheBundledFixture() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/ACC-123/balances").header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        [
                          {"accountId":"ACC-123","month":"2026-07","currency":"EUR",
                           "totalIncome":3000.00,"totalSpending":1000.00,"balance":2000.00},
                          {"accountId":"ACC-123","month":"2026-08","currency":"EUR",
                           "totalIncome":2500.00,"totalSpending":0.00,"balance":2500.00}
                        ]""", JsonCompareMode.STRICT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"NOPE", "WRONG-ID"})
    void accountsWithoutAMatchingFixtureAre404(String accountId) throws Exception {
        mockMvc.perform(post("/api/v1/accounts/{id}/balances", accountId).header(API_KEY_HEADER, API_KEY)).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"BROKEN", "EMPTY"})
    void unreadableFixturesAre502(String accountId) throws Exception {
        mockMvc.perform(post("/api/v1/accounts/{id}/balances", accountId).header(API_KEY_HEADER, API_KEY)).andExpect(status().isBadGateway());
    }

    @Test
    void healthIsUpAndNeedsNoApiKey() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    }

    @Test
    void infoNeedsNoApiKey() throws Exception {
        mockMvc.perform(get("/actuator/info")).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/accounts/ACC-123/balances", "/actuator/env", "/unknown"})
    void everythingElseNeedsTheApiKey(String path) throws Exception {
        mockMvc.perform(post(path))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "ApiKey header=\"X-API-Key\""));
    }

    @Test
    void refusesToStartWithoutAnApiKey() {
        SpringApplication app = new SpringApplication(MoneyFlowBackendApplication.class);
        app.setAdditionalProfiles("stub");

        assertThatThrownBy(() -> app.run("--server.port=0", "--moneyflow.security.api-key="))
                .rootCause()
                .hasMessageContaining("moneyflow.security");
    }
}
