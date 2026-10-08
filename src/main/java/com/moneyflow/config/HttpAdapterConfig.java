package com.moneyflow.config;

import java.net.http.HttpClient;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.moneyflow.adapter.client.TransientFailureRetry;
import com.moneyflow.adapter.client.balance.HttpSummaryPublisher;
import com.moneyflow.adapter.client.statement.HttpTransactionSource;
import com.moneyflow.service.SummaryPublisher;
import com.moneyflow.service.TransactionSource;

/** Real HTTP adapters for both external APIs. Active unless the {@code stub} profile is on. */
@Configuration(proxyBeanMethods = false)
@Profile("!stub")
@EnableConfigurationProperties(ApiClientProperties.class)
class HttpAdapterConfig {

    // Header the external APIs expect. Same name as the one callers send us (ApiKeyAuthenticationFilter), but a
    // separate contract, so each side can change without the other.
    static final String API_KEY_HEADER = "X-API-Key";

    // HttpClient beans so Spring closes them (and their selector threads and connections) on shutdown.
    @Bean
    HttpClient statementApiHttpClient(ApiClientProperties properties) {
        return httpClient(properties.statementApi());
    }

    @Bean
    HttpClient balanceApiHttpClient(ApiClientProperties properties) {
        return httpClient(properties.balanceApi());
    }

    @Bean
    TransactionSource httpTransactionSource(
            RestClient.Builder builder,
            ApiClientProperties properties,
            @Qualifier("statementApiHttpClient") HttpClient httpClient) {
        return new HttpTransactionSource(
                restClient(builder, properties.statementApi(), httpClient, false),
                transientFailureRetry("Statement API", properties.statementApi()));
    }

    @Bean
    SummaryPublisher httpSummaryPublisher(
            RestClient.Builder builder,
            ApiClientProperties properties,
            @Qualifier("balanceApiHttpClient") HttpClient httpClient) {
        // Buffered so each PUT carries Content-Length instead of chunked encoding, which some gateways reject.
        return new HttpSummaryPublisher(
                restClient(builder, properties.balanceApi(), httpClient, true),
                transientFailureRetry("Balance API", properties.balanceApi()));
    }

    private static TransientFailureRetry transientFailureRetry(String api, ApiClientProperties.Client client) {
        return new TransientFailureRetry(api, client.maxRetries(), client.retryDelay());
    }

    private static HttpClient httpClient(ApiClientProperties.Client client) {
        return HttpClient.newBuilder()
                // HTTP/2 is the JDK default; over plain http it adds an h2c upgrade that some proxies reject.
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(client.connectTimeout())
                .build();
    }

    private static RestClient restClient(
            RestClient.Builder builder,
            ApiClientProperties.Client client,
            HttpClient httpClient,
            boolean bufferRequests) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(client.readTimeout());

        return builder.clone()
                .baseUrl(client.baseUrl().toString())
                .defaultHeader(API_KEY_HEADER, client.apiKey())
                .requestFactory(bufferRequests ? new BufferingClientHttpRequestFactory(requestFactory) : requestFactory)
                // The JDK client doesn't follow redirects and RestClient only fails on 4xx/5xx, so without this a 3xx
                // would look like success: a PUT that stored nothing, or a GET whose redirect body is parsed.
                .defaultStatusHandler(HttpStatusCode::is3xxRedirection, (request, response) -> {
                    throw new RestClientException("Unexpected redirect %d for %s %s"
                            .formatted(response.getStatusCode().value(), request.getMethod(), request.getURI()));
                })
                .build();
    }
}
