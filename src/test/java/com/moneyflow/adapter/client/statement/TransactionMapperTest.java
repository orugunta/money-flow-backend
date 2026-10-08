package com.moneyflow.adapter.client.statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moneyflow.adapter.client.statement.TransactionsResponse.TransactionDto;
import com.moneyflow.service.UpstreamException;
import com.moneyflow.domain.AccountHistory;
import com.moneyflow.domain.Direction;
import com.moneyflow.domain.Transaction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class TransactionMapperTest {

    private final TransactionMapper mapper = new TransactionMapper();

    @Test
    void mapsAValidResponse() {
        AccountHistory history = mapper.toDomain("ACC-123", response("EUR",
                new TransactionDto("t1", "2026-07-01", new BigDecimal("3000"), "CREDIT"),
                new TransactionDto("t2", "2026-07-31", new BigDecimal("1000.00"), "DEBIT")));

        assertThat(history.accountId()).isEqualTo("ACC-123");
        assertThat(history.currency()).isEqualTo(Currency.getInstance("EUR"));
        assertThat(history.transactions()).containsExactly(
                new Transaction("t1", LocalDate.of(2026, 7, 1), new BigDecimal("3000.00"), Direction.CREDIT),
                new Transaction("t2", LocalDate.of(2026, 7, 31), new BigDecimal("1000.00"), Direction.DEBIT));
    }

    @Test
    void rejectsMissingTransactionList() {
        assertInvalid(new TransactionsResponse("ACC-123", "EUR", null), "transactions is missing");
    }

    @Test
    void acceptsAnEmptyTransactionList() {
        assertThat(mapper.toDomain("ACC-123", response("EUR")).transactions()).isEmpty();
    }

    @Test
    void rejectsAResponseForAnotherAccount() {
        assertThatThrownBy(() -> mapper.toDomain("ACC-123", new TransactionsResponse("ACC-999", "EUR", List.of())))
                .isInstanceOf(UpstreamException.class)
                .hasMessage("Statement API returned invalid data: "
                        + "accountId 'ACC-999' does not match requested account ACC-123");
    }

    @ParameterizedTest
    @ValueSource(strings = {"euro", " EUR", "XYZ", ""})
    void rejectsCodesThatAreNotIso4217(String currency) {
        assertInvalid(response(currency), "is not an ISO 4217 code");
    }

    @Test
    void rejectsMissingCurrency() {
        assertInvalid(response(null), "currency is missing");
    }

    @Test
    void rejectsNullTransaction() {
        assertInvalid(response("EUR", (TransactionDto) null), "transaction is null");
    }

    @Test
    void rejectsNullId() {
        assertInvalid(response("EUR", new TransactionDto(null, "2026-07-01", BigDecimal.ONE, "CREDIT")),
                "transaction id is missing");
    }

    @Test
    void rejectsMissingId() {
        assertInvalid(response("EUR", new TransactionDto(" ", "2026-07-01", BigDecimal.ONE, "CREDIT")),
                "transaction id is missing");
    }

    @Test
    void rejectsMissingAmount() {
        assertInvalid(response("EUR", new TransactionDto("t1", "2026-07-01", null, "CREDIT")),
                "amount is missing for transaction t1");
    }

    @Test
    void rejectsMissingBookingDate() {
        assertInvalid(response("EUR", new TransactionDto("t1", null, BigDecimal.ONE, "CREDIT")),
                "bookingDate is missing for transaction t1");
    }

    @ParameterizedTest
    @CsvSource({"2026-13-01, is invalid", "01.07.2026, is invalid", "+10000-01-01, is out of range",
        "-0001-03-01, is out of range", "0000-01-01, is out of range"})
    void rejectsUnusableBookingDates(String bookingDate, String reason) {
        assertInvalid(response("EUR", new TransactionDto("t1", bookingDate, BigDecimal.ONE, "CREDIT")),
                "bookingDate '%s' %s for transaction t1".formatted(bookingDate, reason));
    }

    @ParameterizedTest
    @ValueSource(strings = {"credit", "TRANSFER", ""})
    void rejectsUnknownTypes(String type) {
        assertInvalid(response("EUR", new TransactionDto("t1", "2026-07-01", BigDecimal.ONE, type)),
                "type '%s' is invalid for transaction t1".formatted(type));
    }

    @Test
    void rejectsNullType() {
        assertInvalid(response("EUR", new TransactionDto("t1", "2026-07-01", BigDecimal.ONE, null)),
                "type 'null' is invalid for transaction t1");
    }

    @Test
    void turnsDomainRuleViolationsIntoUpstreamExceptions() {
        assertInvalid(response("EUR", new TransactionDto("t1", "2026-07-01", new BigDecimal("-1"), "CREDIT")),
                "amount is negative for transaction t1");
        assertInvalid(response("EUR", new TransactionDto("t1", "2026-07-01", new BigDecimal("10.005"), "CREDIT")),
                "more than 2 decimal places");
        assertInvalid(response("EUR",
                        new TransactionDto("t1", "2026-07-01", BigDecimal.ONE, "CREDIT"),
                        new TransactionDto("t1", "2026-07-02", BigDecimal.ONE, "DEBIT")),
                "duplicate transaction id t1");
        assertInvalid(response("XAU"), "no minor unit");
    }

    @Test
    void keepsTheRawUpstreamValueInTheMessage() {
        // Sanitizing happens where the message is logged (ApiExceptionHandler), not here.
        TransactionsResponse response = response("EUR",
                new TransactionDto("t1", "2026-07-01", BigDecimal.ONE, "X\nY"));

        assertThatThrownBy(() -> mapper.toDomain("ACC-123", response))
                .hasMessage("Statement API returned invalid data: type 'X\nY' is invalid for transaction t1");
    }

    private void assertInvalid(TransactionsResponse response, String reason) {
        assertThatThrownBy(() -> mapper.toDomain("ACC-123", response))
                .isInstanceOf(UpstreamException.class)
                .hasMessageStartingWith("Statement API returned invalid data: ")
                .hasMessageContaining(reason);
    }

    private static TransactionsResponse response(String currency, TransactionDto... transactions) {
        return new TransactionsResponse("ACC-123", currency, Arrays.asList(transactions));
    }
}
