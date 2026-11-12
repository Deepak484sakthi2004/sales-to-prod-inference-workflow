package com.s2p.core.config;

import com.s2p.contracts.Command;
import com.s2p.contracts.Crm;
import com.s2p.contracts.Erp;
import com.s2p.contracts.Mail;
import com.s2p.contracts.Planning;
import com.s2p.contracts.Ports;
import com.s2p.contracts.SourceResponse;
import com.s2p.contracts.SourceUnavailableException;
import com.s2p.contracts.Status;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The core reaches each system of record only through these HTTP adapters. Transport failures become
 * SourceUnavailableException (the state store then keeps its last copy and reports the age); 409s become
 * IllegalStateException and 400/404s IllegalArgumentException, exactly as the in-process adapters behave.
 */
public final class HttpPorts {
    private final RestClient mailbox;
    private final RestClient crm;
    private final RestClient erp;
    private final RestClient planning;
    private final RestClient statusFeed;

    public HttpPorts(CoreProperties.Sources s) {
        this.mailbox = RestClient.create(s.mailbox());
        this.crm = RestClient.create(s.crm());
        this.erp = RestClient.create(s.erp());
        this.planning = RestClient.create(s.planning());
        this.statusFeed = RestClient.create(s.statusFeed());
    }

    static <T> T call(String source, Supplier<T> s) {
        try {
            return s.get();
        } catch (ResourceAccessException e) {
            throw new SourceUnavailableException(source, e.getMessage());
        } catch (RestClientResponseException e) {
            String body = e.getResponseBodyAsString();
            int code = e.getStatusCode().value();
            if (code == 409) {
                throw new IllegalStateException(body);
            }
            if (code == 400 || code == 404) {
                throw new IllegalArgumentException(body);
            }
            throw new SourceUnavailableException(source, "HTTP " + code + " " + body);
        }
    }

    public Ports.MailboxPort mailbox() {
        return new Ports.MailboxPort() {
            public SourceResponse<List<Mail.Message>> inbox() {
                return call("MAILBOX", () -> mailbox.get().uri("/inbox").retrieve()
                        .body(new ParameterizedTypeReference<SourceResponse<List<Mail.Message>>>() {
                        }));
            }

            public Command<Mail.Sent> send(Mail.Outbound m) {
                return call("MAILBOX", () -> mailbox.post().uri("/outbox").contentType(MediaType.APPLICATION_JSON)
                        .body(m).retrieve().body(new ParameterizedTypeReference<Command<Mail.Sent>>() {
                        }));
            }

            public SourceResponse<List<Mail.Sent>> outbox() {
                return call("MAILBOX", () -> mailbox.get().uri("/outbox").retrieve()
                        .body(new ParameterizedTypeReference<SourceResponse<List<Mail.Sent>>>() {
                        }));
            }
        };
    }

    public Ports.CrmPort crm() {
        return new Ports.CrmPort() {
            public SourceResponse<Crm.Snapshot> snapshot() {
                return call("CRM", () -> crm.get().uri("/snapshot").retrieve()
                        .body(new ParameterizedTypeReference<SourceResponse<Crm.Snapshot>>() {
                        }));
            }
        };
    }

    public Ports.ErpPort erp() {
        return new Ports.ErpPort() {
            public SourceResponse<Erp.Snapshot> snapshot() {
                return call("ERP", () -> erp.get().uri("/snapshot").retrieve()
                        .body(new ParameterizedTypeReference<SourceResponse<Erp.Snapshot>>() {
                        }));
            }

            public Command<Erp.Quotation> createQuotation(Erp.NewQuotation q) {
                return call("ERP", () -> erp.post().uri("/quotations").contentType(MediaType.APPLICATION_JSON)
                        .body(q).retrieve().body(new ParameterizedTypeReference<Command<Erp.Quotation>>() {
                        }));
            }

            public Erp.Quotation setQuotationStatus(String id, String status) {
                return call("ERP", () -> erp.put().uri("/quotations/{id}/status?status={s}", id, status).retrieve()
                        .body(Erp.Quotation.class));
            }

            public Command<Erp.SalesOrder> createOrder(Erp.NewOrder o) {
                return call("ERP", () -> erp.post().uri("/orders").contentType(MediaType.APPLICATION_JSON)
                        .body(o).retrieve().body(new ParameterizedTypeReference<Command<Erp.SalesOrder>>() {
                        }));
            }

            public Command<Erp.Reservation> reserve(Erp.NewReservation r) {
                return call("ERP", () -> erp.post().uri("/reservations").contentType(MediaType.APPLICATION_JSON)
                        .body(r).retrieve().body(new ParameterizedTypeReference<Command<Erp.Reservation>>() {
                        }));
            }
        };
    }

