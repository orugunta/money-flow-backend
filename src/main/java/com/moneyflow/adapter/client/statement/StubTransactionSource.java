package com.moneyflow.adapter.client.statement;

import com.moneyflow.service.AccountNotFoundException;
import com.moneyflow.service.TransactionSource;
import com.moneyflow.service.UpstreamException;
import com.moneyflow.domain.AccountHistory;
import com.moneyflow.domain.AccountId;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Serves canned Statement API responses from {@code classpath:stubs/transactions/{accountId}.json}, so the app
 * runs without the real API. An account without a fixture is reported as not found.
 */
public class StubTransactionSource implements TransactionSource {

    private static final String FIXTURE_LOCATION = "stubs/transactions/%s.json";

    private final JsonMapper jsonMapper;
    private final TransactionMapper mapper = new TransactionMapper();

    public StubTransactionSource(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public AccountHistory fetch(String accountId) {
        // The id becomes part of a classpath path, so only well-formed ids may reach the lookup.
        if (!AccountId.isValid(accountId)) {
            throw new AccountNotFoundException(accountId);
        }
        ClassPathResource fixture = new ClassPathResource(FIXTURE_LOCATION.formatted(accountId));
        if (!fixture.exists()) {
            throw new AccountNotFoundException(accountId);
        }
        TransactionsResponse response;
        try (InputStream in = fixture.getInputStream()) {
            response = jsonMapper.readValue(in, TransactionsResponse.class);
        } catch (IOException | JacksonException e) {
            throw new UpstreamException("Could not read stub fixture for account " + accountId, e);
        }
        if (response == null) {
            throw new UpstreamException("Stub fixture for account " + accountId + " is empty");
        }
        // Case-insensitive file systems (macOS) resolve ACC-123.json for "acc-123"; that account does not exist.
        if (!accountId.equals(response.accountId())) {
            throw new AccountNotFoundException(accountId);
        }
        return mapper.toDomain(accountId, response);
    }
}
