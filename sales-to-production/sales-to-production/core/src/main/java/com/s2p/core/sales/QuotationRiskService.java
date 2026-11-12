package com.s2p.core.sales;

import com.s2p.contracts.Crm;
import com.s2p.contracts.Erp;
import com.s2p.core.CoreContext;
import com.s2p.core.engine.Money;
import com.s2p.core.engine.PromiseEngine;
import com.s2p.core.engine.WorkCalendar;
import com.s2p.core.governance.DecisionRecord;
import com.s2p.core.governance.Suggestion;
import com.s2p.core.knowledge.Assessment;
import com.s2p.core.knowledge.Confidence;
import com.s2p.core.knowledge.Describable;
import com.s2p.core.knowledge.Fact;
import com.s2p.core.policy.Policy;
import com.s2p.core.state.CentralView;
import com.s2p.core.state.Source;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Ranks open quotations by expected loss (probability of losing it x its value) and proposes one specific action per
 * quotation. Each action's effect is estimated by re-scoring the quotation with the signal the action changes, so
 * the "difference versus doing nothing" comes from the same model that produced the ranking.
 */
public final class QuotationRiskService {
    public record QuoteRisk(int rank, String quotationId, String account, BigDecimal value, double pLoss,
                            BigDecimal expectedLoss, Map<String, Double> signals, String mainDriver,
                            Suggestion suggestion) {
    }

    public record QuoteRiskReport(List<QuoteRisk> ranked, QuoteRiskModel.Calibration calibration)
            implements Describable {
        @Override
        public String describe() {
            StringBuilder b = new StringBuilder("Open quotations ranked by expected loss (p(loss) x value). Model: "
                    + calibration.describe() + ".");
            for (QuoteRisk q : ranked) {
                b.append("\n  ").append(q.rank()).append(". ").append(q.quotationId()).append(" ").append(q.account())
                        .append(" ").append(Money.inr(q.value())).append(": p(loss) ").append(String.format("%.2f", q.pLoss()))
                        .append(", expected loss ").append(Money.inr(q.expectedLoss())).append("; main driver ")
                        .append(q.mainDriver()).append("\n     signals ").append(q.signals());
                if (q.suggestion() != null) {
                    b.append("\n     [").append(q.suggestion().id()).append("] ")
                            .append(q.suggestion().describe().replace("\n", "\n     "));
                }
            }
            return b.toString();
        }
    }

    private record Candidate(String action, Map<String, Double> after, BigDecimal cost, String costNote, String role,
                             List<String> would, double effectConfidence) {
    }

    private final CoreContext ctx;
    private final ActivityReader activities;

    public QuotationRiskService(CoreContext ctx) {
        this.ctx = ctx;
        this.activities = new ActivityReader(ctx);
    }

