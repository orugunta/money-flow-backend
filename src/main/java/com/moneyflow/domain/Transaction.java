package com.moneyflow.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A single booked transaction. The amount is always non-negative; {@link Direction} decides whether it is
 * income (CREDIT) or spending (DEBIT).
 */
public record Transaction(String id, LocalDate bookingDate, BigDecimal amount, Direction direction) {

    // Far above any real amount; also stops values like 1E+999999999 from being expanded when rescaled.
    private static final int MAX_INTEGER_DIGITS = 18;

    public Transaction {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(bookingDate, "bookingDate");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(direction, "direction");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("amount is negative for transaction " + id);
        }
        if (amount.signum() != 0 && amount.precision() - amount.scale() > MAX_INTEGER_DIGITS) {
            throw new IllegalArgumentException("amount is too large for transaction " + id);
        }
    }

    Transaction withAmount(BigDecimal newAmount) {
        return new Transaction(id, bookingDate, newAmount, direction);
    }
}
