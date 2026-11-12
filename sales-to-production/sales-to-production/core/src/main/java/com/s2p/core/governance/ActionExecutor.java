package com.s2p.core.governance;

import com.s2p.contracts.Command;
import com.s2p.contracts.Erp;
import com.s2p.contracts.Mail;
import com.s2p.contracts.Planning;
import com.s2p.contracts.Ports;
import com.s2p.contracts.Status;
import com.s2p.core.engine.OrderEta;
import com.s2p.core.engine.PromiseEngine;
import com.s2p.core.engine.WorkCalendar;
import com.s2p.core.policy.Policy;
import com.s2p.core.state.CentralView;
import com.s2p.core.state.Source;
import com.s2p.core.state.StateStore;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Runs an approved action against the systems of record. Any date that would reach a customer is re-checked
 * against freshly fetched data first: an approval given an hour ago does not make a date true now.
 */
public final class ActionExecutor implements ActionQueue.Executor {
    private final Ports.MailboxPort mailbox;
    private final Ports.ErpPort erp;
    private final Ports.PlanningPort planning;
    private final Ports.StatusFeedPort statusFeed;
    private final StateStore state;
    private final Supplier<Policy> policy;
    private final DecisionLog log;
    private final PromiseEngine engine = new PromiseEngine();

    public ActionExecutor(Ports.MailboxPort mailbox, Ports.ErpPort erp, Ports.PlanningPort planning,
                          Ports.StatusFeedPort statusFeed, StateStore state, Supplier<Policy> policy, DecisionLog log) {
        this.mailbox = mailbox;
        this.erp = erp;
        this.planning = planning;
        this.statusFeed = statusFeed;
        this.state = state;
        this.policy = policy;
        this.log = log;
    }

    @Override
    public ActionQueue.Execution execute(ProposedAction a) {
        try {
            return switch (a.payload()) {
                case ActionPayload.SendEmail p -> sendEmail(a, p);
                case ActionPayload.SendQuotation p -> sendQuotation(a, p);
                case ActionPayload.PublishStatus p -> publish(a, p);
                case ActionPayload.CreateWorkOrders p -> createWorkOrders(p);
                case ActionPayload.Reserve p -> reserve(p);
                case ActionPayload.Replan p -> replan(p);
                case ActionPayload.ExceptionRequest p -> new ActionQueue.Execution("EXECUTED",
                        "exception recorded: " + p.qty() + " x " + p.sku() + " by " + WorkCalendar.fmt(p.requestedDate())
                                + " is a management commitment NOT supported by the plan (supported date "
                                + WorkCalendar.fmt(p.supportedDate()) + "); the system will keep reporting it as at risk");
            };
        } catch (UnsupportedCommitmentException e) {
            return new ActionQueue.Execution("INVALIDATED", e.getMessage());
        } catch (RuntimeException e) {
            return new ActionQueue.Execution("FAILED", e.getMessage());
        }
    }

    private CentralView freshView() {
        state.refresh(Source.ERP);
        state.refresh(Source.PLANNING);
        CentralView v = state.view();
        if (!v.canCommit()) {
            throw new UnsupportedCommitmentException("ERP or planning data is too old to commit a date: "
                    + v.freshness(Source.PLANNING).describe() + "; " + v.freshness(Source.ERP).describe());
        }
        return v;
    }

    private void recheck(List<DateCommitment> commitments) {
        if (commitments.isEmpty()) {
            return;
        }
        CentralView v = freshView();
        for (DateCommitment c : commitments) {
            recheck(c, v);
        }
    }

    private void recheck(DateCommitment c, CentralView v) {
        PromiseEngine.Result r = engine.evaluate(new PromiseEngine.Request(c.sku(), c.qty(), c.date(), c.accountId(),
                c.forOrderId()), v, policy.get(), PromiseEngine.Adjustments.NONE);
        if (!r.feasible()) {
            throw new UnsupportedCommitmentException(c.qty() + " x " + c.sku() + " by " + WorkCalendar.fmt(c.date())
                    + " is no longer supported; earliest now " + WorkCalendar.fmt(r.earliestDelivery())
                    + ". Nothing was sent.");
        }
    }

    private ActionQueue.Execution sendEmail(ProposedAction a, ActionPayload.SendEmail p) {
        recheck(a.commitments());
        Command<Mail.Sent> sent = mailbox.send(p.mail());
        logEmail(a, p.accountId(), p.mail());
        return new ActionQueue.Execution("EXECUTED", (sent.replayed() ? "already sent as " : "sent ") + sent.value().id());
    }

    private ActionQueue.Execution sendQuotation(ProposedAction a, ActionPayload.SendQuotation p) {
        recheck(a.commitments());
        erp.setQuotationStatus(p.quotationId(), "SENT");
        Command<Mail.Sent> sent = mailbox.send(p.mail());
        logEmail(a, p.accountId(), p.mail());
        return new ActionQueue.Execution("EXECUTED", p.quotationId() + " marked SENT; email "
                + (sent.replayed() ? "already sent as " : "sent ") + sent.value().id());
    }

