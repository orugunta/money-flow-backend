package com.moneyflow.domain;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Objects;

/** Income, spending and balance (income − spending) of one account for one month. */
public record MonthlySummary(
        String accountId,
        YearMonth month,
        String currency,
        BigDecimal totalIncome,
        BigDecimal totalSpending,
        BigDecimal balance) {

    public MonthlySummary {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(month, "month");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(totalIncome, "totalIncome");
        Objects.requireNonNull(totalSpending, "totalSpending");
        Objects.requireNonNull(balance, "balance");
    }
}
