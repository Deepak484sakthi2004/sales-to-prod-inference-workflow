package com.s2p.core.governance;

import com.s2p.contracts.Erp;
import com.s2p.contracts.Mail;
import com.s2p.contracts.Planning;
import com.s2p.contracts.Status;

import java.time.LocalDate;
import java.util.List;

/** What an action would do if a person (or a pre-approved rule) allows it. */
public sealed interface ActionPayload {
    record SendEmail(String accountId, Mail.Outbound mail) implements ActionPayload {
    }

    record SendQuotation(String accountId, String quotationId, Mail.Outbound mail) implements ActionPayload {
    }

    record PublishStatus(Status.CustomerStatus status, boolean withdrawsEstimate, Mail.Outbound notifyMail)
            implements ActionPayload {
    }

    record CreateWorkOrders(String orderId, List<Planning.NewWorkOrder> workOrders) implements ActionPayload {
    }

    record Reserve(Erp.NewReservation reservation) implements ActionPayload {
    }

    record Replan(String orderId, String option, List<Planning.Reschedule> changes,
                  List<Planning.NewWorkOrder> newWorkOrders) implements ActionPayload {
    }

    record ExceptionRequest(String requestText, String requestedBy, String accountId, String sku, int qty,
                            LocalDate requestedDate, LocalDate supportedDate) implements ActionPayload {
    }
}