    public Assessment<QuoteRiskReport> rank() {
        CentralView v = ctx.state().view();
        Policy p = ctx.policy().get();
        QuoteRiskModel.Calibration cal = QuoteRiskModel.calibrate(v.crm().opportunities());
        record Scored(Erp.Quotation q, Map<String, Double> signals, double pLoss, BigDecimal el) {
        }
        List<Scored> scored = new ArrayList<>();
        List<Erp.Quotation> open = v.erp().quotations().stream().filter(q -> q.status().equals("SENT")).toList();
        Map<String, ActivityReader.Reading> read = activities.read(open, v);
        List<String> unread = ActivityReader.unread(open, read);
        for (Erp.Quotation q : open) {
            Map<String, Double> s = signals(q, v, read.get(q.id()));
            double pl = QuoteRiskModel.pLoss(s);
            scored.add(new Scored(q, s, pl, q.value().multiply(BigDecimal.valueOf(pl)).setScale(0, RoundingMode.HALF_UP)));
        }
        scored.sort(Comparator.comparing(Scored::el).reversed());
        List<QuoteRisk> ranked = new ArrayList<>();
        List<Suggestion> suggestions = new ArrayList<>();
        int rank = 1;
        for (Scored sc : scored) {
            Map<String, Double> contrib = QuoteRiskModel.contributions(sc.signals());
            String driver = contrib.entrySet().stream().max(Map.Entry.comparingByValue())
                    .map(e -> e.getKey() + " (+" + String.format("%.2f", e.getValue()) + ")").orElse("none");
            Suggestion s = suggest(sc.q(), sc.signals(), sc.pLoss(), sc.el(), v, p);
            if (s != null) {
                suggestions.add(s);
            }
            ranked.add(new QuoteRisk(rank++, sc.q().id(), v.accountName(sc.q().accountId()), sc.q().value(),
                    Math.round(sc.pLoss() * 100) / 100.0, sc.el(), sc.signals(), driver, s));
        }
        ctx.suggestions().replace("QUOTE:", suggestions);
        Confidence.Builder c = Confidence.start();
        double skill = cal.baselineBrier() > 0 ? 1 - cal.brier() / cal.baselineBrier() : 0;
        c.apply("model calibration", Math.max(0.4, Math.min(0.95, 0.5 + skill)), cal.describe());
        c.apply("sample size", cal.samples() >= 30 ? 1.0 : 0.85, cal.samples() + " closed quotations is a small history");
        if (!unread.isEmpty()) {
            c.apply("CRM notes", 0.9, "the language model could not read the notes for " + String.join(", ", unread)
                    + "; competitor mentions there are unknown and counted as none");
        }
        String headline = ranked.isEmpty() ? "No open quotations." : "Most at risk: " + ranked.stream().limit(3)
                .map(q -> q.quotationId() + " " + q.account() + " (p " + String.format("%.2f", q.pLoss()) + ", "
                        + Money.inr(q.expectedLoss()) + " at risk)").collect(Collectors.joining("; ")) + ".";
        DecisionRecord rec = ctx.log().record("SUGGESTION", "system", null, null, null, headline, null, null,
                suggestions.stream().map(s -> s.id() + ": " + s.action()).toList(), v, c.build().score(), null, null, null,
                p.version());
        List<Fact> basis = new ArrayList<>();
        basis.add(v.fact(Source.ERP, "quotations", scored.size() + " quotations in SENT status"));
        basis.add(v.fact(Source.CRM, "activities", "customer replies and calls from CRM activity"));
        read.forEach((id, r) -> {
            if (r.competitor()) {
                basis.add(v.fact(Source.CRM, r.activityId(), id + ": competitor mentioned, \"" + r.evidence()
                        + "\" (read by " + r.model() + ")"));
            }
        });
        basis.add(v.fact(Source.ERP, "orders", "last price each account paid per product, from past orders"));
        basis.add(new Fact("MODEL", "QuoteRiskModel", "logit = " + QuoteRiskModel.INTERCEPT + " + " + QuoteRiskModel.WEIGHTS,
                0, 0));
        return new Assessment<>("Which open quotations are most likely to be lost, and what should we do?", headline,
                new QuoteRiskReport(ranked, cal), c.build(), CoreContext.fresh(v, Source.ERP, Source.CRM, Source.PLANNING),
                basis, notChecked(unread),
                List.of("A reply from the customer resets daysSilent and usually drops p(loss) sharply.",
                        "Weights are hand-set; re-fitting them on more closed quotations would change the ranking."),
                rec.id());
    }

    private static double calibrationFactor(CentralView v) {
        QuoteRiskModel.Calibration cal = QuoteRiskModel.calibrate(v.crm().opportunities());
        double skill = cal.baselineBrier() > 0 ? 1 - cal.brier() / cal.baselineBrier() : 0;
        return Math.max(0.4, Math.min(0.95, 0.5 + skill));
    }

    private static List<String> notChecked(List<String> unread) {
        List<String> n = new ArrayList<>(List.of("Competitor prices (only whether one was mentioned).",
                "Customer budget cycles and approvals.", "Rep workload: the actions assume time is available."));
        if (!unread.isEmpty()) {
            n.add("Competitor mentions in the CRM notes for " + String.join(", ", unread)
                    + ": the language model could not read them.");
        }
        return n;
    }