    public Ports.PlanningPort planning() {
        return new Ports.PlanningPort() {
            public SourceResponse<Planning.Snapshot> snapshot() {
                return call("PLANNING", () -> planning.get().uri("/snapshot").retrieve()
                        .body(new ParameterizedTypeReference<SourceResponse<Planning.Snapshot>>() {
                        }));
            }

            public Command<Planning.WorkOrder> createWorkOrder(Planning.NewWorkOrder w) {
                return call("PLANNING", () -> planning.post().uri("/work-orders").contentType(MediaType.APPLICATION_JSON)
                        .body(w).retrieve().body(new ParameterizedTypeReference<Command<Planning.WorkOrder>>() {
                        }));
            }

            public List<Command<Planning.WorkOrder>> reschedule(List<Planning.Reschedule> batch) {
                return call("PLANNING", () -> planning.post().uri("/reschedules").contentType(MediaType.APPLICATION_JSON)
                        .body(batch).retrieve().body(new ParameterizedTypeReference<List<Command<Planning.WorkOrder>>>() {
                        }));
            }

            public List<Planning.Event> events(long afterSeq) {
                return call("PLANNING", () -> planning.get().uri("/events?after={a}", afterSeq).retrieve()
                        .body(new ParameterizedTypeReference<List<Planning.Event>>() {
                        }));
            }
        };
    }

    public Ports.StatusFeedPort statusFeed() {
        return new Ports.StatusFeedPort() {
            public Status.PublishResult publish(Status.CustomerStatus s) {
                return call("STATUS_FEED", () -> statusFeed.post().uri("/statuses").contentType(MediaType.APPLICATION_JSON)
                        .body(s).retrieve().body(Status.PublishResult.class));
            }

            public SourceResponse<List<Status.CustomerStatus>> statuses(String accountId) {
                return call("STATUS_FEED", () -> statusFeed.get().uri("/statuses/{a}", accountId).retrieve()
                        .body(new ParameterizedTypeReference<SourceResponse<List<Status.CustomerStatus>>>() {
                        }));
            }

            public Optional<String> accountForToken(String token) {
                try {
                    Map<String, String> m = call("STATUS_FEED", () -> statusFeed.get().uri("/tokens/{t}", token).retrieve()
                            .body(new ParameterizedTypeReference<Map<String, String>>() {
                            }));
                    return Optional.ofNullable(m == null ? null : m.get("accountId"));
                } catch (IllegalArgumentException notFound) {
                    return Optional.empty();
                }
            }
        };
    }

    /** Stand-in controls for the review: inject mail and production events, or reset everything to the seed. */
    public Ports.SimulationPort simulation() {
        return new Ports.SimulationPort() {
            public Mail.Message injectMail(Mail.Message m) {
                return call("MAILBOX", () -> mailbox.post().uri("/sim/inject").contentType(MediaType.APPLICATION_JSON)
                        .body(m).retrieve().body(Mail.Message.class));
            }

            public Planning.Event slipLine(String lineId, int days, LocalDate from, String reason) {
                return call("PLANNING", () -> planning.post().uri("/sim/slip").contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("lineId", lineId, "workingDays", days, "from", from.toString(), "reason", reason))
                        .retrieve().body(Planning.Event.class));
            }

            public void setPlanningFeedLag(int minutes) {
                call("PLANNING", () -> planning.post().uri("/sim/feed-lag?minutes={m}", minutes).retrieve()
                        .toBodilessEntity());
            }

            public Planning.Event reportProgress(String woId, int completedQty) {
                return call("PLANNING", () -> planning.post().uri("/sim/progress").contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("workOrderId", woId, "completedQty", completedQty)).retrieve()
                        .body(Planning.Event.class));
            }

            public Status.PublishResult publishRaw(Status.CustomerStatus s) {
                return call("STATUS_FEED", () -> statusFeed.post().uri("/statuses").contentType(MediaType.APPLICATION_JSON)
                        .body(s).retrieve().body(Status.PublishResult.class));
            }
        };
    }

    public void resetAll() {
        for (RestClient c : List.of(mailbox, crm, erp, planning, statusFeed)) {
            call("STAND-IN", () -> c.post().uri("/sim/reset").retrieve().toBodilessEntity());
        }
    }
}
