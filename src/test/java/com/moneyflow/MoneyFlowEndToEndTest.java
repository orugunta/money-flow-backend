package com.moneyflow;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** The whole application with the real HTTP adapters, against WireMock versions of both external APIs. */
@SpringBootTest
@AutoConfigureMockMvc
class MoneyFlowEndToEndTest {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String API_KEY = "trigger-key";

    private static final String TRANSACTIONS = """
            {
              "accountId": "ACC-123",
              "currency": "EUR",
              "transactions": [
                {"id": "t3", "bookingDate": "2026-08-01", "amount": 2500.00, "type": "CREDIT", "description": "Salary"},
                {"id": "t1", "bookingDate": "2026-07-01", "amount": 3000.00, "type": "CREDIT", "description": "Salary"},
                {"id": "t2", "bookingDate": "2026-07-31", "amount": 1000.00, "type": "DEBIT", "description": "Rent"}
              ]
            }
            """;

    @RegisterExtension
    static WireMockExtension statementApi = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @RegisterExtension
    static WireMockExtension balanceApi = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void apiProperties(DynamicPropertyRegistry registry) {
        registry.add("moneyflow.security.api-key", () -> API_KEY);
        registry.add("moneyflow.statement-api.base-url", statementApi::baseUrl);
        registry.add("moneyflow.statement-api.api-key", () -> "statement-key");
        registry.add("moneyflow.balance-api.base-url", balanceApi::baseUrl);
        registry.add("moneyflow.balance-api.api-key", () -> "balance-key");
        registry.add("moneyflow.statement-api.read-timeout", () -> "500ms");
        // Real retry counts, short waits so failure cases stay fast.
        registry.add("moneyflow.statement-api.retry-delay", () -> "10ms");
        registry.add("moneyflow.balance-api.retry-delay", () -> "10ms");
    }

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void balanceApiAcceptsEverything() {
        balanceApi.stubFor(put(urlMatching("/monthly-balances/.*")).willReturn(aResponse().withStatus(201)));
    }

    @Test
    void fetchesCalculatesAndSendsOneSummaryPerMonth() throws Exception {
        statementApi.stubFor(get(urlEqualTo("/accounts/ACC-123/transactions")).willReturn(okJson(TRANSACTIONS)));

        mockMvc.perform(post("/api/v1/accounts/ACC-123/balances").header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].month").value("2026-07"))
                .andExpect(jsonPath("$[0].balance").value(2000.00))
                .andExpect(jsonPath("$[1].month").value("2026-08"))
                .andExpect(jsonPath("$[1].totalSpending").value(0.00));

        statementApi.verify(getRequestedFor(urlEqualTo("/accounts/ACC-123/transactions"))
                .withHeader("X-API-Key", equalTo("statement-key"))
                // HTTP/1.1 only: no h2c upgrade attempt, which some proxies reject.
                .withoutHeader("Upgrade"));
        balanceApi.verify(putRequestedFor(urlEqualTo("/monthly-balances/ACC-123/2026-07"))
                .withHeader("X-API-Key", equalTo("balance-key"))
                .withHeader("Content-Length", matching("[0-9]+"))
                .withRequestBody(equalTo("""
                        {"accountId":"ACC-123","month":"2026-07","currency":"EUR",\
                        "totalIncome":3000.00,"totalSpending":1000.00,"balance":2000.00}""")));
        balanceApi.verify(putRequestedFor(urlEqualTo("/monthly-balances/ACC-123/2026-08"))
                .withRequestBody(equalTo("""
                        {"accountId":"ACC-123","month":"2026-08","currency":"EUR",\
                        "totalIncome":2500.00,"totalSpending":0.00,"balance":2500.00}""")));
    }

    @Test
    void rerunningSendsTheSameValuesAgain() throws Exception {
        statementApi.stubFor(get(urlEqualTo("/accounts/ACC-123/transactions")).willReturn(okJson(TRANSACTIONS)));

        mockMvc.perform(post("/api/v1/accounts/ACC-123/balances").header(API_KEY_HEADER, API_KEY)).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/accounts/ACC-123/balances").header(API_KEY_HEADER, API_KEY)).andExpect(status().isOk());

        balanceApi.verify(exactly(2), putRequestedFor(urlEqualTo("/monthly-balances/ACC-123/2026-07")));
        balanceApi.verify(exactly(2), putRequestedFor(urlEqualTo("/monthly-balances/ACC-123/2026-08")));
    }

