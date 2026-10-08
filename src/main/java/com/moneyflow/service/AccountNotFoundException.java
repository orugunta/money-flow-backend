package com.moneyflow.service;

public class AccountNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AccountNotFoundException(String accountId) {
        super("Account not found: " + accountId);
    }
}
