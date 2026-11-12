package com.s2p.contracts;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Customer status feed: what each customer is allowed to see about their order. */
public final class Status {
    private Status() {
    }

    /**
     * Only these fields ever reach a customer. currentEstimate is null while a date is being reconfirmed.
     * sequence is monotonic per order; the feed rejects anything older than what it holds.
     */
    public record CustomerStatus(String accountId, String orderRef, String customerPo, String stage,
                                 LocalDate promisedDate, LocalDate currentEstimate, String message,
                                 OffsetDateTime basisAsOf, long sequence, OffsetDateTime publishedAt,
                                 String decisionId) {
    }

    public record PublishResult(boolean accepted, String reason, CustomerStatus current) {
    }

    public record Subscriber(String accountId, String token) {
    }
}
