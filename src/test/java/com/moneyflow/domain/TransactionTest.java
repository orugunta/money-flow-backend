package com.moneyflow.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TransactionTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 1);

    @Test
    void rejectsNegativeAmounts() {
        assertThatThrownBy(() -> transaction("-0.01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("amount is negative for transaction t1");
    }

    @ParameterizedTest
    @CsvSource({"1E+999999999", "1234567890123456789"})
    void rejectsAmountsWithMoreThan18IntegerDigits(String amount) {
        assertThatThrownBy(() -> transaction(amount))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("amount is too large for transaction t1");
    }

    @ParameterizedTest
    @CsvSource({"0", "0E+19", "123456789012345678.99"})
    void acceptsZeroAndAmountsUpTo18IntegerDigits(String amount) {
        assertThat(transaction(amount).amount()).isEqualTo(new BigDecimal(amount));
    }

    @Test
    void requiresAllFields() {
        assertThatThrownBy(() -> new Transaction(null, DATE, BigDecimal.ONE, Direction.CREDIT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Transaction("t1", null, BigDecimal.ONE, Direction.CREDIT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Transaction("t1", DATE, null, Direction.CREDIT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Transaction("t1", DATE, BigDecimal.ONE, null))
                .isInstanceOf(NullPointerException.class);
    }

    private static Transaction transaction(String amount) {
        return new Transaction("t1", DATE, new BigDecimal(amount), Direction.CREDIT);
    }
}
