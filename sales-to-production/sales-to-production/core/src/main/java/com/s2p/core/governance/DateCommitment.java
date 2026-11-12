package com.s2p.core.governance;

import java.time.LocalDate;

/** A date that would reach a customer if this action runs. It is re-checked against live data at execution time. */
public record DateCommitment(String accountId, String sku, int qty, LocalDate date, String forOrderId) {
}
