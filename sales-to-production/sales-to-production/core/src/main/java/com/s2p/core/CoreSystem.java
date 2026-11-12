package com.s2p.core;

import com.s2p.contracts.Erp;
import com.s2p.contracts.Planning;
import com.s2p.contracts.Ports;
import com.s2p.contracts.SimClock;
import com.s2p.contracts.Status;
import com.s2p.core.ai.AiGateway;
import com.s2p.core.engine.CapacityModel;
import com.s2p.core.engine.Money;
import com.s2p.core.engine.OrderEta;
import com.s2p.core.engine.PromiseAssessor;
import com.s2p.core.engine.WorkCalendar;
import com.s2p.core.enquiry.EnquiryReader;
import com.s2p.core.enquiry.EnquiryService;
import com.s2p.core.governance.ActionExecutor;
import com.s2p.core.governance.ActionQueue;
import com.s2p.core.governance.AutoApprovalRules;
import com.s2p.core.governance.DecisionLog;
import com.s2p.core.governance.DecisionRecord;
import com.s2p.core.governance.ProposedAction;
import com.s2p.core.governance.Suggestion;
import com.s2p.core.insight.AskPlanner;
import com.s2p.core.insight.AskService;
import com.s2p.core.insight.ExplainService;
import com.s2p.core.insight.SelfStateService;
import com.s2p.core.insight.TraceService;
import com.s2p.core.knowledge.Assessment;
import com.s2p.core.knowledge.Freshness;
import com.s2p.core.persistence.CoreStore;
import com.s2p.core.persistence.memory.InMemoryCoreStore;
import com.s2p.core.planning.OrderPlanningService;
import com.s2p.core.planning.ReplanService;
import com.s2p.core.policy.Policy;
import com.s2p.core.sales.QuotationRiskService;
import com.s2p.core.state.CentralView;
import com.s2p.core.state.Source;
import com.s2p.core.state.StateStore;
import com.s2p.core.status.StatusService;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * The intelligence layer, framework-free. Spring wires it to HTTP adapters; tests and the scenario runner wire it to
 * in-process adapters. Sales, planning, production and customer status all read the same {@link StateStore}.
 */
public final class CoreSystem implements AutoCloseable {
    public record OrderRow(String orderId, String account, String customerPo, String product, String value,
                           LocalDate committed, LocalDate estimate, Integer slack, String state, String stage,
                           String customerSees) {
    }

    public record DayLoad(LocalDate date, int available, int booked, int free, String note) {
    }

    public record LineLoad(String lineId, String name, List<DayLoad> days) {
    }

    public record PlannerView(OffsetDateTime at, List<Freshness> freshness, List<OrderRow> orders,
                              List<LineLoad> lines, List<ProposedAction> pending, List<Suggestion> suggestions,
                              List<DecisionRecord> recent, String policyVersion) {
    }

    public record CatalogProduct(String sku, String name, String family) {
    }

    public record CatalogAccount(String id, String name, String tier, String region, String contactEmail) {
    }

    public record CatalogWorkOrder(String id, String orderId, String sku, String lineId, int qty, int completedQty,
                                   String status, LocalDate plannedFinish) {
    }

    public record Catalog(List<CatalogProduct> products, List<CatalogAccount> accounts, List<String> lines,
                          List<CatalogWorkOrder> workOrders, LocalDate today) {
    }

    public record RuleView(String id, String description, String approvedBy, LocalDate approvedOn, boolean enabled) {
    }

    public record PolicyView(String version, int minSlackWorkingDays, int qcWorkingDays, boolean allowAlternateLines,
                             Map<String, Double> goodwillPctPerLateDay, long planningSlaMinutes,
                             long commitLimitMinutes, List<RuleView> rules) {
    }

    private final AtomicReference<Policy> policy;
    private final CoreContext ctx;
    private final EnquiryService enquiries;
    private final ReplanService replan;
    private final StatusService status;
    private final QuotationRiskService quotes;
    private final TraceService trace;
    private final SelfStateService self;
    private final ExplainService explain;
    private final AskService ask;

    /** In-memory store and no language model: mail waits unread; promise checks work from structured input. */
    public CoreSystem(Ports.MailboxPort mailbox, Ports.CrmPort crm, Ports.ErpPort erp, Ports.PlanningPort planning,
                      Ports.StatusFeedPort statusFeed, SimClock clock, Policy initial, Path decisionLogFile,
                      Duration refreshInterval) {
        this(mailbox, crm, erp, planning, statusFeed, clock, initial, decisionLogFile, refreshInterval,
                new InMemoryCoreStore(), AiGateway.disabled());
    }

