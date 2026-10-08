package com.moneyflow.adapter.client.balance;

import com.moneyflow.adapter.client.TransientFailureRetry;
import com.moneyflow.service.SummaryPublisher;
import com.moneyflow.service.UpstreamException;
import com.moneyflow.domain.MonthlySummary;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Sends one PUT per account and month to the Balance API. */
public class HttpSummaryPublisher implements SummaryPublisher {

    private final RestClient restClient;
    private final TransientFailureRetry retry;

    /**
     * @param restClient configured with the Balance API base URL, credentials and timeouts
     * @param retry applied to each PUT, which is safe to repeat because it replaces the month's summary
     */
    public HttpSummaryPublisher(RestClient restClient, TransientFailureRetry retry) {
        this.restClient = restClient;
        this.retry = retry;
    }

    @Override
    public void publish(MonthlySummary summary) {
        try {
            retry.call(() -> restClient.put()
                    .uri("/monthly-balances/{accountId}/{month}", summary.accountId(), summary.month().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(MonthlyBalanceRequest.from(summary))
                    .retrieve()
                    .toBodilessEntity());
        } catch (RestClientException e) {
            throw new UpstreamException(
                    "Balance API request failed for account %s, month %s".formatted(summary.accountId(), summary.month()),
                    e);
        }
    }
}
