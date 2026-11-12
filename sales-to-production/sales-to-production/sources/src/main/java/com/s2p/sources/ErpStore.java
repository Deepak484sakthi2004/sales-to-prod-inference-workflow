package com.s2p.sources;

import com.s2p.contracts.Command;
import com.s2p.contracts.Erp;
import com.s2p.contracts.RelativeJson;
import com.s2p.contracts.SimClock;
import com.s2p.contracts.SourceResponse;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ERP stand-in and source of truth for products, prices, stock, quotations and orders.
 * Idempotency is enforced here, at the source of truth, so a restart of the intelligence layer can never
 * create a second quotation for the same enquiry or a second order for the same quotation.
 */
public final class ErpStore {
    private static final Set<String> QUOTE_STATES = Set.of("DRAFT", "SENT", "ACCEPTED", "LOST", "EXPIRED");

    private final SimClock clock;
    private final LocalDate anchor;
    private final AtomicLong version = new AtomicLong();
    private final Map<String, Erp.Product> products = new LinkedHashMap<>();
    private final List<Erp.PriceList> priceLists = new ArrayList<>();
    private final Map<String, Erp.StockItem> stock = new LinkedHashMap<>();
    private final List<Erp.Reservation> reservations = new ArrayList<>();
    private final Map<String, Erp.Quotation> quotations = new LinkedHashMap<>();
    private final Map<String, Erp.SalesOrder> orders = new LinkedHashMap<>();
    private final Map<String, String> idempotency = new HashMap<>();
    private int nextQuote;
    private int nextOrder;
    private int nextReservation;

    public ErpStore(SimClock clock, LocalDate anchor) {
        this.clock = clock;
        this.anchor = anchor;
        reset();
    }

    public synchronized void reset() {
        Erp.Snapshot seed = new RelativeJson(anchor).load("seed/erp.json", Erp.Snapshot.class);
        products.clear();
        priceLists.clear();
        stock.clear();
        reservations.clear();
        quotations.clear();
        orders.clear();
        idempotency.clear();
        version.set(2000);
        seed.products().forEach(p -> products.put(p.sku(), p));
        priceLists.addAll(seed.priceLists());
        seed.stock().forEach(s -> stock.put(s.sku(), new Erp.StockItem(s.sku(), s.kind(), s.onHand(), s.reserved(),
                s.inbound(), version.incrementAndGet(), s.updatedAt() == null ? clock.now() : s.updatedAt())));
        reservations.addAll(seed.reservations());
        seed.quotations().forEach(q -> quotations.put(q.id(), q));
        seed.orders().forEach(o -> orders.put(o.id(), o));
        seed.quotations().stream().filter(q -> q.idempotencyKey() != null)
                .forEach(q -> idempotency.put(q.idempotencyKey(), q.id()));
        nextQuote = Ids.next(quotations.keySet(), "Q-", 3000);
        nextOrder = Ids.next(orders.keySet(), "SO-", 1000);
        nextReservation = Ids.next(reservations.stream().map(Erp.Reservation::id).toList(), "RES-", 300);
    }

    public synchronized SourceResponse<Erp.Snapshot> snapshot() {
        return new SourceResponse<>("erp", version.get(), clock.now(), new Erp.Snapshot(
                List.copyOf(products.values()), List.copyOf(priceLists), List.copyOf(stock.values()),
                List.copyOf(reservations), List.copyOf(quotations.values()), List.copyOf(orders.values())));
    }

    public synchronized Command<Erp.Quotation> createQuotation(Erp.NewQuotation q) {
        requireKey(q.idempotencyKey());
        String existing = idempotency.get(q.idempotencyKey());
        if (existing != null) {
            return new Command<>(quotations.get(existing), true);
        }
        if (q.accountId() == null || q.lines() == null || q.lines().isEmpty()) {
            throw new IllegalArgumentException("A quotation needs an account and at least one line");
        }
        for (Erp.QuoteLine l : q.lines()) {
            if (!products.containsKey(l.sku()) || l.qty() <= 0 || l.unitPrice().signum() <= 0) {
                throw new IllegalArgumentException("Invalid quotation line " + l);
            }
        }
        String id = "Q-" + nextQuote++;
        Erp.Quotation created = new Erp.Quotation(id, q.accountId(), q.sourceRef(), List.copyOf(q.lines()),
                q.validTo(), "DRAFT", clock.now(), null, q.idempotencyKey(), version.incrementAndGet());
        quotations.put(id, created);
        idempotency.put(q.idempotencyKey(), id);
        return new Command<>(created, false);
    }

