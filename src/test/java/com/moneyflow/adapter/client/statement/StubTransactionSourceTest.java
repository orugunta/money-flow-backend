package com.moneyflow.adapter.client.statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moneyflow.service.AccountNotFoundException;
import com.moneyflow.domain.AccountHistory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class StubTransactionSourceTest {

    private final StubTransactionSource source = new StubTransactionSource(JsonMapper.builder().build());

    @Test
    void servesTheBundledFixture() {
        AccountHistory history = source.fetch("ACC-123");

        assertThat(history.accountId()).isEqualTo("ACC-123");
        assertThat(history.transactions()).hasSize(3);
    }

    @ParameterizedTest
    // "acc-123" resolves ACC-123.json on case-insensitive file systems but is a different account.
    @ValueSource(strings = {"NOPE", "acc-123", "../application", "ACC 123"})
    void reportsAccountsWithoutAnExactFixtureAsNotFound(String accountId) {
        assertThatThrownBy(() -> source.fetch(accountId)).isInstanceOf(AccountNotFoundException.class);
    }
}
