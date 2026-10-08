package com.moneyflow.adapter.controller;

import com.moneyflow.domain.MonthlySummary;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/**
 * One month in the response body of our trigger endpoint. Deliberately separate from the Balance API request, so our
 * API and the external contract can change independently.
 */
record MonthlySummaryResponse(
        @Schema(example = "ACC-123") String accountId,
        @Schema(description = "Booking month, yyyy-MM", example = "2026-07") String month,
        @Schema(description = "ISO 4217 code", example = "EUR") String currency,
        @Schema(description = "Sum of CREDIT amounts", example = "3000.00") BigDecimal totalIncome,
        @Schema(description = "Sum of DEBIT amounts", example = "1000.00") BigDecimal totalSpending,
        @Schema(description = "totalIncome - totalSpending; negative when spending is higher", example = "2000.00")
                BigDecimal balance) {

    static MonthlySummaryResponse from(MonthlySummary summary) {
        return new MonthlySummaryResponse(
                summary.accountId(),
                summary.month().toString(),
                summary.currency(),
                summary.totalIncome(),
                summary.totalSpending(),
                summary.balance());
    }
}
