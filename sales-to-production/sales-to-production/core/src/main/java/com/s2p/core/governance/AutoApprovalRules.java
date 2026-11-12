package com.s2p.core.governance;

import com.s2p.core.policy.Policy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Rules approved in advance by a named person. An action they cover runs without waiting, and the record says which
 * rule allowed it and who approved that rule. Anything else waits for a person.
 */
public final class AutoApprovalRules {
    public record Rule(String id, String description, String approvedBy, LocalDate approvedOn,
                       Predicate<ProposedAction> covers) {
    }

    private static final String OPS_HEAD = "A. Raman (Head of Operations)";

    public List<Rule> rules(Policy p, LocalDate today) {
        LocalDate approved = today.minusDays(41);
        return List.of(
                new Rule("R1", "Publish a status update whose estimate is on or before the promised date, from fresh data",
                        OPS_HEAD, approved, a -> a.payload() instanceof ActionPayload.PublishStatus ps
                        && !ps.withdrawsEstimate() && ps.notifyMail() == null
                        && ps.status().currentEstimate() != null && ps.status().promisedDate() != null
                        && !ps.status().currentEstimate().isAfter(ps.status().promisedDate())
                        && Boolean.TRUE.equals(a.ruleFacts().get("dataFresh"))),
                new Rule("R2", "Reserve unreserved finished stock against a confirmed customer order",
                        OPS_HEAD, approved, a -> a.payload() instanceof ActionPayload.Reserve r
                        && r.reservation().orderId() != null),
                new Rule("R3", "Create work orders for a confirmed order when the plan keeps at least "
                        + p.autoScheduleMinSlack() + " working days of slack and the order is worth at most "
                        + p.autoScheduleMaxValue(), OPS_HEAD, approved,
                        a -> a.payload() instanceof ActionPayload.CreateWorkOrders
                                && a.ruleFacts().get("slackWorkingDays") instanceof Integer s && s >= p.autoScheduleMinSlack()
                                && a.ruleFacts().get("orderValue") instanceof BigDecimal v
                                && v.compareTo(p.autoScheduleMaxValue()) <= 0),
                new Rule("R4", "Withdraw a customer-visible estimate the plan no longer supports, replacing it with "
                        + "'being reconfirmed' (no new date is given)", OPS_HEAD, approved,
                        a -> a.payload() instanceof ActionPayload.PublishStatus ps && ps.withdrawsEstimate()
                                && ps.status().currentEstimate() == null && ps.notifyMail() == null));
    }

    public Optional<Rule> match(ProposedAction a, Policy p, LocalDate today) {
        return rules(p, today).stream().filter(r -> p.enabledRules().contains(r.id()))
                .filter(r -> r.covers().test(a)).findFirst();
    }
}
