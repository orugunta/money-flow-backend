package com.moneyflow.adapter.controller;

import com.moneyflow.service.MonthlyBalanceService;
import com.moneyflow.domain.AccountId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Balances")
class BalanceController {

    private static final String PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE;

    private final MonthlyBalanceService monthlyBalanceService;

    BalanceController(MonthlyBalanceService monthlyBalanceService) {
        this.monthlyBalanceService = monthlyBalanceService;
    }

    /** Fetches the account's transactions, calculates every month, sends each summary and returns them. */
    @PostMapping("/{accountId}/balances")
    @Operation(
            summary = "Calculate and send monthly balances",
            description = "Fetches all of the account's transactions, groups them by booking month, calculates "
                    + "income, spending and balance (income - spending) per month, sends each month to the Balance "
                    + "API and returns the summaries, oldest month first. Safe to re-run: each month with "
                    + "transactions is sent again and replaces the previous summary. A month whose transactions "
                    + "have all disappeared since an earlier run is not cleared.")
    @ApiResponse(responseCode = "200", description = "All months calculated and sent", content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            array = @ArraySchema(schema = @Schema(implementation = MonthlySummaryResponse.class))))
    @ApiResponse(responseCode = "400", description = "Invalid account id",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "X-API-Key header missing or wrong",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "The Statement API doesn't know the account",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "500", description = "Unexpected error",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "502", description = "An external API failed, timed out or returned invalid data",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    List<MonthlySummaryResponse> calculateAndPublish(
            @Parameter(description = "Account id", example = "ACC-123")
                    @PathVariable
                    @Pattern(regexp = AccountId.PATTERN)
                    String accountId) {
        return monthlyBalanceService.calculateAndPublish(accountId).stream()
                .map(MonthlySummaryResponse::from)
                .toList();
    }
}