    /** Signals for one quotation. {@code notes} is the model's reading of its CRM notes (null when not read). */
    Map<String, Double> signals(Erp.Quotation q, CentralView v, ActivityReader.Reading notes) {
        LocalDate today = v.today();
        List<Crm.Activity> acts = v.crm().activities().stream().filter(a -> q.id().equals(a.quotationId())).toList();
        LocalDate lastHeard = acts.stream().filter(a -> a.direction().equals("INBOUND")).map(a -> a.at().toLocalDate())
                .max(LocalDate::compareTo).orElse(q.sentAt() == null ? q.createdAt().toLocalDate() : q.sentAt().toLocalDate());
        Erp.QuoteLine main = q.lines().stream().max(Comparator.comparing(Erp.QuoteLine::value)).orElseThrow();
        Optional<BigDecimal> lastPaid = v.erp().orders().stream()
                .filter(o -> o.accountId().equals(q.accountId()))
                .sorted(Comparator.comparing(Erp.SalesOrder::createdAt).reversed())
                .flatMap(o -> o.lines().stream()).filter(l -> l.sku().equals(main.sku()))
                .map(Erp.OrderLine::unitPrice).findFirst();
        double gap = lastPaid.map(lp -> main.unitPrice().subtract(lp).doubleValue() / lp.doubleValue() * 100).orElse(0.0);
        Map<String, Double> s = new LinkedHashMap<>();
        s.put("daysSilent", (double) ChronoUnit.DAYS.between(lastHeard, today));
        s.put("priceGapPct", Math.round(gap * 10) / 10.0);
        s.put("deliveryGapDays", (double) Math.max(0, main.requestedDate() == null ? 0
                : ChronoUnit.DAYS.between(main.requestedDate(), main.promisedDate())));
        s.put("competitorMentioned", notes != null && notes.competitor() ? 1.0 : 0.0);
        s.put("validityExpiring", ChronoUnit.DAYS.between(today, q.validTo()) <= 3 ? 1.0 : 0.0);
        s.put("accountWinRate", v.account(q.accountId()).map(Crm.Account::historicalWinRate).orElse(0.5));
        return s;
    }

