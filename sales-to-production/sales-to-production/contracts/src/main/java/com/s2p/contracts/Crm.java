package com.s2p.contracts;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** CRM contract: accounts, contacts, opportunities and activity. */
public final class Crm {
    private Crm() {
    }

    public record Account(String id, String name, String emailDomain, String tier, String region, int transitDays,
                          double historicalWinRate) {
    }

    public record Contact(String id, String accountId, String name, String email, String role) {
    }

    /**
     * stage is OPEN, WON or LOST. signals is the deal-signal snapshot the CRM records at each stage change
     * (daysSilent, priceGapPct, deliveryGapDays, competitorMentioned, validityDaysLeft, accountWinRate).
     */
    public record Opportunity(String id, String accountId, String quotationId, String stage, BigDecimal value,
                              OffsetDateTime closedAt, Map<String, Double> signals) {
    }

    /** type EMAIL, CALL, MEETING, NOTE; direction INBOUND, OUTBOUND, INTERNAL. */
    public record Activity(String id, String accountId, String quotationId, String type, String direction,
                           String note, OffsetDateTime at) {
    }

    public record Snapshot(List<Account> accounts, List<Contact> contacts, List<Opportunity> opportunities,
                           List<Activity> activities) {
    }
}