    @Test
    void transientFailuresOnBothApisAreRetried() throws Exception {
        statementApi.stubFor(get(urlEqualTo("/accounts/ACC-123/transactions")).inScenario("fetch")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("up"));
        statementApi.stubFor(get(urlEqualTo("/accounts/ACC-123/transactions")).inScenario("fetch")
                .whenScenarioStateIs("up")
                .willReturn(okJson(TRANSACTIONS)));
        balanceApi.stubFor(put(urlEqualTo("/monthly-balances/ACC-123/2026-07")).inScenario("send")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("up"));
        balanceApi.stubFor(put(urlEqualTo("/monthly-balances/ACC-123/2026-07")).inScenario("send")
                .whenScenarioStateIs("up")
                .willReturn(aResponse().withStatus(200)));

        mockMvc.perform(post("/api/v1/accounts/ACC-123/balances").header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        statementApi.verify(exactly(2), getRequestedFor(urlEqualTo("/accounts/ACC-123/transactions")));
        balanceApi.verify(exactly(2), putRequestedFor(urlEqualTo("/monthly-balances/ACC-123/2026-07")));
        balanceApi.verify(exactly(1), putRequestedFor(urlEqualTo("/monthly-balances/ACC-123/2026-08")));
    }

    @Test
    void apiDocsAreOffByDefault() throws Exception {
        // Only the stub profile turns them on; WireMock's get() is imported statically, hence the qualified name.
        mockMvc.perform(MockMvcRequestBuilders.get("/v3/api-docs")).andExpect(status().isNotFound());
        mockMvc.perform(MockMvcRequestBuilders.get("/swagger-ui/index.html")).andExpect(status().isNotFound());
    }

    @Test
    void withoutTheApiKeyNothingIsFetchedOrSent() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/ACC-123/balances")).andExpect(status().isUnauthorized());

        statementApi.verify(exactly(0), getRequestedFor(urlMatching(".*")));
        balanceApi.verify(exactly(0), putRequestedFor(urlMatching(".*")));
    }

    @Test
    void unknownAccountIs404AndNothingIsSent() throws Exception {
        statementApi.stubFor(get(urlEqualTo("/accounts/NOPE/transactions")).willReturn(aResponse().withStatus(404)));

        mockMvc.perform(post("/api/v1/accounts/NOPE/balances").header(API_KEY_HEADER, API_KEY)).andExpect(status().isNotFound());

        balanceApi.verify(exactly(0), putRequestedFor(urlMatching(".*")));
    }

    @Test
    void malformedUpstreamJsonIs502() throws Exception {
        // Regression: Boot's ProblemDetail handler used to match the nested HttpMessageNotReadableException → 500.
        statementApi.stubFor(get(urlEqualTo("/accounts/ACC-123/transactions"))
                .willReturn(okJson("{\"accountId\": \"ACC-123\", \"type\": \"X\nY\"}")));

        mockMvc.perform(post("/api/v1/accounts/ACC-123/balances").header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("An external service failed or returned invalid data."));
    }

    @Test
    void statementApiTimeoutIs502() throws Exception {
        statementApi.stubFor(get(urlEqualTo("/accounts/ACC-123/transactions"))
                .willReturn(okJson(TRANSACTIONS).withFixedDelay(2_000)));

        mockMvc.perform(post("/api/v1/accounts/ACC-123/balances").header(API_KEY_HEADER, API_KEY)).andExpect(status().isBadGateway());
    }

    @Test
    void statementApiRedirectIs502() throws Exception {
        statementApi.stubFor(get(urlEqualTo("/accounts/ACC-123/transactions"))
                .willReturn(aResponse().withStatus(302).withHeader("Location", "https://elsewhere.example.com")));

        mockMvc.perform(post("/api/v1/accounts/ACC-123/balances").header(API_KEY_HEADER, API_KEY)).andExpect(status().isBadGateway());
    }

    @Test
    void balanceApiRedirectIs502BecauseNothingWasStored() throws Exception {
        statementApi.stubFor(get(urlEqualTo("/accounts/ACC-123/transactions")).willReturn(okJson(TRANSACTIONS)));
        balanceApi.stubFor(put(urlEqualTo("/monthly-balances/ACC-123/2026-07"))
                .willReturn(aResponse().withStatus(307).withHeader("Location", "https://elsewhere.example.com")));

        mockMvc.perform(post("/api/v1/accounts/ACC-123/balances").header(API_KEY_HEADER, API_KEY)).andExpect(status().isBadGateway());

        balanceApi.verify(exactly(0), putRequestedFor(urlEqualTo("/monthly-balances/ACC-123/2026-08")));
    }

    @Test
    void balanceApiFailureStopsAtThatMonthWith502() throws Exception {
        statementApi.stubFor(get(urlEqualTo("/accounts/ACC-123/transactions")).willReturn(okJson(TRANSACTIONS)));
        balanceApi.stubFor(put(urlEqualTo("/monthly-balances/ACC-123/2026-07")).willReturn(aResponse().withStatus(503)));

        mockMvc.perform(post("/api/v1/accounts/ACC-123/balances").header(API_KEY_HEADER, API_KEY)).andExpect(status().isBadGateway());

        // The first call plus 3 retries, then the run stops before the next month.
        balanceApi.verify(exactly(4), putRequestedFor(urlEqualTo("/monthly-balances/ACC-123/2026-07")));
        balanceApi.verify(exactly(0), putRequestedFor(urlEqualTo("/monthly-balances/ACC-123/2026-08")));
    }
}
