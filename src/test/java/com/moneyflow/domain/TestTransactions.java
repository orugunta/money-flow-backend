package com.moneyflow.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;

/** Builders for readable domain fixtures. */
public final class TestTransactions {

    public static final Currency EUR = Currency.getInstance("EUR");

    private TestTransactions() {}

    public static Transaction credit(String id, String date, String amount) {
        return new Transaction(id, LocalDate.parse(date), new BigDecimal(amount), Direction.CREDIT);
    }

    public static Transaction debit(String id, String date, String amount) {
        return new Transaction(id, LocalDate.parse(date), new BigDecimal(amount), Direction.DEBIT);
    }

    public static AccountHistory history(Transaction... transactions) {
        return new AccountHistory("ACC-123", EUR, List.of(transactions));
    }
}
