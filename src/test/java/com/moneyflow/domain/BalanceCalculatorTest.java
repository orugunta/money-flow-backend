package com.moneyflow.domain;

import static com.moneyflow.domain.TestTransactions.credit;
import static com.moneyflow.domain.TestTransactions.debit;
import static com.moneyflow.domain.TestTransactions.history;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.Test;

class BalanceCalculatorTest {

    private final BalanceCalculator calculator = new BalanceCalculator();

    @Test
    void normalMonth() {
        List<MonthlySummary> summaries = calculator.summarize(history(
                credit("t1", "2026-07-01", "3000.00"),
                debit("t2", "2026-07-15", "1000.00")));

        assertThat(summaries).containsExactly(summary("2026-07", "3000.00", "1000.00", "2000.00"));
    }

    @Test
    void spendingGreaterThanIncomeGivesNegativeBalance() {
        List<MonthlySummary> summaries = calculator.summarize(history(
                credit("t1", "2026-07-01", "500.00"),
                debit("t2", "2026-07-02", "800.00")));

        assertThat(summaries).containsExactly(summary("2026-07", "500.00", "800.00", "-300.00"));
    }

    @Test
    void onlySpendingInAMonth() {
        List<MonthlySummary> summaries = calculator.summarize(history(debit("t1", "2026-07-02", "800.00")));

        assertThat(summaries).containsExactly(summary("2026-07", "0.00", "800.00", "-800.00"));
    }

    @Test
    void monthsAreCalculatedIndependently() {
        List<MonthlySummary> summaries = calculator.summarize(history(
                credit("t1", "2026-07-01", "3000.00"),
                debit("t2", "2026-07-31", "1000.00"),
                credit("t3", "2026-08-01", "2500.00")));

        // August does not start from July's 2000.00.
        assertThat(summaries).containsExactly(
                summary("2026-07", "3000.00", "1000.00", "2000.00"),
                summary("2026-08", "2500.00", "0.00", "2500.00"));
    }

    @Test
    void sumsAllTransactionsOfEachDirectionInAMonth() {
        List<MonthlySummary> summaries = calculator.summarize(history(
                credit("t1", "2026-07-01", "1000.00"),
                credit("t2", "2026-07-10", "250.50"),
                debit("t3", "2026-07-11", "100.25"),
                debit("t4", "2026-07-20", "49.75")));

        assertThat(summaries).containsExactly(summary("2026-07", "1250.50", "150.00", "1100.50"));
    }

    @Test
    void monthBoundaryUsesBookingDate() {
        List<MonthlySummary> summaries = calculator.summarize(history(
                debit("t1", "2026-07-31", "10.00"),
                debit("t2", "2026-08-01", "20.00")));

        assertThat(summaries).extracting(MonthlySummary::month, MonthlySummary::totalSpending).containsExactly(
                tuple(YearMonth.of(2026, 7), new BigDecimal("10.00")),
                tuple(YearMonth.of(2026, 8), new BigDecimal("20.00")));
    }

    @Test
    void monthsAreOrderedOldestFirstRegardlessOfInputOrder() {
        List<MonthlySummary> summaries = calculator.summarize(history(
                credit("t1", "2026-09-01", "1.00"),
                credit("t2", "2025-12-31", "1.00"),
                credit("t3", "2026-01-15", "1.00")));

        assertThat(summaries).extracting(MonthlySummary::month)
                .containsExactly(YearMonth.of(2025, 12), YearMonth.of(2026, 1), YearMonth.of(2026, 9));
    }

    @Test
    void monthsWithoutTransactionsAreNotProduced() {
        List<MonthlySummary> summaries = calculator.summarize(history(
                credit("t1", "2026-07-01", "1.00"),
                credit("t2", "2026-09-01", "1.00")));

        assertThat(summaries).extracting(MonthlySummary::month)
                .containsExactly(YearMonth.of(2026, 7), YearMonth.of(2026, 9));
    }

    @Test
    void noTransactionsGiveNoSummaries() {
        assertThat(calculator.summarize(history())).isEmpty();
    }

    @Test
    void totalsUseTheCurrencyScale() {
        AccountHistory yen = new AccountHistory(
                "ACC-JP", Currency.getInstance("JPY"), List.of(credit("t1", "2026-07-01", "5000")));

        MonthlySummary summary = calculator.summarize(yen).getFirst();

        assertThat(summary.currency()).isEqualTo("JPY");
        assertThat(summary.totalIncome()).isEqualTo(new BigDecimal("5000"));
        assertThat(summary.totalSpending()).isEqualTo(new BigDecimal("0"));
    }

    private static MonthlySummary summary(String month, String income, String spending, String balance) {
        // BigDecimal.equals compares scale too, so these also assert the 2-decimal EUR format.
        return new MonthlySummary(
                "ACC-123",
                YearMonth.parse(month),
                "EUR",
                new BigDecimal(income),
                new BigDecimal(spending),
                new BigDecimal(balance));
    }
}
