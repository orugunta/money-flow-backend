package com.moneyflow.adapter.client.statement;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.moneyflow.adapter.client.TransientFailureRetry;
import com.moneyflow.service.AccountNotFoundException;
import com.moneyflow.service.UpstreamException;
import com.moneyflow.domain.AccountHistory;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

@ExtendWith(OutputCaptureExtension.class)
class HttpTransactionSourceTest {

    private static final String PATH = "/accounts/ACC-123/transactions";

    @RegisterExtension
    static WireMockExtension statementApi = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private HttpTransactionSource source;

    @BeforeEach
    void setUp() {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(Duration.ofMillis(500));
        source = new HttpTransactionSource(
                RestClient.builder()
                        .baseUrl(statementApi.baseUrl())
                        .requestFactory(requestFactory)
                        .build(),
                new TransientFailureRetry("Statement API", 3, Duration.ofMillis(10)));
    }

    @Test
    void fetchesAndMapsTransactions() {
        statementApi.stubFor(get(urlEqualTo(PATH)).willReturn(okJson("""
                {
                  "accountId": "ACC-123",
                  "currency": "EUR",
                  "transactions": [
                    {"id": "t1", "bookingDate": "2026-07-01", "amount": 3000.00, "type": "CREDIT",
                     "description": "Salary", "unknownField": true}
                  ]
                }
                """)));

        AccountHistory history = source.fetch("ACC-123");

        assertThat(history.transactions()).singleElement()
                .satisfies(t -> assertThat(t.amount()).isEqualTo(new BigDecimal("3000.00")));
        statementApi.verify(getRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void notFoundMeansTheAccountDoesNotExist() {
        statementApi.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> source.fetch("ACC-123"))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessage("Account not found: ACC-123");
    }

    @Test
    void otherErrorStatusesAreUpstreamFailures() {
        statementApi.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> source.fetch("ACC-123"))
                .isInstanceOf(UpstreamException.class)
                .hasMessage("Statement API request failed for account ACC-123");
    }

    @Test
    void clientErrorsOtherThanNotFoundAreUpstreamFailures() {
        statementApi.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(401)));

        assertThatThrownBy(() -> source.fetch("ACC-123")).isInstanceOf(UpstreamException.class);
        statementApi.verify(exactly(1), getRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void retriesATransientFailureAndSucceeds(CapturedOutput output) {
        statementApi.stubFor(get(urlEqualTo(PATH)).inScenario("retry").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503).withBody("down\n2026-10-08 INFO forged"))
                .willSetStateTo("recovered"));
        statementApi.stubFor(get(urlEqualTo(PATH)).inScenario("retry").whenScenarioStateIs("recovered")
                .willReturn(okJson("""
                        {"accountId": "ACC-123", "currency": "EUR", "transactions": []}""")));

        assertThat(source.fetch("ACC-123").transactions()).isEmpty();
        statementApi.verify(exactly(2), getRequestedFor(urlEqualTo(PATH)));
        // Status code only: the upstream body never reaches the log.
        assertThat(output).contains("Statement API call failed (HTTP 503), retry 1 of 3 in PT0.01S")
                .doesNotContain("forged");
    }

    @Test
    void givesUpAfterThreeRetries() {
        statementApi.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> source.fetch("ACC-123"))
                .isInstanceOf(UpstreamException.class)
                .hasCauseInstanceOf(HttpServerErrorException.class);
        statementApi.verify(exactly(4), getRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void notFoundIsNotRetried() {
        statementApi.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> source.fetch("ACC-123")).isInstanceOf(AccountNotFoundException.class);
        statementApi.verify(exactly(1), getRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void malformedJsonIsAnUpstreamFailureAndNotRetried() {
        statementApi.stubFor(get(urlEqualTo(PATH)).willReturn(okJson("{\"accountId\": ")));

        assertThatThrownBy(() -> source.fetch("ACC-123")).isInstanceOf(UpstreamException.class);
        statementApi.verify(exactly(1), getRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void emptyBodyIsAnUpstreamFailure() {
        statementApi.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)));

        assertThatThrownBy(() -> source.fetch("ACC-123"))
                .isInstanceOf(UpstreamException.class)
                .hasMessage("Statement API returned an empty body for account ACC-123");
    }

    @Test
    void invalidDataIsAnUpstreamFailure() {
        statementApi.stubFor(get(urlEqualTo(PATH)).willReturn(okJson("""
                {"accountId": "ACC-123", "currency": "EUR",
                 "transactions": [{"id": "t1", "bookingDate": "2026-07-01", "amount": -5, "type": "CREDIT"}]}
                """)));

        assertThatThrownBy(() -> source.fetch("ACC-123"))
                .isInstanceOf(UpstreamException.class)
                .hasMessageContaining("amount is negative");
    }

    @Test
    void readTimeoutIsAnUpstreamFailure() {
        statementApi.stubFor(get(urlEqualTo(PATH)).willReturn(okJson("{}").withFixedDelay(2_000)));

        assertThatThrownBy(() -> source.fetch("ACC-123")).isInstanceOf(UpstreamException.class);
    }

    @Test
    void connectionFailureIsRetriedThenAnUpstreamFailure(CapturedOutput output) {
        statementApi.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> source.fetch("ACC-123")).isInstanceOf(UpstreamException.class);
        statementApi.verify(exactly(4), getRequestedFor(urlEqualTo(PATH)));
        assertThat(output).contains("Statement API call failed (ResourceAccessException), retry 3 of 3");
    }

    @Test
    void encodesTheAccountIdInThePath() {
        statementApi.stubFor(get(urlEqualTo("/accounts/a%2Fb/transactions")).willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> source.fetch("a/b")).isInstanceOf(AccountNotFoundException.class);
        statementApi.verify(getRequestedFor(urlEqualTo("/accounts/a%2Fb/transactions")));
    }
}
