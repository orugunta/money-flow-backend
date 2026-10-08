package com.moneyflow.adapter.client.statement;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;

/** Wire format of {@code GET /accounts/{accountId}/transactions}. Validated by {@link TransactionMapper}. */
@JsonIgnoreProperties(ignoreUnknown = true)
record TransactionsResponse(String accountId, String currency, List<TransactionDto> transactions) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TransactionDto(String id, String bookingDate, BigDecimal amount, String type) {}
}
