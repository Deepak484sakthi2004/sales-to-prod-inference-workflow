package com.s2p.core.engine;

import com.s2p.contracts.Erp;
import com.s2p.contracts.Planning;
import com.s2p.core.policy.Policy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Raw material available to new production by day: unreserved stock now, plus inbound receipts once they clear
 * inspection, minus what already-planned (not yet released) work orders will draw.
 */
public final class MaterialModel {
    public record Receipt(String ref, String rmSku, int qty, LocalDate expected, LocalDate usableFrom,
                          String supplier, double onTimeRate) {
    }

    private final Map<String, Integer> base = new HashMap<>();
    private final Map<String, List<Receipt>> receipts = new HashMap<>();
    private final Map<String, Map<String, Integer>> bom = new HashMap<>();
    private final Map<String, Integer> consumed = new HashMap<>();

    public static MaterialModel from(Erp.Snapshot erp, Planning.Snapshot plan, Policy policy, Set<String> excludeOrders) {
        MaterialModel m = new MaterialModel();
        for (Erp.Product p : erp.products()) {
            m.bom.put(p.sku(), p.bom() == null ? Map.of() : p.bom());
        }
        for (Erp.StockItem s : erp.stock()) {
            if (!"RM".equals(s.kind())) {
                continue;
            }
            m.base.put(s.sku(), s.available());
            for (Erp.InboundReceipt r : s.inbound()) {
                LocalDate usable = WorkCalendar.addWorkingDays(r.expectedDate(), policy.materialInspectionWorkingDays());
                m.receipts.computeIfAbsent(s.sku(), k -> new ArrayList<>())
                        .add(new Receipt(r.ref(), s.sku(), r.qty(), r.expectedDate(), usable, r.supplier(),
                                r.supplierOnTimeRate()));
            }
        }
        for (Planning.WorkOrder wo : plan.workOrders()) {
            if (!"PLANNED".equals(wo.status()) || excludeOrders.contains(wo.orderId())) {
                continue;
            }
            m.bom.getOrDefault(wo.sku(), Map.of()).forEach((rm, per) -> m.base.merge(rm, -wo.remainingQty() * per, Integer::sum));
        }
        m.receipts.values().forEach(l -> l.sort(Comparator.comparing(Receipt::usableFrom)));
        return m;
    }

    /** For work already released to the floor: its material was issued, so nothing new is needed. */
    public static MaterialModel unconstrained() {
        return new MaterialModel();
    }

    public int buildable(String productSku, LocalDate d) {
        int min = Integer.MAX_VALUE;
        for (Map.Entry<String, Integer> c : bom.getOrDefault(productSku, Map.of()).entrySet()) {
            if (!base.containsKey(c.getKey())) {
                continue;
            }
            int avail = base.get(c.getKey()) - consumed.getOrDefault(c.getKey(), 0);
            for (Receipt r : receipts.getOrDefault(c.getKey(), List.of())) {
                if (!r.usableFrom().isAfter(d)) {
                    avail += r.qty();
                }
            }
            min = Math.min(min, Math.max(0, avail) / c.getValue());
        }
        return min;
    }

    public void consume(String productSku, int units) {
        bom.getOrDefault(productSku, Map.of()).forEach((rm, per) -> consumed.merge(rm, units * per, Integer::sum));
    }

    /** Receipts this production depends on: consumption beyond today's stock must come from them. */
    public List<Receipt> reliedOn(String productSku) {
        List<Receipt> out = new ArrayList<>();
        for (String rm : bom.getOrDefault(productSku, Map.of()).keySet()) {
            int excess = consumed.getOrDefault(rm, 0) - base.getOrDefault(rm, 0);
            for (Receipt r : receipts.getOrDefault(rm, List.of())) {
                if (excess <= 0) {
                    break;
                }
                out.add(r);
                excess -= r.qty();
            }
        }
        return out;
    }

    public int baseAvailable(String rm) {
        return base.getOrDefault(rm, 0);
    }

    public List<Receipt> receipts(String rm) {
        return receipts.getOrDefault(rm, List.of());
    }

    public Map<String, Integer> bom(String productSku) {
        return bom.getOrDefault(productSku, Map.of());
    }

    public MaterialModel withReceiptDelay(String ref, int days) {
        MaterialModel c = copy();
        c.receipts.replaceAll((rm, list) -> list.stream().map(r -> r.ref().equals(ref)
                ? new Receipt(r.ref(), r.rmSku(), r.qty(), r.expected().plusDays(days),
                WorkCalendar.addWorkingDays(r.usableFrom(), days), r.supplier(), r.onTimeRate()) : r).toList());
        return c;
    }

    public MaterialModel copy() {
        MaterialModel c = new MaterialModel();
        c.base.putAll(base);
        receipts.forEach((k, v) -> c.receipts.put(k, new ArrayList<>(v)));
        c.bom.putAll(bom);
        c.consumed.putAll(consumed);
        return c;
    }
}
