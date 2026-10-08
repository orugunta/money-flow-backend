package com.moneyflow.domain;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Groups transactions by booking month and calculates each month independently:
 * {@code balance = totalIncome − totalSpending}. No balance is carried between months.
 */
public class BalanceCalculator {

    /** Returns one summary per month that has transactions, ordered oldest first. */
    public List<MonthlySummary> summarize(AccountHistory history) {
        // Amounts already use the currency's scale; starting from the same scale makes an empty sum 0.00, not 0.
        BigDecimal zero = BigDecimal.ZERO.setScale(history.currency().getDefaultFractionDigits());
        TreeMap<YearMonth, List<Transaction>> byMonth = history.transactions().stream()
                .collect(Collectors.groupingBy(
                        transaction -> YearMonth.from(transaction.bookingDate()), TreeMap::new, Collectors.toList()));

        return byMonth.entrySet().stream()
                .map(entry -> summarizeMonth(history, entry.getKey(), entry.getValue(), zero))
                .toList();
    }

    private static MonthlySummary summarizeMonth(
            AccountHistory history, YearMonth month, List<Transaction> transactions, BigDecimal zero) {
        BigDecimal income = sum(transactions, Direction.CREDIT, zero);
        BigDecimal spending = sum(transactions, Direction.DEBIT, zero);
        return new MonthlySummary(
                history.accountId(), month, history.currency().getCurrencyCode(), income, spending, income.subtract(spending));
    }

    private static BigDecimal sum(List<Transaction> transactions, Direction direction, BigDecimal zero) {
        return transactions.stream()
                .filter(transaction -> transaction.direction() == direction)
                .map(Transaction::amount)
                .reduce(zero, BigDecimal::add);
    }
}
