package com.s2p.core.status;

import com.s2p.contracts.Crm;
import com.s2p.contracts.Erp;
import com.s2p.contracts.Status;
import com.s2p.core.CoreContext;
import com.s2p.core.engine.OrderEta;
import com.s2p.core.engine.WorkCalendar;
import com.s2p.core.governance.ActionPayload;
import com.s2p.core.governance.ProposedAction;
import com.s2p.core.knowledge.Assessment;
import com.s2p.core.knowledge.Confidence;
import com.s2p.core.knowledge.Describable;
import com.s2p.core.knowledge.Fact;
import com.s2p.core.knowledge.Freshness;
import com.s2p.core.policy.Policy;
import com.s2p.core.state.CentralView;
import com.s2p.core.state.Source;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Keeps what customers see in step with production. Good news and progress are published under rule R1; an estimate
 * the plan no longer supports is withdrawn under rule R4; a later date always waits for a planner.
 * Customers only ever see the fields of {@link Status.CustomerStatus}: no lines, work orders or reasons.
 */
public final class StatusService {
    public record StatusChange(String orderId, String account, String customerSaw, String nowTrue, String outcome,
                               String actionId) implements Describable {
        @Override
        public String describe() {
            return orderId + " (" + account + "): customer saw [" + customerSaw + "], plan says [" + nowTrue + "] -> "
                    + outcome + (actionId == null ? "" : " (" + actionId + ")");
        }
    }

    public record CustomerOrderView(String orderRef, String yourPo, String stage, String stageLabel,
                                    LocalDate promisedDate, LocalDate currentEstimate, String estimateText,
                                    String message, OffsetDateTime lastUpdated, String basisText) {
    }

    public record CustomerAnswer(String account, List<CustomerOrderView> orders, OffsetDateTime asOf, String note)
            implements Describable {
        @Override
        public String describe() {
            StringBuilder b = new StringBuilder("Shown to " + account + ":");
            for (CustomerOrderView o : orders) {
                b.append("\n  ").append(o.orderRef()).append(o.yourPo() == null ? "" : " (your PO " + o.yourPo() + ")")
                        .append(": ").append(o.stageLabel()).append("; promised ").append(WorkCalendar.fmt(o.promisedDate()))
                        .append("; ").append(o.estimateText()).append("\n    \"").append(o.message()).append("\"\n    ")
                        .append(o.basisText());
            }
            if (note != null) {
                b.append("\n  ").append(note);
            }
            return b.toString();
        }
    }

    private static final Map<String, String> LABELS = Map.of(
            "ORDER_RECEIVED", "Order received", "SCHEDULED", "Scheduled for production",
            "IN_PRODUCTION", "In production", "READY_TO_DISPATCH", "Ready to dispatch", "SHIPPED", "Shipped");
    private static final Map<String, String> MESSAGES = Map.of(
            "ORDER_RECEIVED", "We have received your order and are planning production.",
            "SCHEDULED", "Your order is scheduled for production.",
            "IN_PRODUCTION", "Your order is in production.",
            "READY_TO_DISPATCH", "Your order is packed and ready to dispatch.");

    private final CoreContext ctx;

    public StatusService(CoreContext ctx) {
        this.ctx = ctx;
    }

    public synchronized List<StatusChange> sync() {
        CentralView v = ctx.state().view();
        Policy p = ctx.policy().get();
        Freshness planning = v.freshness(Source.PLANNING);
        List<StatusChange> out = new ArrayList<>();
        for (Erp.SalesOrder o : v.openOrders()) {
            String account = v.accountName(o.accountId());
            Optional<Status.CustomerStatus> pub = ctx.statusFeed().statuses(o.accountId()).data().stream()
                    .filter(s -> s.orderRef().equals(o.id())).findFirst();
            ctx.state().recordStatusFeedContact(v.at());
            OrderEta eta = OrderEta.of(o, v, p);
            String saw = pub.map(s -> s.stage() + ", " + (s.currentEstimate() == null ? "date being reconfirmed"
                    : "estimate " + WorkCalendar.fmt(s.currentEstimate()))).orElse("nothing published");
            String truth = eta.stage() + ", estimate " + WorkCalendar.fmt(eta.estimate());
            if (pub.isPresent() && pub.get().stage().equals(eta.stage())
                    && Objects.equals(pub.get().currentEstimate(), eta.estimate())) {
                continue;
            }
            if (planning.blocksCommitment()) {
                out.add(new StatusChange(o.id(), account, saw, truth, "NOT UPDATED: " + planning.describe(), null));
                continue;
            }
            long seq = pub.map(Status.CustomerStatus::sequence).orElse(0L) + 1;
            boolean supported = eta.estimate() != null && !eta.estimate().isAfter(eta.committed());
            if (supported) {
                Status.CustomerStatus st = new Status.CustomerStatus(o.accountId(), o.id(), o.customerPo(), eta.stage(),
                        eta.committed(), eta.estimate(), MESSAGES.getOrDefault(eta.stage(), "Your order is being processed."),
                        planning.asOf(), seq, null, null);
                ProposedAction a = ctx.actions().propose(ProposedAction.draft("PUBLISH_STATUS", "Publish " + o.id()
                                + ": " + eta.stage() + ", estimate " + WorkCalendar.fmt(eta.estimate()),
                        new ActionPayload.PublishStatus(st, false, null), "PLANNER",
                        "status:" + o.id() + ":" + eta.stage() + ":" + eta.estimate(),
                        Map.of("dataFresh", planning.fresh()), List.of(), null));
                out.add(new StatusChange(o.id(), account, saw, truth, outcome(a), a.id()));
                continue;
            }
            boolean showsDate = pub.isPresent() && pub.get().currentEstimate() != null;
            if (showsDate) {
                String by = WorkCalendar.fmt(WorkCalendar.addWorkingDays(v.today(), 1));
                Status.CustomerStatus st = new Status.CustomerStatus(o.accountId(), o.id(), o.customerPo(), eta.stage(),
                        eta.committed(), null, "We are reconfirming your delivery date and will update you by " + by + ".",
                        planning.asOf(), seq, null, null);
                ProposedAction w = ctx.actions().propose(ProposedAction.draft("PUBLISH_STATUS", "Withdraw the "
                                + WorkCalendar.fmt(pub.get().currentEstimate()) + " estimate on " + o.id()
                                + " (plan now gives " + WorkCalendar.fmt(eta.estimate()) + ")",
                        new ActionPayload.PublishStatus(st, true, null), "PLANNER",
                        "withdraw:" + o.id() + ":" + pub.get().sequence(), Map.of("dataFresh", planning.fresh()),
                        List.of(), null));
                out.add(new StatusChange(o.id(), account, saw, truth, outcome(w) + "; new date needs a planner (see re-plan "
                        + "suggestions)", w.id()));
            } else {
                out.add(new StatusChange(o.id(), account, saw, truth, "UNCHANGED: estimate already withdrawn; new date "
                        + WorkCalendar.fmt(eta.estimate()) + " waits for a planner decision", null));
            }
        }
        return out;
    }

