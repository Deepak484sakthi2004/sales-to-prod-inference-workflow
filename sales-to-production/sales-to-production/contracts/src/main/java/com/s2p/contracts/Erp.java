package com.s2p.contracts;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** ERP contract: products, price lists, stock, quotations and orders. The ERP is the source of truth for all of these. */
public final class Erp {
    private Erp() {
    }

    /** bom maps a raw-material SKU to units consumed per finished unit. */
    public record Product(String sku, String name, String family, int sizeMm, String variant, BigDecimal unitCost,
                          Map<String, Integer> bom) {
    }

    /** accountId null means the standard list. */
    public record PriceList(String id, String accountId, String sku, BigDecimal unitPrice, String currency,
                            LocalDate validFrom, LocalDate validTo, int minQty) {
        public boolean validOn(LocalDate d) {
            return !d.isBefore(validFrom) && !d.isAfter(validTo);
        }
    }

    public record InboundReceipt(String ref, int qty, LocalDate expectedDate, String supplier,
                                 double supplierOnTimeRate) {
    }

    /** kind FG (finished goods) or RM (raw material). */
    public record StockItem(String sku, String kind, int onHand, int reserved, List<InboundReceipt> inbound,
                            long version, OffsetDateTime updatedAt) {
        public StockItem {
            inbound = inbound == null ? List.of() : List.copyOf(inbound);
        }

        public int available() {
            return onHand - reserved;
        }
    }

    public record Reservation(String id, String sku, int qty, String orderId, String idempotencyKey) {
    }

    public record QuoteLine(String sku, int qty, BigDecimal unitPrice, String priceListId, LocalDate requestedDate,
                            LocalDate promisedDate) {
        public BigDecimal value() {
            return unitPrice.multiply(BigDecimal.valueOf(qty));
        }
    }

    /** status DRAFT, SENT, ACCEPTED, LOST, EXPIRED. */
    public record Quotation(String id, String accountId, String sourceRef, List<QuoteLine> lines, LocalDate validTo,
                            String status, OffsetDateTime createdAt, OffsetDateTime sentAt, String idempotencyKey,
                            long version) {
        public BigDecimal value() {
            return lines.stream().map(QuoteLine::value).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    public record OrderLine(String sku, int qty, BigDecimal unitPrice, LocalDate requestedDate,
                            LocalDate committedDate) {
        public BigDecimal value() {
            return unitPrice.multiply(BigDecimal.valueOf(qty));
        }
    }

    /** status OPEN, SHIPPED, CANCELLED. Late-delivery terms are part of the order. */
    public record SalesOrder(String id, String accountId, String quotationId, String customerPo,
                             List<OrderLine> lines, String status, double latePenaltyPctPerDay,
                             double latePenaltyCapPct, OffsetDateTime createdAt, String idempotencyKey,
                             long version) {
        public BigDecimal value() {
            return lines.stream().map(OrderLine::value).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    public record Snapshot(List<Product> products, List<PriceList> priceLists, List<StockItem> stock,
                           List<Reservation> reservations, List<Quotation> quotations, List<SalesOrder> orders) {
    }

    public record NewQuotation(String idempotencyKey, String accountId, String sourceRef, List<QuoteLine> lines,
                               LocalDate validTo) {
    }

    public record NewOrder(String idempotencyKey, String quotationId, String customerPo) {
    }

    public record NewReservation(String idempotencyKey, String sku, int qty, String orderId) {
    }
}