    /**
     * @param store where the core keeps its decision log, actions, suggestions and enquiry ledger
     * @param ai    the language model gateway; {@link AiGateway#disabled()} leaves mail unread
     */
    public CoreSystem(Ports.MailboxPort mailbox, Ports.CrmPort crm, Ports.ErpPort erp, Ports.PlanningPort planning,
                      Ports.StatusFeedPort statusFeed, SimClock clock, Policy initial, Path decisionLogFile,
                      Duration refreshInterval, CoreStore store, AiGateway ai) {
        this.policy = new AtomicReference<>(initial);
        StateStore state = new StateStore(mailbox, crm, erp, planning, clock, policy::get, refreshInterval);
        DecisionLog log = new DecisionLog(store, decisionLogFile, clock);
        log.seed(clock.today());
        ActionQueue actions = new ActionQueue(store, new AutoApprovalRules(), log, clock, policy::get);
        actions.setExecutor(new ActionExecutor(mailbox, erp, planning, statusFeed, state, policy::get, log));
        this.ctx = new CoreContext(state, policy::get, clock, actions, log, new PromiseAssessor(),
                new SuggestionBoard(store, actions), mailbox, crm, erp, planning, statusFeed, store, ai);
        OrderPlanningService planner = new OrderPlanningService(ctx);
        this.enquiries = new EnquiryService(ctx, planner, new EnquiryReader(ai));
        this.replan = new ReplanService(ctx);
        this.status = new StatusService(ctx);
        this.quotes = new QuotationRiskService(ctx);
        this.trace = new TraceService(ctx);
        this.self = new SelfStateService(ctx, () -> enquiries.unprocessed(ctx.state().view()));
        this.explain = new ExplainService(ctx);
        this.ask = new AskService(ctx, replan, status, quotes, trace, self, new AskPlanner(ctx, ai));
    }

    public void start() {
        ctx.state().refreshAll();
        replan.baseline();
    }

    /** One polling cycle: read new mail, re-plan on new production events, bring customer status in line. */
    public synchronized List<String> tick() {
        List<String> notes = new ArrayList<>();
        enquiries.poll().forEach(a -> notes.add(a.headline()));
        if (replan.newEvents() > 0) {
            notes.add(replan.replan("planning events").headline());
        }
        status.sync().forEach(c -> notes.add(c.describe()));
        return notes;
    }

    public CoreContext context() {
        return ctx;
    }

    public Policy policy() {
        return policy.get();
    }

    public void setPolicy(Policy p, String who, String why) {
        Policy old = policy.getAndSet(p);
        ctx.log().record("POLICY_CHANGED", who, null, null, null, "Policy " + old.version() + " -> " + p.version()
                + ": " + why, null, null, List.of(), ctx.state().view(), null, null, who, null, p.version());
    }

    public List<Assessment<EnquiryService.EnquiryResult>> enquiries() {
        return enquiries.history();
    }

    /** Pick-lists for the frontend: products, customers, lines and open work orders from the current view. */
    public Catalog catalog() {
        CentralView v = ctx.state().view();
        return new Catalog(
                v.erp().products().stream().map(p -> new CatalogProduct(p.sku(), p.name(), p.family())).toList(),
                v.crm().accounts().stream().map(a -> new CatalogAccount(a.id(), a.name(), a.tier(), a.region(),
                        v.primaryContact(a.id()).map(c -> c.email()).orElse(null))).toList(),
                v.planning().lines().stream().map(Planning.Line::id).toList(),
                v.planning().workOrders().stream().filter(w -> !CapacityModel.closed(w))
                        .map(w -> new CatalogWorkOrder(w.id(), w.orderId(), w.sku(), w.lineId(), w.qty(), w.completedQty(),
                                w.status(), w.plannedFinish())).toList(),
                v.today());
    }

    public PolicyView policyView() {
        Policy p = policy.get();
        LocalDate today = ctx.clock().today();
        return new PolicyView(p.version(), p.minSlackWorkingDays(), p.qcWorkingDays(), p.allowAlternateLines(),
                p.goodwillPctPerLateDay(), p.sla(Source.PLANNING).toMinutes(),
                p.commitLimit(Source.PLANNING) == null ? 0 : p.commitLimit(Source.PLANNING).toMinutes(),
                new AutoApprovalRules().rules(p, today).stream().map(r -> new RuleView(r.id(), r.description(),
                        r.approvedBy(), r.approvedOn(), p.enabledRules().contains(r.id()))).toList());
    }

    public List<Assessment<EnquiryService.EnquiryResult>> pollMailbox() {
        return enquiries.poll();
    }

    public Assessment<AskService.PromiseAnswer> promise(String sku, int qty, LocalDate date, String accountId) {
        Map<String, String> c = new HashMap<>();
        c.put("sku", sku);
        c.put("qty", String.valueOf(qty));
        c.put("accountId", accountId);
        if (date != null) {
            c.put("date", date.toString());
        }
        return ask.promise("Can we promise " + qty + " x " + sku + (date == null ? "" : " by " + date) + "?", c);
    }

