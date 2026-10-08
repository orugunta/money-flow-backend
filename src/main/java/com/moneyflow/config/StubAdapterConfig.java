package com.moneyflow.config;

import com.moneyflow.adapter.client.balance.LoggingSummaryPublisher;
import com.moneyflow.adapter.client.statement.StubTransactionSource;
import com.moneyflow.service.SummaryPublisher;
import com.moneyflow.service.TransactionSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import tools.jackson.databind.json.JsonMapper;

/** Stand-ins for both external APIs, so the app runs end to end before they exist. */
@Configuration(proxyBeanMethods = false)
@Profile("stub")
class StubAdapterConfig {

    @Bean
    TransactionSource stubTransactionSource(JsonMapper jsonMapper) {
        return new StubTransactionSource(jsonMapper);
    }

    @Bean
    SummaryPublisher loggingSummaryPublisher() {
        return new LoggingSummaryPublisher();
    }
}
