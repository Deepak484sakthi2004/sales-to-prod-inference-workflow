package com.s2p.core.insight;

import com.s2p.contracts.Erp;
import com.s2p.core.CoreContext;
import com.s2p.core.engine.CapacityModel;
import com.s2p.core.engine.Money;
import com.s2p.core.engine.OrderEta;
import com.s2p.core.engine.WorkCalendar;
import com.s2p.core.knowledge.Fact;
import com.s2p.core.state.CentralView;
import com.s2p.core.state.Source;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Every figure in the views has a key; this turns a key back into its formula and the source facts behind it. */
public final class ExplainService {
    public record Explained(String key, String value, String formula, List<Fact> inputs, OffsetDateTime asOf) {
    }

    private final CoreContext ctx;

    public ExplainService(CoreContext ctx) {
        this.ctx = ctx;
    }

    public Explained explain(String key) {
        CentralView v = ctx.state().view();
        String[] k = key.split(":");
        List<Fact> in = new ArrayList<>();
        switch (k[0]) {
            case "atp" -> {
                Erp.StockItem s = v.stock(k[1]).orElseThrow(() -> new IllegalArgumentException("Unknown SKU " + k[1]));
                v.erp().reservations().stream().filter(r -> r.sku().equals(k[1])).forEach(r -> in.add(v.fact(Source.ERP,
                        r.id(), r.qty() + " reserved for " + r.orderId())));
                in.add(v.fact(Source.ERP, "stock/" + k[1], "on hand " + s.onHand()));
                return new Explained(key, String.valueOf(s.available()), "on hand " + s.onHand() + " - reserved "
                        + s.reserved() + " = " + s.available(), in, v.at());
            }
            case "free" -> {
                LocalDate d = LocalDate.parse(k[2]);
                CapacityModel cap = CapacityModel.from(v.planning(), Set.of());
                cap.explain(k[1], d).forEach(e -> in.add(v.fact(Source.PLANNING, k[1] + "/" + d, e)));
                v.planning().capacity().stream().filter(c -> c.lineId().equals(k[1]) && c.date().equals(d) && c.note() != null)
                        .forEach(c -> in.add(v.fact(Source.PLANNING, "calendar", c.note())));
                return new Explained(key, cap.free(k[1], d) + " min", "available " + cap.available(k[1], d) + " - booked "
                        + cap.booked(k[1], d) + " = " + cap.free(k[1], d) + " min on " + WorkCalendar.fmt(d), in, v.at());
            }
            case "eta" -> {
                Erp.SalesOrder o = v.order(k[1]).orElseThrow(() -> new IllegalArgumentException("Unknown order " + k[1]));
                OrderEta eta = OrderEta.of(o, v, ctx.policy().get());
                eta.basis().forEach(b -> in.add(v.fact(Source.PLANNING, o.id(), b)));
                return new Explained(key, WorkCalendar.fmt(eta.estimate()), "latest work-order finish "
                        + WorkCalendar.fmt(eta.productionFinish()) + " + QC " + ctx.policy().get().qcWorkingDays()
                        + " working day + transit " + v.transitDays(o.accountId()) + " day(s) (Sunday deliveries move to Monday)",
                        in, v.at());
            }
            case "value" -> {
                Erp.SalesOrder o = v.order(k[1]).orElseThrow(() -> new IllegalArgumentException("Unknown order " + k[1]));
                o.lines().forEach(l -> in.add(v.fact(Source.ERP, o.id(), l.qty() + " x " + l.sku() + " @ " + Money.inr(l.unitPrice()))));
                return new Explained(key, Money.inr(o.value()), "sum of quantity x unit price", in, v.at());
            }
            case "slack" -> {
                Erp.SalesOrder o = v.order(k[1]).orElseThrow(() -> new IllegalArgumentException("Unknown order " + k[1]));
                OrderEta eta = OrderEta.of(o, v, ctx.policy().get());
                return new Explained(key, String.valueOf(eta.slackWorkingDays()), "working days from estimate "
                        + WorkCalendar.fmt(eta.estimate()) + " to committed " + WorkCalendar.fmt(eta.committed())
                        + " (Sundays excluded)", List.of(), v.at());
            }
            default -> throw new IllegalArgumentException("Unknown figure key " + key
                    + "; use atp:SKU, free:LINE:DATE, eta:ORDER, value:ORDER or slack:ORDER");
        }
    }
}