    private static String outcome(ProposedAction a) {
        return a.ruleId() != null ? a.status() + " under rule " + a.ruleId() : "WAITING for " + a.requiredRole();
    }

    /** What a customer sees. Only published, customer-safe fields; never the internal plan. */
    public Assessment<CustomerAnswer> customerView(String token, String orderRef) {
        String accountId = ctx.statusFeed().accountForToken(token)
                .orElseThrow(() -> new SecurityException("Unknown customer token"));
        CentralView v = ctx.state().view();
        Policy p = ctx.policy().get();
        String account = v.accountName(accountId);
        List<Status.CustomerStatus> published = ctx.statusFeed().statuses(accountId).data();
        ctx.state().recordStatusFeedContact(v.at());
        List<CustomerOrderView> views = new ArrayList<>();
        Confidence.Builder conf = Confidence.start();
        List<Fact> basis = new ArrayList<>();
        String note = null;
        for (Status.CustomerStatus s : published) {
            if (orderRef != null && !orderRef.equalsIgnoreCase(s.orderRef()) && !orderRef.equalsIgnoreCase(s.customerPo())) {
                continue;
            }
            long basisAge = s.basisAsOf() == null ? 0 : Duration.between(s.basisAsOf(), v.at()).toMinutes();
            String estimateText = s.currentEstimate() == null ? "delivery date being reconfirmed"
                    : "estimated delivery " + WorkCalendar.fmt(s.currentEstimate());
            String basisText = "Last updated " + Freshness.ageText(Duration.between(s.publishedAt(), v.at()).toMinutes())
                    + " ago, based on production data from " + Freshness.ageText(basisAge) + " ago.";
            views.add(new CustomerOrderView(s.orderRef(), s.customerPo(), s.stage(), LABELS.getOrDefault(s.stage(), s.stage()),
                    s.promisedDate(), s.currentEstimate(), estimateText, s.message(), s.publishedAt(), basisText));
            basis.add(new Fact("STATUS_FEED", s.orderRef(), "published seq " + s.sequence() + ": " + s.stage() + ", "
                    + estimateText, s.sequence(), Duration.between(s.publishedAt(), v.at()).toMinutes()));
            v.order(s.orderRef()).filter(o -> o.status().equals("OPEN")).ifPresent(o -> {
                OrderEta eta = OrderEta.of(o, v, p);
                basis.add(v.fact(Source.PLANNING, o.id(), "internal estimate " + WorkCalendar.fmt(eta.estimate())
                        + " (not shown to the customer)"));
                if (!Objects.equals(eta.estimate(), s.currentEstimate())) {
                    conf.apply(o.id() + " status", 0.7, "the plan now says " + WorkCalendar.fmt(eta.estimate())
                            + "; the customer sees the last approved status until a planner decides");
                }
            });
            if (basisAge > p.sla(Source.PLANNING).toMinutes() * 48 && !"SHIPPED".equals(s.stage())) {
                conf.apply(s.orderRef() + " age", 0.8, "the published status rests on data " + Freshness.ageText(basisAge) + " old");
            }
        }
        if (views.isEmpty()) {
            note = orderRef == null ? "No orders on your account." : "No order with reference " + orderRef
                    + " on your account.";
        }
        CustomerAnswer answer = new CustomerAnswer(account, views, v.at(), note);
        String headline = views.isEmpty() ? note : views.stream().map(o -> o.orderRef() + ": " + o.stageLabel() + ", "
                + o.estimateText()).reduce((a, b) -> a + "; " + b).orElse("");
        return new Assessment<>("Customer " + account + ": where is my order?", headline, answer, conf.build(),
                CoreContext.fresh(v, Source.STATUS_FEED, Source.PLANNING), basis,
                List.of("Carrier tracking after dispatch (no carrier feed is connected)."),
                List.of("A planner approving a new estimate changes what the customer sees within one sync."), null);
    }

    public static Optional<Crm.Account> account(CentralView v, String id) {
        return v.account(id);
    }
}
