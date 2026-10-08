package com.moneyflow.config;

import com.moneyflow.service.MonthlyBalanceService;
import com.moneyflow.service.SummaryPublisher;
import com.moneyflow.service.TransactionSource;
import com.moneyflow.domain.BalanceCalculator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the domain and application layers, which stay free of Spring annotations. */
@Configuration(proxyBeanMethods = false)
class ApplicationConfig {

    @Bean
    BalanceCalculator balanceCalculator() {
        return new BalanceCalculator();
    }

    @Bean
    MonthlyBalanceService monthlyBalanceService(
            TransactionSource transactionSource, BalanceCalculator balanceCalculator, SummaryPublisher summaryPublisher) {
        return new MonthlyBalanceService(transactionSource, balanceCalculator, summaryPublisher);
    }
}
