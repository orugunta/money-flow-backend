package com.moneyflow.adapter.client.balance;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.moneyflow.adapter.client.TransientFailureRetry;
import com.moneyflow.service.UpstreamException;
import com.moneyflow.domain.MonthlySummary;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

class HttpSummaryPublisherTest {

    private static final String PATH = "/monthly-balances/ACC-123/2026-07";
    private static final MonthlySummary SUMMARY = new MonthlySummary(
            "ACC-123",
            YearMonth.of(2026, 7),
            "EUR",
            new BigDecimal("3000.00"),
            new BigDecimal("1000.00"),
            new BigDecimal("2000.00"));

    @RegisterExtension
    static WireMockExtension balanceApi = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    // Buffered like the production client (see HttpAdapterConfig); unbuffered JDK requests stream the body chunked.
    private final HttpSummaryPublisher publisher = new HttpSummaryPublisher(
            RestClient.builder()
                    .baseUrl(balanceApi.baseUrl())
                    .requestFactory(new BufferingClientHttpRequestFactory(new JdkClientHttpRequestFactory()))
                    .build(),
            new TransientFailureRetry("Balance API", 3, Duration.ofMillis(10)));

    @Test
    void putsTheSummaryForItsAccountAndMonth() {
        balanceApi.stubFor(put(urlEqualTo(PATH)).willReturn(aResponse().withStatus(201)));

        publisher.publish(SUMMARY);

        // Exact numeric text, so scale is part of the contract (3000.00, not 3000 or 3000.0).
        balanceApi.verify(putRequestedFor(urlEqualTo(PATH))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalTo("""
                        {"accountId":"ACC-123","month":"2026-07","currency":"EUR",\
                        "totalIncome":3000.00,"totalSpending":1000.00,"balance":2000.00}""")));
    }

    @Test
    void acceptsReplacingAnExistingSummary() {
        balanceApi.stubFor(put(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)));

        publisher.publish(SUMMARY);

        balanceApi.verify(putRequestedFor(urlEqualTo(PATH)).withRequestBody(equalToJson("""
                {"accountId": "ACC-123", "month": "2026-07"}""", true, true)));
    }

    @Test
    void anyErrorStatusIsAnUpstreamFailure() {
        balanceApi.stubFor(put(urlEqualTo(PATH)).willReturn(aResponse()
                .withStatus(422)
                .withBody("{\"errors\":[\"month is closed\"]}")));

        // The error body stays in the cause, so the log shows why the Balance API rejected the summary.
        assertThatThrownBy(() -> publisher.publish(SUMMARY))
                .isInstanceOf(UpstreamException.class)
                .hasMessage("Balance API request failed for account ACC-123, month 2026-07")
                .cause()
                .isInstanceOf(HttpClientErrorException.class)
                .hasMessageContaining("month is closed");
        balanceApi.verify(exactly(1), putRequestedFor(urlEqualTo(PATH)));
    }

    @ParameterizedTest
    @ValueSource(ints = {501, 505, 508, 510, 511})
    void serverErrorsThatNeverRecoverAreNotRetried(int status) {
        balanceApi.stubFor(put(urlEqualTo(PATH)).willReturn(aResponse().withStatus(status)));

        assertThatThrownBy(() -> publisher.publish(SUMMARY)).isInstanceOf(UpstreamException.class);
        balanceApi.verify(exactly(1), putRequestedFor(urlEqualTo(PATH)));
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 503})
    void retriesTransientStatusesAndResendsTheSameBody(int status) {
        balanceApi.stubFor(put(urlEqualTo(PATH)).inScenario("retry").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(status))
                .willSetStateTo("recovered"));
        balanceApi.stubFor(put(urlEqualTo(PATH)).inScenario("retry").whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)));

        publisher.publish(SUMMARY);

        balanceApi.verify(exactly(2), putRequestedFor(urlEqualTo(PATH)).withRequestBody(equalToJson("""
                {"accountId":"ACC-123","month":"2026-07","currency":"EUR",
                 "totalIncome":3000.00,"totalSpending":1000.00,"balance":2000.00}""")));
    }

    @Test
    void connectionFailureIsRetriedThenAnUpstreamFailure() {
        balanceApi.stubFor(put(urlEqualTo(PATH)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> publisher.publish(SUMMARY))
                .isInstanceOf(UpstreamException.class)
                .hasMessage("Balance API request failed for account ACC-123, month 2026-07")
                .hasCauseInstanceOf(ResourceAccessException.class);
        balanceApi.verify(exactly(4), putRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void serverErrorIsAnUpstreamFailureAfterThreeRetries() {
        balanceApi.stubFor(put(urlEqualTo(PATH)).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> publisher.publish(SUMMARY)).isInstanceOf(UpstreamException.class);
        balanceApi.verify(exactly(4), putRequestedFor(urlEqualTo(PATH)));
    }
}