    private void logEmail(ProposedAction a, String accountId, Mail.Outbound m) {
        DateCommitment c = a.commitments().isEmpty() ? null : a.commitments().get(0);
        log.record("CUSTOMER_EMAIL_SENT", "system", accountId, c == null ? null : c.forOrderId(), null,
                m.subject() + ": " + firstLines(m.body()), c == null ? null : c.date(), c == null ? null : c.qty(),
                List.of("action " + a.id() + " decided by " + a.decidedBy()), null, null, a.ruleId(), a.decidedBy(),
                a.id(), policy.get().version());
    }

    private ActionQueue.Execution publish(ProposedAction a, ActionPayload.PublishStatus p) {
        Status.CustomerStatus s = p.status();
        if (s.currentEstimate() != null) {
            CentralView v = freshView();
            Erp.SalesOrder o = v.order(s.orderRef()).orElseThrow();
            OrderEta eta = OrderEta.of(o, v, policy.get());
            if (eta.estimate() == null || eta.estimate().isAfter(s.currentEstimate())) {
                throw new UnsupportedCommitmentException("Estimate " + WorkCalendar.fmt(s.currentEstimate())
                        + " for " + s.orderRef() + " is no longer supported; current plan gives "
                        + WorkCalendar.fmt(eta.estimate()) + ". Nothing was published.");
            }
        }
        Status.PublishResult r = statusFeed.publish(s);
        state.recordStatusFeedContact(r.current() == null ? null : r.current().publishedAt());
        if (!r.accepted()) {
            log.record("STALE_UPDATE_REJECTED", "statusfeed", s.accountId(), s.orderRef(), null, r.reason(), null, null,
                    List.of(), null, null, null, null, a.id(), policy.get().version());
            return new ActionQueue.Execution("FAILED", "status feed refused it: " + r.reason());
        }
        log.record("CUSTOMER_STATUS_PUBLISHED", "system", s.accountId(), s.orderRef(), null,
                "Stage " + s.stage() + "; estimate " + (s.currentEstimate() == null ? "being reconfirmed"
                        : WorkCalendar.fmt(s.currentEstimate())) + "; message: " + s.message(), s.currentEstimate(), null,
                List.of("action " + a.id(), "basis as of " + s.basisAsOf()), null, null, a.ruleId(), a.decidedBy(),
                a.id(), policy.get().version());
        String extra = "";
        if (p.notifyMail() != null) {
            Command<Mail.Sent> sent = mailbox.send(p.notifyMail());
            logEmail(a, s.accountId(), p.notifyMail());
            extra = "; customer emailed " + sent.value().id();
        }
        return new ActionQueue.Execution("EXECUTED", "published seq " + s.sequence() + extra);
    }

    private ActionQueue.Execution createWorkOrders(ActionPayload.CreateWorkOrders p) {
        List<String> ids = new ArrayList<>();
        for (Planning.NewWorkOrder w : p.workOrders()) {
            Command<Planning.WorkOrder> c = planning.createWorkOrder(w);
            ids.add(c.value().id() + (c.replayed() ? " (existing)" : "") + " on " + w.lineId() + " finish "
                    + WorkCalendar.fmt(c.value().plannedFinish()));
        }
        return new ActionQueue.Execution("EXECUTED", "work orders " + String.join(", ", ids));
    }

    private ActionQueue.Execution reserve(ActionPayload.Reserve p) {
        Command<Erp.Reservation> c = erp.reserve(p.reservation());
        return new ActionQueue.Execution("EXECUTED", (c.replayed() ? "existing " : "") + c.value().id() + ": "
                + c.value().qty() + " x " + c.value().sku() + " for " + c.value().orderId());
    }

    private ActionQueue.Execution replan(ActionPayload.Replan p) {
        List<String> done = new ArrayList<>();
        if (!p.changes().isEmpty()) {
            for (Command<Planning.WorkOrder> c : planning.reschedule(p.changes())) {
                done.add(c.value().id() + (c.value().status().equals("CANCELLED") ? " handed over"
                        : " now finishes " + WorkCalendar.fmt(c.value().plannedFinish())));
            }
        }
        for (Planning.NewWorkOrder w : p.newWorkOrders()) {
            Command<Planning.WorkOrder> c = planning.createWorkOrder(w);
            done.add(c.value().id() + " created on " + w.lineId() + " finish " + WorkCalendar.fmt(c.value().plannedFinish()));
        }
        return new ActionQueue.Execution("EXECUTED", p.option() + ": " + String.join("; ", done));
    }

    private static String firstLines(String body) {
        String flat = String.join(" ", body.lines().map(String::strip).filter(l -> !l.isEmpty()).toList());
        return flat.length() > 220 ? flat.substring(0, 220) + "..." : flat;
    }
}
