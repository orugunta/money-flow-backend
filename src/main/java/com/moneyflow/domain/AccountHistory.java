package com.moneyflow.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * All transactions of one account. The account holds a single currency, which all amounts are in. Amounts are
 * normalized to the currency's number of decimal places (EUR 2), so totals are exact and consistently formatted.
 */
public record AccountHistory(String accountId, Currency currency, List<Transaction> transactions) {

    public AccountHistory {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(currency, "currency");
        int fractionDigits = currency.getDefaultFractionDigits();
        if (fractionDigits < 0) {
            throw new IllegalArgumentException("currency " + currency + " has no minor unit and is not supported");
        }
        Objects.requireNonNull(transactions, "transactions");
        Set<String> ids = new HashSet<>();
        List<Transaction> normalized = new ArrayList<>(transactions.size());
        for (Transaction transaction : transactions) {
            Objects.requireNonNull(transaction, "transaction");
            if (!ids.add(transaction.id())) {
                throw new IllegalArgumentException("duplicate transaction id " + transaction.id());
            }
            normalized.add(transaction.withAmount(toCurrencyScale(transaction, fractionDigits)));
        }
        transactions = List.copyOf(normalized);
    }

    /** Exact, no rounding: rejects amounts with more decimal places than the currency allows. */
    private static BigDecimal toCurrencyScale(Transaction transaction, int fractionDigits) {
        BigDecimal amount = transaction.amount();
        if (amount.signum() == 0) {
            return BigDecimal.ZERO.setScale(fractionDigits);
        }
        BigDecimal stripped = amount.stripTrailingZeros();
        if (stripped.scale() > fractionDigits) {
            // toString(), not toPlainString(): an amount like 1E-999999999 would expand to a billion characters.
            throw new IllegalArgumentException("amount %s has more than %d decimal places in transaction %s"
                    .formatted(amount, fractionDigits, transaction.id()));
        }
        return stripped.setScale(fractionDigits);
    }
}
