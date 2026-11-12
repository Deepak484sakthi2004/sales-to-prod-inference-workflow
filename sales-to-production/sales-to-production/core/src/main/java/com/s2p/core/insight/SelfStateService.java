package com.s2p.core.insight;

import com.s2p.core.CoreContext;
import com.s2p.core.engine.LineReliability;
import com.s2p.core.engine.WorkCalendar;
import com.s2p.core.governance.AutoApprovalRules;
import com.s2p.core.governance.DecisionRecord;
import com.s2p.core.governance.ProposedAction;
import com.s2p.core.knowledge.Assessment;
import com.s2p.core.knowledge.Confidence;
import com.s2p.core.knowledge.Describable;
import com.s2p.core.knowledge.Freshness;
import com.s2p.core.policy.Policy;
import com.s2p.core.sales.QuoteRiskModel;
import com.s2p.core.state.CentralView;
import com.s2p.core.state.Source;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** The system reading its own state: data age, pending work, and how accurate its past forecasts were. */
public final class SelfStateService {
    public record SelfState(List<Freshness> freshness, int pendingActions, Map<String, Long> pendingByType,
                            String oldestPending, int unprocessedMail, List<String> lineHistory, String promiseAccuracy,
                            String quoteModel, List<String> rulesInForce, String policyVersion,
                            List<String> doNotTrustYet, List<String> machinery) implements Describable {
        @Override
        public String describe() {
            StringBuilder b = new StringBuilder("Data:");
            freshness.forEach(f -> b.append("\n  ").append(f.describe()));
            b.append("\nPending work: ").append(pendingActions).append(" action(s) awaiting a person ").append(pendingByType)
                    .append(oldestPending == null ? "" : ", oldest " + oldestPending).append("; ").append(unprocessedMail)
                    .append(" unprocessed mail");
            b.append("\nForecast accuracy:");
            lineHistory.forEach(l -> b.append("\n  ").append(l));
            b.append("\n  ").append(promiseAccuracy).append("\n  ").append(quoteModel);
            b.append("\nRules in force (policy ").append(policyVersion).append("):");
            rulesInForce.forEach(r -> b.append("\n  ").append(r));
            b.append("\nDo not trust yet:");
            doNotTrustYet.forEach(r -> b.append("\n  - ").append(r));
            if (machinery != null && !machinery.isEmpty()) {
                b.append("\nHow I read language and where I keep my own records:");
                machinery.forEach(r -> b.append("\n  ").append(r));
            }
            return b.toString();
        }
    }

    private final CoreContext ctx;
    private final Supplier<Integer> unprocessedMail;

    public SelfStateService(CoreContext ctx, Supplier<Integer> unprocessedMail) {
        this.ctx = ctx;
        this.unprocessedMail = unprocessedMail;
    }

    public Assessment<SelfState> report() {
        CentralView v = ctx.state().view();
        Policy p = ctx.policy().get();
        List<Freshness> fr = List.of(Source.values()).stream().map(v::freshness).toList();
        List<ProposedAction> pending = ctx.actions().pending();
        Map<String, Long> byType = pending.stream().collect(Collectors.groupingBy(ProposedAction::type, Collectors.counting()));
        String oldest = pending.stream().map(ProposedAction::createdAt).min(java.time.OffsetDateTime::compareTo)
                .map(t -> Freshness.ageText(Duration.between(t, v.at()).toMinutes())).orElse(null);
        List<String> lines = LineReliability.compute(v.planning().history()).values().stream()
                .map(LineReliability.LineStats::describe).toList();
        List<DecisionRecord> outcomes = ctx.log().find(r -> r.actualDelivery() != null && r.dateStated() != null);
        long hits = outcomes.stream().filter(r -> !r.actualDelivery().isAfter(r.dateStated())).count();
        String acc = outcomes.isEmpty() ? "Delivery promises: no closed outcomes yet."
                : String.format("Delivery promises I made that have closed: %d/%d delivered on or before the date I gave (%.0f%%).",
                hits, outcomes.size(), 100.0 * hits / outcomes.size());
        QuoteRiskModel.Calibration cal = QuoteRiskModel.calibrate(v.crm().opportunities());
        List<String> rules = new AutoApprovalRules().rules(p, v.today()).stream()
                .map(r -> r.id() + (p.enabledRules().contains(r.id()) ? " ON " : " OFF ") + r.description()
                        + " (approved by " + r.approvedBy() + ", " + WorkCalendar.fmt(r.approvedOn()) + ")").toList();
        List<String> distrust = new ArrayList<>();
        distrust.add("Goodwill cost per late day: a policy estimate, not measured. It drives the ranking of recovery options.");
        distrust.add("Quotation loss model: hand-set weights on " + cal.samples() + " closed quotations. Wrong if a re-fit on "
                + "the next 30 closed quotes moves the Brier score above the base-rate guess.");
        LineReliability.compute(v.planning().history()).values().stream().filter(s -> s.onTimeRate() < 0.75)
                .forEach(s -> distrust.add("Zero-slack dates on " + s.lineId() + ": it finished only "
                        + Math.round(s.onTimeRate() * 100) + "% of work orders on time."));
        fr.stream().filter(f -> !f.fresh()).forEach(f -> distrust.add("Anything that depends on " + f.source() + ": "
                + f.describe()));
        List<String> machinery = new ArrayList<>();
        machinery.add("Language: " + ctx.ai().describeConfig());
        if (ctx.ai().enabled()) {
            ctx.ai().telemetry().describe().forEach(l -> machinery.add("  " + l));
        }
        distrust.add("Language model readings of mail and questions: checks catch values the mail does not support, not "
                + "a plausible misreading that quotes real text (" + ctx.ai().telemetry().rejectedFields() + " reading(s) "
                + "rejected so far). Wrong if a labelled sample of real mail shows checked readings below 98% correct on "
                + "quantity, product and date.");
        machinery.add("Own records: " + ctx.store().describe());
        Confidence.Builder c = Confidence.start();
        fr.stream().filter(Freshness::blocksCommitment).forEach(f -> c.apply(f.source() + "", 0.5, f.describe()));
        fr.stream().filter(f -> !f.fresh() && !f.blocksCommitment()).forEach(f -> c.apply(f.source() + "", 0.85, f.describe()));
        String headline = fr.stream().allMatch(f -> f.fresh() || f.source() == Source.STATUS_FEED)
                ? "All sources fresh; " + pending.size() + " action(s) wait for a person; " + acc
                : "Not all data is fresh (" + fr.stream().filter(f -> !f.fresh()).map(f -> f.source().name())
                .collect(Collectors.joining(", ")) + "); " + pending.size() + " action(s) wait for a person.";
        return new Assessment<>("How sure are you, and how fresh is your data?", headline,
                new SelfState(fr, pending.size(), byType, oldest, unprocessedMail.get(), lines, acc, cal.describe(), rules,
                        p.version(), distrust, machinery), c.build(), fr, List.of(), List.of(), List.of(), null);
    }
}
