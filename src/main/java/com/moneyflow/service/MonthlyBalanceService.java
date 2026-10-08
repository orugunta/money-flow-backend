package com.moneyflow.service;

import com.moneyflow.domain.AccountHistory;
import com.moneyflow.domain.BalanceCalculator;
import com.moneyflow.domain.MonthlySummary;
import java.util.List;

/** Use case: fetch an account's transactions, summarize them per month and publish each month's summary. */
public class MonthlyBalanceService {

    private final TransactionSource transactionSource;
    private final BalanceCalculator calculator;
    private final SummaryPublisher summaryPublisher;

    public MonthlyBalanceService(
            TransactionSource transactionSource, BalanceCalculator calculator, SummaryPublisher summaryPublisher) {
        this.transactionSource = transactionSource;
        this.calculator = calculator;
        this.summaryPublisher = summaryPublisher;
    }

    /**
     * Publishes months oldest first and stops at the first failure. Re-running is safe because the publisher
     * replaces months that were already sent.
     */
    public List<MonthlySummary> calculateAndPublish(String accountId) {
        AccountHistory history = transactionSource.fetch(accountId);
        List<MonthlySummary> summaries = calculator.summarize(history);
        summaries.forEach(summaryPublisher::publish);
        return summaries;
    }
}