    private Suggestion suggest(Erp.Quotation q, Map<String, Double> s, double pl, BigDecimal el, CentralView v, Policy p) {
        String contact = v.primaryContact(q.accountId()).map(Crm.Contact::name).orElse("the buyer");
        Erp.QuoteLine main = q.lines().stream().max(Comparator.comparing(Erp.QuoteLine::value)).orElseThrow();
        List<Candidate> cands = new ArrayList<>();
        BigDecimal callCost = p.staffHourCost().divide(BigDecimal.valueOf(2), 0, RoundingMode.HALF_UP);

        if (s.get("daysSilent") >= 5 || s.get("validityExpiring") > 0) {
            Map<String, Double> after = new LinkedHashMap<>(s);
            after.put("daysSilent", Math.floor(s.get("daysSilent") / 2));
            after.put("validityExpiring", 0.0);
            LocalDate ext = q.validTo().plusDays(14);
            LocalDate priceEnd = v.erp().priceLists().stream().filter(pl2 -> pl2.id().equals(main.priceListId())
                    && pl2.sku().equals(main.sku())).map(Erp.PriceList::validTo).findFirst().orElse(ext);
            if (priceEnd.isBefore(ext)) {
                ext = priceEnd;
            }
            cands.add(new Candidate("Call " + contact + " before the quote lapses on " + WorkCalendar.fmt(q.validTo())
                    + " and offer to extend validity to " + WorkCalendar.fmt(ext) + " (price list " + main.priceListId()
                    + " allows it)", after, callCost, "30 minutes of a rep's time; no price change", "SALES_REP",
                    List.of("Assumes the call gets a response (modelled as halving days silent)."), 0.6));
        }
        if (s.get("priceGapPct") > 1.0) {
            BigDecimal target = main.unitPrice().divide(BigDecimal.valueOf(1 + s.get("priceGapPct") / 100), 0, RoundingMode.HALF_UP);
            BigDecimal floor = v.product(main.sku()).map(pr -> pr.unitCost().multiply(BigDecimal.valueOf(1 + p.marginFloorPct() / 100)))
                    .orElse(target).setScale(0, RoundingMode.HALF_UP);
            BigDecimal newPrice = target.max(floor);
            if (newPrice.compareTo(main.unitPrice()) < 0) {
                Map<String, Double> after = new LinkedHashMap<>(s);
                after.put("priceGapPct", Math.round((newPrice.doubleValue() / main.unitPrice().doubleValue()
                        * (1 + s.get("priceGapPct") / 100) - 1) * 1000) / 10.0);
                BigDecimal cost = main.unitPrice().subtract(newPrice).multiply(BigDecimal.valueOf(main.qty()));
                cands.add(new Candidate("Re-quote " + main.sku() + " at " + Money.inr(newPrice) + " (the price "
                        + v.accountName(q.accountId()) + " last paid; above the " + p.marginFloorPct()
                        + "% margin floor of " + Money.inr(floor) + ")", after, cost, "margin given up on "
                        + main.qty() + " units", "SALES_HEAD",
                        List.of("A competitor quoting below " + Money.inr(floor) + " cannot be matched within policy."), 0.7));
            }
        }
        if (s.get("deliveryGapDays") > 2 && main.requestedDate() != null) {
            PromiseEngine.Result r = ctx.promises().engine().evaluate(new PromiseEngine.Request(main.sku(), main.qty(),
                    main.requestedDate(), q.accountId(), null), v, p, PromiseEngine.Adjustments.NONE);
            if (r.qtyByRequested() > 0 && r.qtyByRequested() < main.qty()) {
                Map<String, Double> after = new LinkedHashMap<>(s);
                after.put("deliveryGapDays", Math.floor(s.get("deliveryGapDays") / 2));
                cands.add(new Candidate("Offer a split: " + r.qtyByRequested() + " of " + main.qty() + " by the requested "
                        + WorkCalendar.fmt(main.requestedDate()) + " (checked against today's stock and capacity), the rest by "
                        + WorkCalendar.fmt(r.earliestDelivery()), after, p.extraShipmentCost(), "a second shipment",
                        "SALES_REP", List.of("The partial quantity holds only while that capacity stays free."), 0.65));
            }
        }
        Candidate best = null;
        double bestNet = Double.NEGATIVE_INFINITY;
        BigDecimal bestEl = null;
        for (Candidate cnd : cands) {
            double pAfter = QuoteRiskModel.pLoss(cnd.after());
            BigDecimal elAfter = q.value().multiply(BigDecimal.valueOf(pAfter)).setScale(0, RoundingMode.HALF_UP).add(cnd.cost());
            double net = el.subtract(elAfter).doubleValue();
            if (net > bestNet) {
                bestNet = net;
                best = cnd;
                bestEl = elAfter;
            }
        }
        if (best == null || bestNet <= 0) {
            return null;
        }
        List<String> would = new ArrayList<>(best.would());
        would.add(String.format("p(loss) %.2f -> %.2f if the action works as modelled.", pl, QuoteRiskModel.pLoss(best.after())));
        return new Suggestion(ctx.suggestions().nextId(), "QUOTE:" + q.id(), "Win " + q.id() + " from "
                + v.accountName(q.accountId()) + " (" + Money.inr(q.value()) + ")", q.value(), best.action(), best.cost(),
                best.costNote(), String.format("%.0f%% chance of losing it", pl * 100), el, bestEl,
                BigDecimal.valueOf(bestNet).setScale(0, RoundingMode.HALF_UP),
                Math.round(best.effectConfidence() * calibrationFactor(v) * 100) / 100.0,
                best.role(), would, "QUOTE_ACTION", null);
    }
}
