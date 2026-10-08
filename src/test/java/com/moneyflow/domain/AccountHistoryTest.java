package com.moneyflow.domain;

import static com.moneyflow.domain.TestTransactions.EUR;
import static com.moneyflow.domain.TestTransactions.credit;
import static com.moneyflow.domain.TestTransactions.debit;
import static com.moneyflow.domain.TestTransactions.history;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AccountHistoryTest {

    @ParameterizedTest
    @CsvSource({
        "3000, 3000.00",
        "3000.5, 3000.50",
        "1.0000000000, 1.00",
        "1E+3, 1000.00",
        "0E+19, 0.00",
        "0E-999999999, 0.00",
    })
    void normalizesAmountsToTheCurrencyScale(String input, String expected) {
        AccountHistory history = history(credit("t1", "2026-07-01", input));

        assertThat(history.transactions().getFirst().amount()).isEqualTo(new BigDecimal(expected));
    }

    @Test
    void keepsZeroDecimalCurrenciesWithoutDecimals() {
        AccountHistory history = new AccountHistory(
                "ACC-JP", Currency.getInstance("JPY"), List.of(credit("t1", "2026-07-01", "5000.000")));

        assertThat(history.transactions().getFirst().amount()).isEqualTo(new BigDecimal("5000"));
    }

    @ParameterizedTest
    @CsvSource({"10.005", "0.00000001", "1E-999999999"})
    void rejectsMoreDecimalPlacesThanTheCurrencyAllows(String amount) {
        assertThatThrownBy(() -> history(credit("t1", "2026-07-01", amount)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than 2 decimal places in transaction t1");
    }

    @Test
    void rejectsDuplicateTransactionIds() {
        assertThatThrownBy(() -> history(credit("t1", "2026-07-01", "5"), debit("t1", "2026-07-02", "5")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("duplicate transaction id t1");
    }

    @Test
    void rejectsCurrenciesWithoutMinorUnit() {
        assertThatThrownBy(() -> new AccountHistory("ACC-1", Currency.getInstance("XAU"), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no minor unit");
    }

    @Test
    void requiresTheTransactionListAndItsElements() {
        assertThatThrownBy(() -> new AccountHistory("ACC-1", EUR, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("transactions");
        assertThatThrownBy(() -> new AccountHistory("ACC-1", EUR, Arrays.asList((Transaction) null)))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("transaction");
    }

    @Test
    void transactionListIsImmutable() {
        AccountHistory history = history(credit("t1", "2026-07-01", "5"));

        assertThatThrownBy(() -> history.transactions().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(history.currency()).isEqualTo(EUR);
    }
}