    public synchronized Erp.Quotation setQuotationStatus(String id, String status) {
        Erp.Quotation q = quotations.get(id);
        if (q == null) {
            throw new IllegalArgumentException("Unknown quotation " + id);
        }
        if (!QUOTE_STATES.contains(status)) {
            throw new IllegalArgumentException("Unknown quotation status " + status);
        }
        if (q.status().equals(status)) {
            return q;
        }
        boolean allowed = switch (q.status()) {
            case "DRAFT" -> status.equals("SENT") || status.equals("LOST");
            case "SENT" -> status.equals("ACCEPTED") || status.equals("LOST") || status.equals("EXPIRED");
            default -> false;
        };
        if (!allowed) {
            throw new IllegalStateException("Quotation " + id + " cannot move from " + q.status() + " to " + status);
        }
        Erp.Quotation updated = new Erp.Quotation(q.id(), q.accountId(), q.sourceRef(), q.lines(), q.validTo(), status,
                q.createdAt(), status.equals("SENT") ? clock.now() : q.sentAt(), q.idempotencyKey(),
                version.incrementAndGet());
        quotations.put(id, updated);
        return updated;
    }

    public synchronized Command<Erp.SalesOrder> createOrder(Erp.NewOrder o) {
        requireKey(o.idempotencyKey());
        String existing = idempotency.get(o.idempotencyKey());
        if (existing != null) {
            return new Command<>(orders.get(existing), true);
        }
        for (Erp.SalesOrder so : orders.values()) {
            if (o.quotationId().equals(so.quotationId())) {
                idempotency.put(o.idempotencyKey(), so.id());
                return new Command<>(so, true);
            }
        }
        Erp.Quotation q = quotations.get(o.quotationId());
        if (q == null || !(q.status().equals("SENT") || q.status().equals("ACCEPTED"))) {
            throw new IllegalStateException("Quotation " + o.quotationId() + " is not open for acceptance");
        }
        if (q.validTo().isBefore(clock.today())) {
            throw new IllegalStateException("Quotation " + q.id() + " expired on " + q.validTo());
        }
        List<Erp.OrderLine> lines = q.lines().stream().map(l -> new Erp.OrderLine(l.sku(), l.qty(), l.unitPrice(),
                l.requestedDate(), l.promisedDate())).toList();
        String id = "SO-" + nextOrder++;
        Erp.SalesOrder so = new Erp.SalesOrder(id, q.accountId(), q.id(), o.customerPo(), lines, "OPEN", 0.5, 5.0,
                clock.now(), o.idempotencyKey(), version.incrementAndGet());
        orders.put(id, so);
        idempotency.put(o.idempotencyKey(), id);
        if (q.status().equals("SENT")) {
            setQuotationStatus(q.id(), "ACCEPTED");
        }
        return new Command<>(so, false);
    }

    public synchronized Command<Erp.Reservation> reserve(Erp.NewReservation r) {
        requireKey(r.idempotencyKey());
        for (Erp.Reservation existing : reservations) {
            if (r.idempotencyKey().equals(existing.idempotencyKey())) {
                return new Command<>(existing, true);
            }
        }
        Erp.StockItem item = stock.get(r.sku());
        if (item == null) {
            throw new IllegalArgumentException("Unknown stock item " + r.sku());
        }
        if (item.available() < r.qty()) {
            throw new IllegalStateException("Only " + item.available() + " of " + r.sku() + " available, "
                    + r.qty() + " requested");
        }
        Erp.Reservation res = new Erp.Reservation("RES-" + nextReservation++, r.sku(), r.qty(), r.orderId(),
                r.idempotencyKey());
        reservations.add(res);
        stock.put(r.sku(), new Erp.StockItem(item.sku(), item.kind(), item.onHand(), item.reserved() + r.qty(),
                item.inbound(), version.incrementAndGet(), clock.now()));
        return new Command<>(res, false);
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Writes to the ERP need an idempotency key");
        }
    }
}
