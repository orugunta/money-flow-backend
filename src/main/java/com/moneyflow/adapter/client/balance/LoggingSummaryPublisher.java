package com.moneyflow.adapter.client.balance;

import com.moneyflow.service.SummaryPublisher;
import com.moneyflow.domain.MonthlySummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Logs summaries instead of sending them, for running without the real Balance API. */
public class LoggingSummaryPublisher implements SummaryPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingSummaryPublisher.class);

    @Override
    public void publish(MonthlySummary summary) {
        log.info("Would PUT /monthly-balances/{}/{}: {}", summary.accountId(), summary.month(), summary);
    }
}
