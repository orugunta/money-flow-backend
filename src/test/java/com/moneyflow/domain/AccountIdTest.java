package com.moneyflow.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class AccountIdTest {

    @ParameterizedTest
    @ValueSource(strings = {"ACC-123", "a", "0123456789012345678901234567890123456789012345678901234567890123"})
    void acceptsLettersDigitsAndHyphensUpTo64Characters(String accountId) {
        assertThat(AccountId.isValid(accountId)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
        "../application", "ACC_123", "ACC 123", "ACC/123",
        "01234567890123456789012345678901234567890123456789012345678901234"
    })
    void rejectsEverythingElse(String accountId) {
        assertThat(AccountId.isValid(accountId)).isFalse();
    }
}
