package com.moneyflow.adapter.client.statement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.moneyflow.adapter.client.TransientFailureRetry;
import com.moneyflow.domain.AccountHistory;
import com.moneyflow.service.AccountNotFoundException;
import com.moneyflow.service.TransactionSource;
import com.moneyflow.service.UpstreamException;

/** Fetches transactions from the Statement API over HTTP. */
public class HttpTransactionSource implements TransactionSource {

    private static final Logger log = LoggerFactory.getLogger(HttpTransactionSource.class);

    private final RestClient restClient;
    private final TransientFailureRetry transientFailureRetry;
    private final TransactionMapper mapper = new TransactionMapper();

    /**
     * @param restClient configured with the Statement API base URL, credentials and timeouts
     * @param transientFailureRetry applied to the GET, which is safe to repeat
     */
    public HttpTransactionSource(RestClient restClient, TransientFailureRetry transientFailureRetry) {
        this.restClient = restClient;
        this.transientFailureRetry = transientFailureRetry;
    }

    @Override
    public AccountHistory fetch(String accountId) {
        TransactionsResponse response;
        try {
            response = transientFailureRetry.call(() -> restClient.get()
                    .uri("/accounts/{accountId}/transactions", accountId)
                    .retrieve()
                    .onStatus(status -> status.isSameCodeAs(HttpStatus.NOT_FOUND), (request, ignored) -> {
                        // The contract defines 404 as "unknown account". The URL is logged so that a misconfigured
                        // base URL, which also returns 404 for every account, is visible.
                        log.info("Statement API returned 404 for {}, reporting account as not found", request.getURI());
                        throw new AccountNotFoundException(accountId);
                    })
                    .body(TransactionsResponse.class));
        } catch (RestClientException e) {
            throw new UpstreamException("Statement API request failed for account " + accountId, e);
        }
        if (response == null) {
            throw new UpstreamException("Statement API returned an empty body for account " + accountId);
        }
        return mapper.toDomain(accountId, response);
    }
}
