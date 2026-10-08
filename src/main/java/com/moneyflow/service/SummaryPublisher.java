package com.moneyflow.service;

import com.moneyflow.domain.MonthlySummary;

/** Port to the external Balance API. Publishing the same account and month again replaces the earlier value. */
public interface SummaryPublisher {

    /** @throws UpstreamException if the summary could not be delivered */
    void publish(MonthlySummary summary);
}
