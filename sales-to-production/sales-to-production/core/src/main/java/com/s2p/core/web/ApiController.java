package com.s2p.core.web;

import com.s2p.contracts.Mail;
import com.s2p.contracts.Planning;
import com.s2p.contracts.SimClock;
import com.s2p.core.CoreSystem;
import com.s2p.core.ai.AiGateway;
import com.s2p.core.insight.AskService;
import com.s2p.core.config.CoreProperties;
import com.s2p.core.config.CoreWiring;
import com.s2p.core.config.HttpPorts;
import com.s2p.core.enquiry.EnquiryService;
import com.s2p.core.governance.DecisionRecord;
import com.s2p.core.governance.ProposedAction;
import com.s2p.core.governance.Suggestion;
import com.s2p.core.insight.ExplainService;
import com.s2p.core.knowledge.Assessment;
import com.s2p.core.scenarios.Render;
import com.s2p.core.scenarios.ScenarioSuite;
import com.s2p.core.status.StatusService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Internal API for sales, planning, production and management. Roles are passed by the caller and trusted: in this
 * build there is no login (see the design note). Every read returns an Assessment with confidence and freshness.
 */
@RestController
@RequestMapping("/api")
public class ApiController {
    public record AskRequest(String role, String user, String text, Map<String, String> context) {
    }

    public record DecisionRequest(String who, String role, boolean approve, String note) {
    }

    public record SlipRequest(String lineId, int workingDays, LocalDate from, String reason) {
    }

    /**
     * {@code readAs}: how the question was understood; {@code resolved}: the customer, product, quantity and date
     * actually used; {@code reader}: "model", "rules" or "rules (model unavailable)".
     */
    public record AskReply(String headline, String text, String reasoning, Assessment<?> assessment, String readAs,
                           Map<String, String> resolved, String reader) {
    }

    private final CoreSystem core;
    private final HttpPorts ports;
    private final SimClock clock;
    private final CoreProperties props;
    private final Poller poller;
    private final AiGateway ai;

    public ApiController(CoreSystem core, HttpPorts ports, SimClock clock, CoreProperties props, Poller poller,
                         AiGateway ai) {
        this.ai = ai;
        this.core = core;
        this.ports = ports;
        this.clock = clock;
        this.props = props;
        this.poller = poller;
    }

    @GetMapping("/catalog")
    public CoreSystem.Catalog catalog() {
        return core.catalog();
    }

    @GetMapping("/enquiries")
    public List<Assessment<EnquiryService.EnquiryResult>> enquiries() {
        return core.enquiries();
    }

    @GetMapping("/policy")
    public CoreSystem.PolicyView policy() {
        return core.policyView();
    }

    @GetMapping("/views/planner")
    public CoreSystem.PlannerView planner() {
        return core.plannerView();
    }

    @PostMapping("/ask")
    public AskReply ask(@RequestBody AskRequest r) {
        AskService.Answer ans = core.askWithReading(r.role(), r.user() == null ? r.role() : r.user(), r.text(),
                r.context() == null ? Map.of() : r.context());
        Assessment<?> a = ans.assessment();
        return new AskReply(a.headline(), Render.output(a), Render.reasoning(a), a, ans.readAs(), ans.resolved(),
                ans.reader());
    }

    @GetMapping("/promise")
    public Assessment<?> promise(@RequestParam("sku") String sku, @RequestParam("qty") int qty,
                                 @RequestParam(name = "date", required = false) LocalDate date,
                                 @RequestParam("accountId") String accountId) {
        return core.promise(sku, qty, date, accountId);
    }

    @PostMapping("/mailbox/poll")
    public List<Assessment<EnquiryService.EnquiryResult>> poll() {
        return core.pollMailbox();
    }

    @PostMapping("/replan")
    public Assessment<?> replan(@RequestParam(name = "trigger", defaultValue = "manual") String trigger) {
        return core.replan(trigger);
    }

    @PostMapping("/status/sync")
    public List<StatusService.StatusChange> sync() {
        return core.syncStatus();
    }

    @PostMapping("/tick")
    public List<String> tick() {
        return poller.run();
    }

    @GetMapping("/quotations/at-risk")
    public Assessment<?> quotes() {
        return core.quoteRisks();
    }

