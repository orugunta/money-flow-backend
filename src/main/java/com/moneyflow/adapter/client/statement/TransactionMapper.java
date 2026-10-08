package com.moneyflow.adapter.client.statement;

import com.moneyflow.adapter.client.statement.TransactionsResponse.TransactionDto;
import com.moneyflow.service.UpstreamException;
import com.moneyflow.domain.AccountHistory;
import com.moneyflow.domain.Direction;
import com.moneyflow.domain.Transaction;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Currency;
import java.util.List;

/**
 * Parses the Statement API response into the domain. Anything the response or the domain rules reject becomes an
 * {@link UpstreamException}.
 */
class TransactionMapper {

    AccountHistory toDomain(String requestedAccountId, TransactionsResponse response) {
        if (!requestedAccountId.equals(response.accountId())) {
            throw invalid("accountId '%s' does not match requested account %s"
                    .formatted(response.accountId(), requestedAccountId));
        }
        Currency currency = parseCurrency(response.currency());
        List<TransactionDto> dtos = response.transactions();
        if (dtos == null) {
            // Required by the contract; treating it as empty would report a changed or broken response as success.
            throw invalid("transactions is missing");
        }
        try {
            return new AccountHistory(
                    response.accountId(), currency, dtos.stream().map(TransactionMapper::toDomain).toList());
        } catch (IllegalArgumentException e) {
            throw invalid(e.getMessage());
        }
    }

    private static Currency parseCurrency(String code) {
        if (code == null) {
            throw invalid("currency is missing");
        }
        try {
            return Currency.getInstance(code);
        } catch (IllegalArgumentException e) {
            throw invalid("currency '%s' is not an ISO 4217 code".formatted(code));
        }
    }

    private static Transaction toDomain(TransactionDto dto) {
        if (dto == null) {
            throw invalid("transaction is null");
        }
        if (dto.id() == null || dto.id().isBlank()) {
            throw invalid("transaction id is missing");
        }
        if (dto.amount() == null) {
            throw invalid("amount is missing for transaction " + dto.id());
        }
        return new Transaction(dto.id(), parseDate(dto), dto.amount(), parseDirection(dto));
    }

    private static LocalDate parseDate(TransactionDto dto) {
        if (dto.bookingDate() == null) {
            throw invalid("bookingDate is missing for transaction " + dto.id());
        }
        LocalDate date;
        try {
            date = LocalDate.parse(dto.bookingDate());
        } catch (DateTimeParseException e) {
            throw invalid("bookingDate '%s' is invalid for transaction %s".formatted(dto.bookingDate(), dto.id()));
        }
        // ISO parsing accepts years like +10000 or -0001, which don't fit the Balance API's yyyy-MM month.
        if (date.getYear() < 1 || date.getYear() > 9999) {
            throw invalid("bookingDate '%s' is out of range for transaction %s".formatted(dto.bookingDate(), dto.id()));
        }
        return date;
    }

    private static Direction parseDirection(TransactionDto dto) {
        if ("CREDIT".equals(dto.type())) {
            return Direction.CREDIT;
        }
        if ("DEBIT".equals(dto.type())) {
            return Direction.DEBIT;
        }
        throw invalid("type '%s' is invalid for transaction %s".formatted(dto.type(), dto.id()));
    }

    private static UpstreamException invalid(String reason) {
        return new UpstreamException("Statement API returned invalid data: " + reason);
    }
}
