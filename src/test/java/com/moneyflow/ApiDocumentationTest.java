package com.moneyflow;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.moneyflow.domain.AccountId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** The OpenAPI document and Swagger UI are served without an API key and describe the trigger endpoint. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("stub")
class ApiDocumentationTest {

    private static final String OPERATION = "$.paths['/api/v1/accounts/{accountId}/balances'].post";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void describesTheTriggerEndpoint() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Money Flow API"))
                .andExpect(jsonPath(OPERATION + ".parameters[0].name").value("accountId"))
                .andExpect(jsonPath(OPERATION + ".parameters[0].schema.pattern").value(AccountId.PATTERN))
                .andExpect(jsonPath(OPERATION + ".responses.*.description").value(containsInAnyOrder(
                        "All months calculated and sent",
                        "Invalid account id",
                        "X-API-Key header missing or wrong",
                        "The Statement API doesn't know the account",
                        "An external API failed, timed out or returned invalid data",
                        "Unexpected error")))
                .andExpect(jsonPath(OPERATION + ".responses.200.content['application/json'].schema.items.$ref")
                        .value("#/components/schemas/MonthlySummaryResponse"))
                .andExpect(jsonPath(OPERATION + ".responses.401.content['application/problem+json']").exists());
    }

    @Test
    void requiresTheApiKeyHeader() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.components.securitySchemes.apiKey.type").value("apiKey"))
                .andExpect(jsonPath("$.components.securitySchemes.apiKey.in").value("header"))
                .andExpect(jsonPath("$.components.securitySchemes.apiKey.name").value("X-API-Key"))
                .andExpect(jsonPath("$.security[0].apiKey").isArray());
    }

    @Test
    void servesYaml() throws Exception {
        mockMvc.perform(get("/v3/api-docs.yaml"))
                .andExpect(status().isOk())
                .andExpect(content().string(startsWith("openapi: 3.1.0")));
    }

    @Test
    void servesSwaggerUi() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/swagger-ui/index.html"));
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }
}
