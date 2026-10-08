package com.moneyflow.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.moneyflow.service.SummaryPublisher;
import com.moneyflow.service.TransactionSource;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

class HttpAdapterConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(HttpAdapterConfig.class)
            .withBean(RestClient.Builder.class, RestClient::builder);

    @Test
    void createsBothAdaptersWithHttp11ClientsUsingTheConfiguredTimeouts() {
        runner.withPropertyValues(
                        "moneyflow.statement-api.base-url=http://statement.local",
                        "moneyflow.statement-api.api-key=s",
                        "moneyflow.statement-api.connect-timeout=3s",
                        "moneyflow.balance-api.base-url=http://balance.local",
                        "moneyflow.balance-api.api-key=b")
                .run(context -> {
                    assertThat(context).hasSingleBean(TransactionSource.class).hasSingleBean(SummaryPublisher.class);
                    HttpClient statement = context.getBean("statementApiHttpClient", HttpClient.class);
                    HttpClient balance = context.getBean("balanceApiHttpClient", HttpClient.class);
                    assertThat(statement.version()).isEqualTo(HttpClient.Version.HTTP_1_1);
                    assertThat(statement.connectTimeout()).contains(Duration.ofSeconds(3));
                    assertThat(balance.connectTimeout()).contains(Duration.ofSeconds(2));
                });
    }

    @Test
    void closesTheHttpClientsWithTheContext() {
        AtomicReference<HttpClient> client = new AtomicReference<>();

        runner.withPropertyValues(
                        "moneyflow.statement-api.base-url=http://statement.local",
                        "moneyflow.statement-api.api-key=s",
                        "moneyflow.balance-api.base-url=http://balance.local",
                        "moneyflow.balance-api.api-key=b")
                .run(context -> client.set(context.getBean("statementApiHttpClient", HttpClient.class)));

        assertThat(client.get().isTerminated()).isTrue();
    }

    @Test
    void failsToStartWithoutBaseUrlsAndKeys() {
        runner.run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                .rootCause()
                .hasMessageContaining("moneyflow"));
    }
}
