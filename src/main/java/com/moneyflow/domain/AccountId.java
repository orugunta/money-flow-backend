package com.moneyflow.domain;

import java.util.regex.Pattern;

/** The accepted account id format, shared by every entry point and adapter that handles raw ids. */
public final class AccountId {

    public static final String PATTERN = "[A-Za-z0-9-]{1,64}";

    private static final Pattern COMPILED = Pattern.compile(PATTERN);

    private AccountId() {}

    public static boolean isValid(String accountId) {
        return accountId != null && COMPILED.matcher(accountId).matches();
    }
}
