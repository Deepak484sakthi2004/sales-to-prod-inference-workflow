package com.s2p.sources;

import com.s2p.contracts.Command;
import com.s2p.contracts.Crm;
import com.s2p.contracts.Erp;
import com.s2p.contracts.Mail;
import com.s2p.contracts.Planning;
import com.s2p.contracts.Ports;
import com.s2p.contracts.SimClock;
import com.s2p.contracts.SourceResponse;
import com.s2p.contracts.SourceUnavailableException;
import com.s2p.contracts.Status;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * In-process adapters over the same stores the HTTP stand-ins serve. Used by the automated tests and the scenario
 * runner. Any source can be taken down to exercise outage handling.
 */
public final class LocalSources {
    public enum Name { MAILBOX, CRM, ERP, PLANNING, STATUS_FEED }

    public final MailboxStore mailbox;
    public final CrmStore crm;
    public final ErpStore erp;
    public final PlanningStore planning;
    public final StatusFeedStore statusFeed;
    private final Set<Name> down = EnumSet.noneOf(Name.class);

    public LocalSources(SimClock clock) {
        LocalDate anchor = clock.today();
        this.mailbox = new MailboxStore(clock, anchor);
        this.crm = new CrmStore(clock, anchor);
        this.erp = new ErpStore(clock, anchor);
        this.planning = new PlanningStore(clock, anchor);
        this.statusFeed = new StatusFeedStore(clock, anchor);
    }

    public void takeDown(Name n) {
        down.add(n);
    }

    private void check(Name n) {
        if (down.contains(n)) {
            throw new SourceUnavailableException(n.name(), "connection refused (simulated outage)");
        }
    }

    public Ports.MailboxPort mailboxPort() {
        return new Ports.MailboxPort() {
            public SourceResponse<List<Mail.Message>> inbox() {
                check(Name.MAILBOX);
                return mailbox.inbox();
            }

            public Command<Mail.Sent> send(Mail.Outbound m) {
                check(Name.MAILBOX);
                return mailbox.send(m);
            }

            public SourceResponse<List<Mail.Sent>> outbox() {
                check(Name.MAILBOX);
                return mailbox.outbox();
            }
        };
    }

    public Ports.CrmPort crmPort() {
        return new Ports.CrmPort() {
            public SourceResponse<Crm.Snapshot> snapshot() {
                check(Name.CRM);
                return crm.snapshot();
            }
        };
    }

    public Ports.ErpPort erpPort() {
        return new Ports.ErpPort() {
            public SourceResponse<Erp.Snapshot> snapshot() {
                check(Name.ERP);
                return erp.snapshot();
            }

            public Command<Erp.Quotation> createQuotation(Erp.NewQuotation q) {
                check(Name.ERP);
                return erp.createQuotation(q);
            }

            public Erp.Quotation setQuotationStatus(String id, String status) {
                check(Name.ERP);
                return erp.setQuotationStatus(id, status);
            }

            public Command<Erp.SalesOrder> createOrder(Erp.NewOrder o) {
                check(Name.ERP);
                return erp.createOrder(o);
            }

            public Command<Erp.Reservation> reserve(Erp.NewReservation r) {
                check(Name.ERP);
                return erp.reserve(r);
            }
        };
    }

    public Ports.PlanningPort planningPort() {
        return new Ports.PlanningPort() {
            public SourceResponse<Planning.Snapshot> snapshot() {
                check(Name.PLANNING);
                return planning.snapshot();
            }

            public Command<Planning.WorkOrder> createWorkOrder(Planning.NewWorkOrder w) {
                check(Name.PLANNING);
                return planning.createWorkOrder(w);
            }

            public List<Command<Planning.WorkOrder>> reschedule(List<Planning.Reschedule> batch) {
                check(Name.PLANNING);
                return planning.reschedule(batch);
            }

            public List<Planning.Event> events(long afterSeq) {
                check(Name.PLANNING);
                return planning.events(afterSeq);
            }
        };
    }

    public Ports.StatusFeedPort statusFeedPort() {
        return new Ports.StatusFeedPort() {
            public Status.PublishResult publish(Status.CustomerStatus s) {
                check(Name.STATUS_FEED);
                return statusFeed.publish(s);
            }

            public SourceResponse<List<Status.CustomerStatus>> statuses(String accountId) {
                check(Name.STATUS_FEED);
                return statusFeed.statuses(accountId);
            }

            public Optional<String> accountForToken(String token) {
                check(Name.STATUS_FEED);
                return statusFeed.accountForToken(token);
            }
        };
    }

    public Ports.SimulationPort simulationPort() {
        return new Ports.SimulationPort() {
            public Mail.Message injectMail(Mail.Message m) {
                return mailbox.inject(m);
            }

            public Planning.Event slipLine(String lineId, int days, LocalDate from, String reason) {
                return planning.slipLine(lineId, days, from, reason);
            }

            public void setPlanningFeedLag(int minutes) {
                planning.setFeedLag(minutes);
            }

            public Planning.Event reportProgress(String woId, int completedQty) {
                return planning.reportProgress(woId, completedQty);
            }

            public Status.PublishResult publishRaw(Status.CustomerStatus s) {
                return statusFeed.publish(s);
            }
        };
    }
}