    public Assessment<ReplanService.ReplanReport> replan(String trigger) {
        return replan.replan(trigger);
    }

    public List<StatusService.StatusChange> syncStatus() {
        replan.observeNewOrders();
        return status.sync();
    }

    public Assessment<StatusService.CustomerAnswer> customerView(String token, String orderRef) {
        return status.customerView(token, orderRef);
    }

    public Assessment<QuotationRiskService.QuoteRiskReport> quoteRisks() {
        return quotes.rank();
    }

    public Assessment<?> ask(String role, String user, String text, Map<String, String> context) {
        return ask.ask(role, user, text, context);
    }

    /** The answer with how the question was read (model or rules) and the customer, product and date actually used. */
    public AskService.Answer askWithReading(String role, String user, String text, Map<String, String> context) {
        return ask.answer(role, user, text, context);
    }

    @Override
    public void close() {
        ctx.store().close();
    }

    public Assessment<TraceService.TraceReport> trace(String question, String accountId, String orderId,
                                                      LocalDate dateStated, LocalDate saidFrom, LocalDate saidTo) {
        return trace.trace(question, accountId, orderId, dateStated, saidFrom, saidTo);
    }

    public Assessment<SelfStateService.SelfState> selfState() {
        return self.report();
    }

    public ExplainService.Explained explain(String key) {
        return explain.explain(key);
    }

    public ProposedAction decide(String actionId, String who, String role, boolean approve, String note) {
        return ctx.actions().decide(actionId, who, role, approve, note);
    }

    public ProposedAction acceptSuggestion(String id, String who, String role, String note) {
        return ctx.suggestions().accept(id, who, role, note);
    }

    public List<ProposedAction> actions() {
        return ctx.actions().all();
    }

    public List<Suggestion> suggestions() {
        return ctx.suggestions().all();
    }

    public List<DecisionRecord> decisions(int n) {
        return ctx.log().recent(n);
    }

    public PlannerView plannerView() {
        CentralView v = ctx.state().view();
        Policy p = policy.get();
        Map<String, List<Status.CustomerStatus>> published = new HashMap<>();
        List<OrderRow> rows = new ArrayList<>();
        for (Erp.SalesOrder o : v.openOrders()) {
            OrderEta eta = OrderEta.of(o, v, p);
            List<Status.CustomerStatus> st = published.computeIfAbsent(o.accountId(), a -> {
                try {
                    return ctx.statusFeed().statuses(a).data();
                } catch (RuntimeException e) {
                    return List.of();
                }
            });
            String sees = st.stream().filter(s -> s.orderRef().equals(o.id())).findFirst()
                    .map(s -> s.stage() + ", " + (s.currentEstimate() == null ? "date being reconfirmed"
                            : WorkCalendar.fmt(s.currentEstimate()))).orElse("nothing published");
            String state = eta.estimate() == null ? "UNPLANNED" : eta.lateDays() > 0 ? "LATE"
                    : eta.atRisk(p) ? "AT_RISK" : "ON_TRACK";
            rows.add(new OrderRow(o.id(), v.accountName(o.accountId()), o.customerPo(), o.lines().stream()
                    .map(l -> l.qty() + " x " + l.sku()).collect(Collectors.joining(", ")), Money.inr(o.value()),
                    eta.committed(), eta.estimate(), eta.slackWorkingDays(), state, eta.stage(), sees));
        }
        rows.sort((a, b) -> a.state().equals(b.state()) ? a.orderId().compareTo(b.orderId())
                : Integer.compare(rank(a.state()), rank(b.state())));
        CapacityModel cap = CapacityModel.from(v.planning(), Set.of());
        List<LineLoad> lines = new ArrayList<>();
        for (Planning.Line l : v.planning().lines()) {
            List<DayLoad> days = new ArrayList<>();
            for (LocalDate d = v.today(); days.size() < 12; d = d.plusDays(1)) {
                if (WorkCalendar.isWorkingDay(d)) {
                    LocalDate day = d;
                    String note = v.planning().capacity().stream().filter(c -> c.lineId().equals(l.id()) && c.date().equals(day))
                            .map(Planning.CapacityDay::note).filter(java.util.Objects::nonNull).findFirst().orElse(null);
                    days.add(new DayLoad(d, cap.available(l.id(), d), cap.booked(l.id(), d), cap.free(l.id(), d), note));
                }
            }
            lines.add(new LineLoad(l.id(), l.name(), days));
        }
        List<Freshness> fr = List.of(Source.values()).stream().map(v::freshness).toList();
        return new PlannerView(v.at(), fr, rows, lines, ctx.actions().pending(), ctx.suggestions().all(),
                ctx.log().recent(15), p.version());
    }

    private static int rank(String state) {
        return switch (state) {
            case "LATE" -> 0;
            case "AT_RISK" -> 1;
            case "UNPLANNED" -> 2;
            default -> 3;
        };
    }
}
