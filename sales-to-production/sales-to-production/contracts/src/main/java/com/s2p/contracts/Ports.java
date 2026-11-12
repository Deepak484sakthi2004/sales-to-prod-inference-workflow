package com.s2p.contracts;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** The only way the intelligence layer reaches a system of record. HTTP and in-process adapters implement these. */
public final class Ports {
    private Ports() {
    }

    public interface MailboxPort {
        SourceResponse<List<Mail.Message>> inbox();

        Command<Mail.Sent> send(Mail.Outbound message);

        SourceResponse<List<Mail.Sent>> outbox();
    }

    public interface CrmPort {
        SourceResponse<Crm.Snapshot> snapshot();
    }

    public interface ErpPort {
        SourceResponse<Erp.Snapshot> snapshot();

        Command<Erp.Quotation> createQuotation(Erp.NewQuotation q);

        Erp.Quotation setQuotationStatus(String quotationId, String status);

        Command<Erp.SalesOrder> createOrder(Erp.NewOrder o);

        Command<Erp.Reservation> reserve(Erp.NewReservation r);
    }

    public interface PlanningPort {
        SourceResponse<Planning.Snapshot> snapshot();

        Command<Planning.WorkOrder> createWorkOrder(Planning.NewWorkOrder w);

        /** Applied atomically: either every change fits the line calendars together, or nothing changes. */
        List<Command<Planning.WorkOrder>> reschedule(List<Planning.Reschedule> batch);

        List<Planning.Event> events(long afterSeq);
    }

    public interface StatusFeedPort {
        Status.PublishResult publish(Status.CustomerStatus s);

        SourceResponse<List<Status.CustomerStatus>> statuses(String accountId);

        Optional<String> accountForToken(String token);
    }

    /** Stand-in only: lets a reviewer inject enquiries and production events. Never used by the decision logic. */
    public interface SimulationPort {
        Mail.Message injectMail(Mail.Message m);

        Planning.Event slipLine(String lineId, int workingDays, LocalDate from, String reason);

        void setPlanningFeedLag(int minutes);

        Planning.Event reportProgress(String workOrderId, int completedQty);

        Status.PublishResult publishRaw(Status.CustomerStatus s);
    }
}
