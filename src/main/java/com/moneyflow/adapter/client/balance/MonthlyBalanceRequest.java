package com.moneyflow.adapter.client.balance;

import com.moneyflow.domain.MonthlySummary;
import java.math.BigDecimal;

/**
 * Wire format of {@code PUT /monthly-balances/{accountId}/{yyyy-MM}}. Deliberately separate from our own response
 * type, so the external contract and our API can change independently.
 */
record MonthlyBalanceRequest(
        String accountId,
        String month,
        String currency,
        BigDecimal totalIncome,
        BigDecimal totalSpending,
        BigDecimal balance) {

    static MonthlyBalanceRequest from(MonthlySummary summary) {
        return new MonthlyBalanceRequest(
                summary.accountId(),
                summary.month().toString(),
                summary.currency(),
                summary.totalIncome(),
                summary.totalSpending(),
                summary.balance());
    }
}
