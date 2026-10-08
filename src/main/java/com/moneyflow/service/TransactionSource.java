package com.moneyflow.service;

import com.moneyflow.domain.AccountHistory;

/** Port to the external Statement API. */
public interface TransactionSource {

    /**
     * @throws AccountNotFoundException if the account does not exist
     * @throws UpstreamException if the source fails or returns invalid data
     */
    AccountHistory fetch(String accountId);
}