    /** The question is read by the language model (which statement, which dates, which period), as in /ask. */
    @GetMapping("/trace")
    public Assessment<?> trace(@RequestParam("q") String q,
                               @RequestParam(name = "accountId", required = false) String accountId,
                               @RequestParam(name = "orderId", required = false) String orderId) {
        Map<String, String> c = new LinkedHashMap<>();
        if (accountId != null && !accountId.isBlank()) {
            c.put("accountId", accountId);
        }
        if (orderId != null && !orderId.isBlank()) {
            c.put("orderId", orderId);
        }
        return core.ask("OPS_HEAD", "operations view", q, c);
    }

    @GetMapping("/self")
    public Assessment<?> self() {
        return core.selfState();
    }

    @GetMapping("/explain")
    public ExplainService.Explained explain(@RequestParam("key") String key) {
        return core.explain(key);
    }

    @GetMapping("/actions")
    public List<ProposedAction> actions() {
        return core.actions();
    }

    @PostMapping("/actions/{id}/decision")
    public ProposedAction decide(@PathVariable("id") String id, @RequestBody DecisionRequest d) {
        return core.decide(id, d.who(), d.role(), d.approve(), d.note());
    }

    @GetMapping("/suggestions")
    public List<Suggestion> suggestions() {
        return core.suggestions();
    }

    @PostMapping("/suggestions/{id}/accept")
    public ProposedAction accept(@PathVariable("id") String id, @RequestBody DecisionRequest d) {
        return core.acceptSuggestion(id, d.who(), d.role(), d.note());
    }

    @GetMapping("/decisions")
    public List<DecisionRecord> decisions(@RequestParam(name = "n", defaultValue = "50") int n) {
        return core.decisions(n);
    }

    // ---- change a requirement in the room

    @PostMapping("/policy/min-slack")
    public Map<String, Object> minSlack(@RequestParam("days") int days, @RequestParam("who") String who) {
        core.setPolicy(core.policy().withMinSlack(days), who, "minimum slack set to " + days + " working day(s)");
        return Map.of("policy", core.policy().version(), "minSlackWorkingDays", days);
    }

    @PostMapping("/policy/rules/{id}")
    public Map<String, Object> rule(@PathVariable("id") String id, @RequestParam("enabled") boolean enabled,
                                    @RequestParam("who") String who) {
        core.setPolicy(core.policy().withRule(id, enabled), who, "rule " + id + (enabled ? " enabled" : " disabled"));
        return Map.of("policy", core.policy().version(), "enabledRules", core.policy().enabledRules());
    }

    // ---- stand-in controls, so a review set can be injected from one place

    @PostMapping("/sim/mail")
    public Mail.Message injectMail(@RequestBody Mail.Message m) {
        return ports.simulation().injectMail(m);
    }

    @PostMapping("/sim/slip")
    public Planning.Event slip(@RequestBody SlipRequest s) {
        return ports.simulation().slipLine(s.lineId(), s.workingDays(), s.from(), s.reason());
    }

    @PostMapping("/sim/feed-lag")
    public Map<String, Integer> lag(@RequestParam("minutes") int minutes) {
        ports.simulation().setPlanningFeedLag(minutes);
        return Map.of("planningFeedLagMinutes", minutes);
    }

    @PostMapping("/sim/progress")
    public Planning.Event progress(@RequestParam("workOrderId") String wo, @RequestParam("completedQty") int qty) {
        return ports.simulation().reportProgress(wo, qty);
    }

    /**
     * Runs the full scenario suite against the live stand-ins with a fresh intelligence layer, after resetting every
     * stand-in to its seed. Returns the run log as Markdown. The polling loop is paused for the duration.
     */
    @PostMapping(value = "/scenarios/run", produces = MediaType.TEXT_PLAIN_VALUE)
    public String runScenarios() {
        poller.pause(true);
        try {
            ports.resetAll();
            CoreSystem fresh = CoreWiring.scratch(ports, clock, props, ai);
            List<ScenarioSuite.Step> steps = new ScenarioSuite(fresh, ports.simulation()).run();
            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("mode", "live HTTP stand-ins");
            meta.put("business time", clock.now().toString());
            return ScenarioSuite.text(steps, "RUN LOG (live)", "Generated by POST /api/scenarios/run against the "
                    + "running stand-ins " + meta + ", with mail, questions and CRM notes read by the language model. "
                    + "Stand-ins were reset to their seed first.");
        } finally {
            ports.resetAll();
            poller.pause(false);
        }
    }
}
