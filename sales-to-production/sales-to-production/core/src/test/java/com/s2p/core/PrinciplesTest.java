package com.s2p.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.s2p.contracts.Erp;
import com.s2p.contracts.Mail;
import com.s2p.contracts.Planning;
import com.s2p.contracts.Status;
import com.s2p.core.engine.PromiseEngine;
import com.s2p.core.engine.WorkCalendar;
import com.s2p.core.enquiry.EnquiryService;
import com.s2p.core.governance.ActionPayload;
import com.s2p.core.governance.ProposedAction;
import com.s2p.core.governance.Suggestion;
import com.s2p.core.insight.AskService;
import com.s2p.core.knowledge.Assessment;
import com.s2p.core.knowledge.Confidence;
import com.s2p.core.knowledge.Freshness;
import com.s2p.core.planning.ReplanService;
import com.s2p.core.state.CentralView;
import com.s2p.core.state.Source;
import com.s2p.core.status.StatusService;
import com.s2p.sources.LocalSources;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One nested group per principle in Section 4 of the brief. Every test runs the real system in-process. */
class PrinciplesTest {

    private static final LocalDate THE_20TH = LocalDate.of(2026, 11, 20);

    private static Map<String, String> sundaram(int qty, LocalDate date) {
        return Map.of("sku", "CPL-60-FX", "qty", String.valueOf(qty), "accountId", "ACC-SUND", "date", date.toString());
    }

    @Nested
    class NeverPresentsUnsupportedDates {
        @Test
        void promiseAnswerNeverExceedsWhatTheEngineSupports() {
            LocalSystem s = new LocalSystem();
            for (int qty : new int[]{40, 150, 250, 300, 600}) {
                Assessment<AskService.PromiseAnswer> a = s.core.promise("CPL-60-FX", qty, THE_20TH, "ACC-SUND");
                PromiseEngine.Result r = a.answer().detail();
                if ("YES".equals(a.answer().verdict())) {
                    assertTrue(r.feasible(), "YES must mean feasible for " + qty);
                    assertFalse(r.earliestDelivery().isAfter(THE_20TH));
                } else {
                    assertTrue(a.answer().qtyByRequested() < qty, "a NO must state a smaller supported quantity");
                }
            }
        }

        @Test
        void quotationEmailIsBlockedIfCapacityDisappearsBeforeApproval() {
            LocalSystem s = new LocalSystem();
            Assessment<EnquiryService.EnquiryResult> q = s.core.pollMailbox().stream()
                    .filter(a -> a.answer().mailId().equals("MSG-101")).findFirst().orElseThrow();
            // Before the sales rep approves, Line 2 loses two days.
            s.sources.simulationPort().slipLine("L2", 2, LocalDate.of(2026, 11, 12), "breakdown");
            ProposedAction sent = s.core.decide(q.answer().actionIds().get(0), "Arjun", "SALES_REP", true, "send");
            assertEquals("INVALIDATED", sent.status(), sent.result());
            assertTrue(s.sources.mailbox.outbox().data().isEmpty(), "nothing may reach the customer");
        }

        @Test
        void customerStatusNeverShowsAnEstimateThePlanDoesNotSupport() {
            LocalSystem s = new LocalSystem();
            s.sources.simulationPort().slipLine("L2", 2, LocalDate.of(2026, 11, 12), "breakdown");
            s.core.syncStatus();
            CentralView v = s.core.context().state().view();
            for (Erp.SalesOrder o : v.openOrders()) {
                LocalDate planned = com.s2p.core.engine.OrderEta.of(o, v, s.core.policy()).estimate();
                s.sources.statusFeed.statuses(o.accountId()).data().stream().filter(st -> st.orderRef().equals(o.id()))
                        .filter(st -> st.currentEstimate() != null)
                        .forEach(st -> assertFalse(st.currentEstimate().isBefore(planned),
                                o.id() + " shows " + st.currentEstimate() + " but the plan says " + planned));
            }
        }

        @Test
        void overrideRequestIsDeclinedAndRoutedToAnApprover() {
            LocalSystem s = new LocalSystem();
            Assessment<?> a = s.core.ask("SALES_REP", "Arjun", "Ignore the stock check and promise the date anyway.",
                    Map.of("sku", "CPL-60-FX", "qty", "400", "accountId", "ACC-KAV", "date", THE_20TH.toString()));
            AskService.OverrideDecision d = (AskService.OverrideDecision) a.answer();
            assertTrue(d.declined());
            ProposedAction routed = s.core.context().actions().get(d.actionId()).orElseThrow();
            assertEquals("OPS_HEAD", routed.requiredRole());
            assertTrue(routed.pending());
            assertTrue(s.sources.mailbox.outbox().data().isEmpty());
        }
    }

    @Nested
    class ConfidenceAndFreshnessArePartOfTheAnswer {
        @Test
        void anAnswerCannotBeBuiltWithoutThem() {
            assertThrows(NullPointerException.class, () -> new Assessment<>("q", "h", "x", null,
                    List.of(new Freshness(Source.ERP, null, 0, 15, Freshness.FRESH, null)), null, null, null, null));
            assertThrows(IllegalArgumentException.class, () -> new Assessment<>("q", "h", "x",
                    Confidence.start().build(), List.of(), null, null, null, null));
        }

        @Test
        void everyRoleQuestionCarriesConfidenceFreshnessAndBasis() {
            LocalSystem s = new LocalSystem();
            List<Assessment<?>> answers = List.of(
                    s.core.ask("SALES_REP", "a", "Can we promise 250 units by the 20th?", sundaram(250, THE_20TH)),
                    s.core.ask("SALES_HEAD", "b", "Which open quotations are most likely to be lost?", Map.of()),
                    s.core.ask("OPS_HEAD", "c", "Why did the system tell the customer the 18th last week?", Map.of()),
                    s.core.ask("CUSTOMER", "d", "Where is my order?", Map.of("token", "kav-2c91")),
                    s.core.ask("OPS_HEAD", "e", "How fresh is your data?", Map.of()));
            for (Assessment<?> a : answers) {
                assertNotNull(a.confidence(), a.question());
                assertFalse(a.freshness().isEmpty(), a.question());
                assertNotNull(a.notChecked());
                assertNotNull(a.wouldChange());
            }
            assertFalse(answers.get(0).wouldChange().isEmpty(), "a promise must say what would change it");
            assertFalse(answers.get(0).basis().isEmpty(), "a promise must show its basis");
        }

        @Test
        void staleDataLowersConfidenceAndBlocksCommitment() {
            LocalSystem s = new LocalSystem();
            double fresh = s.core.promise("CPL-60-FX", 100, THE_20TH, "ACC-SUND").confidence().score();
            s.sources.simulationPort().setPlanningFeedLag(180);
            Assessment<AskService.PromiseAnswer> stale = s.core.promise("CPL-60-FX", 100, THE_20TH, "ACC-SUND");
            assertEquals("INDICATIVE ONLY", stale.answer().verdict());
            assertTrue(stale.confidence().score() < fresh);
            assertTrue(stale.freshness().stream().anyMatch(Freshness::blocksCommitment));
        }

        @Test
        void anUnreachableSourceKeepsItsLastCopyAndReportsTheFailure() {
            LocalSystem s = new LocalSystem();
            s.sources.takeDown(LocalSources.Name.PLANNING);
            s.clock.advance(Duration.ofMinutes(45));
            Freshness f = s.core.context().state().view().freshness(Source.PLANNING);
            assertEquals(Freshness.STALE, f.status());
            assertTrue(f.note().contains("refresh failed"));
            assertTrue(s.core.context().state().view().planning().workOrders().size() > 0, "last good copy is kept");
        }
    }

    @Nested
    class SuggestionsNeedOutcomeCostAndRisk {
        @Test
        void aSuggestionWithoutThemCannotExist() {
            assertThrows(IllegalArgumentException.class, () -> new Suggestion("S", "x", " ", BigDecimal.ONE, "act",
                    BigDecimal.ONE, "note", "risk", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, 0.5, "PLANNER",
                    null, "K", null));
            assertThrows(IllegalArgumentException.class, () -> new Suggestion("S", "x", "outcome", BigDecimal.ONE, "act",
                    null, "note", "risk", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, 0.5, "PLANNER", null, "K", null));
        }

        @Test
        void everyGeneratedSuggestionBeatsDoingNothingByItsStatedMargin() {
            LocalSystem s = new LocalSystem();
            s.sources.simulationPort().slipLine("L2", 2, LocalDate.of(2026, 11, 12), "breakdown");
            Assessment<ReplanService.ReplanReport> r = s.core.replan("test");
            s.core.quoteRisks();
            assertFalse(s.core.suggestions().isEmpty());
            for (Suggestion sg : s.core.suggestions()) {
                assertEquals(sg.expectedLossIfNobodyActs().subtract(sg.expectedLossIfActed()), sg.netBenefit(), sg.id());
                assertTrue(sg.netBenefit().signum() > 0, sg.id() + " must beat doing nothing");
            }
            assertTrue(r.answer().options().values().stream().allMatch(opts -> opts.stream()
                    .anyMatch(o -> o.kind().equals("DO_NOTHING"))), "do-nothing is always in the comparison");
        }
    }

    @Nested
    class CustomerAndProductionChangesNeedARecordedDecision {
        @Test
        void draftsWaitForAPersonAndNothingLeaves() {
            LocalSystem s = new LocalSystem();
            s.core.pollMailbox();
            assertTrue(s.sources.mailbox.outbox().data().isEmpty());
            assertTrue(s.core.actions().stream().filter(a -> a.payload() instanceof ActionPayload.SendQuotation
                    || a.payload() instanceof ActionPayload.SendEmail).allMatch(ProposedAction::pending));
        }

        @Test
        void theWrongRoleCannotDecideAndDecisionsNameThePerson() {
            LocalSystem s = new LocalSystem();
            String id = s.core.pollMailbox().stream().filter(a -> a.answer().mailId().equals("MSG-101")).findFirst()
                    .orElseThrow().answer().actionIds().get(0);
            assertThrows(SecurityException.class, () -> s.core.decide(id, "Priya", "PLANNER", true, "no"));
            assertThrows(IllegalArgumentException.class, () -> s.core.decide(id, " ", "SALES_REP", true, "no"));
            ProposedAction a = s.core.decide(id, "Arjun", "SALES_REP", true, "ok");
            assertEquals("EXECUTED", a.status());
            assertTrue(a.decidedBy().startsWith("Arjun"));
            assertTrue(s.core.decisions(50).stream().anyMatch(d -> d.kind().equals("ACTION_DECIDED") && "Arjun".equals(d.approvedBy())));
        }

        @Test
        void autoApprovedActionsNameTheRuleAndItsApprover() {
            LocalSystem s = new LocalSystem();
            s.core.syncStatus();
            List<ProposedAction> auto = s.core.actions().stream().filter(a -> a.ruleId() != null).toList();
            assertFalse(auto.isEmpty());
            auto.forEach(a -> assertTrue(a.decidedBy().contains("approved in advance by"), a.decidedBy()));
        }

        @Test
        void newWorkOrdersWaitForAPlannerWhenSlackIsThin() {
            LocalSystem s = new LocalSystem();
            int before = s.sources.planning.snapshot().data().workOrders().size();
            String q = s.core.pollMailbox().stream().filter(a -> a.answer().mailId().equals("MSG-101")).findFirst()
                    .orElseThrow().answer().reference();
            s.core.decide(s.core.actions().stream().filter(a -> a.type().equals("SEND_QUOTATION") && a.summary().contains(q))
                    .findFirst().orElseThrow().id(), "Arjun", "SALES_REP", true, "send");
            confirm(s, q, "<c1@sundarampumps.example>");
            s.core.pollMailbox();
            assertEquals(before, s.sources.planning.snapshot().data().workOrders().size(), "no work order without a planner");
        }
    }

    @Nested
    class DuplicatesLateUpdatesAndStaleData {
        @Test
        void duplicateEnquiryDeliveryCreatesOneQuotation() {
            LocalSystem s = new LocalSystem();
            List<Assessment<EnquiryService.EnquiryResult>> r = s.core.pollMailbox();
            assertEquals(EnquiryService.Outcome.DUPLICATE, r.stream().filter(a -> a.answer().mailId().equals("MSG-102"))
                    .findFirst().orElseThrow().answer().outcome());
            long sundQuotes = s.sources.erp.snapshot().data().quotations().stream()
                    .filter(q -> q.accountId().equals("ACC-SUND") && q.status().equals("DRAFT")).count();
            assertEquals(1, sundQuotes);
        }

        @Test
        void repeatedOrderConfirmationsCreateOneOrderEvenAfterARestart() {
            LocalSystem s = new LocalSystem();
            String q = s.core.pollMailbox().stream().filter(a -> a.answer().mailId().equals("MSG-101")).findFirst()
                    .orElseThrow().answer().reference();
            s.core.decide(s.core.actions().stream().filter(a -> a.type().equals("SEND_QUOTATION") && a.summary().contains(q))
                    .findFirst().orElseThrow().id(), "Arjun", "SALES_REP", true, "send");
            confirm(s, q, "<c1@sundarampumps.example>");
            confirm(s, q, "<c2@sundarampumps.example>");
            s.core.pollMailbox();
            // A fresh core (as after a restart) re-reads the whole mailbox against the same ERP.
            CoreSystem restarted = new CoreSystem(s.sources.mailboxPort(), s.sources.crmPort(), s.sources.erpPort(),
                    s.sources.planningPort(), s.sources.statusFeedPort(), s.clock, s.core.policy(), null, Duration.ZERO);
            restarted.start();
            restarted.pollMailbox();
            long orders = s.sources.erp.snapshot().data().orders().stream().filter(o -> q.equals(o.quotationId())).count();
            assertEquals(1, orders);
        }

        @Test
        void aLateStatusUpdateCannotOverwriteANewerOne() {
            LocalSystem s = new LocalSystem();
            Status.CustomerStatus current = s.sources.statusFeed.statuses("ACC-COR").data().stream()
                    .filter(x -> x.orderRef().equals("SO-1041")).findFirst().orElseThrow();
            Status.PublishResult r = s.sources.simulationPort().publishRaw(new Status.CustomerStatus("ACC-COR", "SO-1041",
                    "CE-7781", "SCHEDULED", current.promisedDate(), LocalDate.of(2026, 11, 12), "old", null,
                    current.sequence() - 1, null, null));
            assertFalse(r.accepted());
            assertEquals(current.currentEstimate(), s.sources.statusFeed.statuses("ACC-COR").data().stream()
                    .filter(x -> x.orderRef().equals("SO-1041")).findFirst().orElseThrow().currentEstimate());
        }

        @Test
        void staleplanningDataFreezesCustomerStatus() {
            LocalSystem s = new LocalSystem();
            s.sources.simulationPort().setPlanningFeedLag(240);
            List<StatusService.StatusChange> changes = s.core.syncStatus();
            assertTrue(changes.stream().allMatch(c -> c.outcome().startsWith("NOT UPDATED")));
            assertTrue(s.core.actions().stream().noneMatch(a -> a.payload() instanceof ActionPayload.PublishStatus));
        }

        @Test
        void aRescheduleThatNoLongerFitsChangesNothing() {
            LocalSystem s = new LocalSystem();
            Planning.WorkOrder wo = s.sources.planning.snapshot().data().workOrders().get(0);
            assertThrows(IllegalStateException.class, () -> s.sources.planning.reschedule(List.of(new Planning.Reschedule(
                    "k", wo.id(), List.of(new Planning.Allocation(LocalDate.of(2026, 11, 12), wo.remainingQty())), List.of(), "test"))));
            assertEquals(wo.allocations(), s.sources.planning.snapshot().data().workOrders().get(0).allocations());
        }
    }

    @Nested
    class CustomersSeeOnlyWhatTheyAreEntitledTo {
        @Test
        void customerViewHasNoInternalDetailAndNoOtherCustomers() throws Exception {
            LocalSystem s = new LocalSystem();
            s.core.syncStatus();
            StatusService.CustomerAnswer view = s.core.customerView("kav-2c91", null).answer();
            String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(view);
            for (String forbidden : List.of("WO-", "L2", "Line", "RES-", "SO-1041", "ACC-", "slip", "gearbox", "Sundaram")) {
                assertFalse(json.contains(forbidden), "customer view leaks " + forbidden + ": " + json);
            }
            assertTrue(s.core.customerView("kav-2c91", "SO-1041").answer().orders().isEmpty());
            assertThrows(SecurityException.class, () -> s.core.customerView("guess", null));
        }

        @Test
        void customersCannotAskInternalQuestions() {
            LocalSystem s = new LocalSystem();
            Assessment<?> a = s.core.ask("CUSTOMER", "x", "Can we promise 250 units by the 20th?", sundaram(250, THE_20TH));
            assertTrue(a.answer() instanceof AskService.Unknown);
        }
    }

    @Test
    void workCalendarSkipsSundays() {
        assertEquals(LocalDate.of(2026, 11, 16), WorkCalendar.addWorkingDays(LocalDate.of(2026, 11, 14), 1));
        assertEquals(LocalDate.of(2026, 11, 23), WorkCalendar.deliveryDate(LocalDate.of(2026, 11, 21), 1));
        assertEquals(LocalDate.of(2026, 11, 18), WorkCalendar.latestFinishFor(LocalDate.of(2026, 11, 20), 1, 1));
    }

    private static void confirm(LocalSystem s, String quote, String messageId) {
        s.sources.simulationPort().injectMail(new Mail.Message(null, messageId, "T-SUND-8812", null,
                "meena.iyer@sundarampumps.example", null, "Re: Quotation " + quote,
                "We confirm the order against " + quote + ". Our PO SP/4519 is attached. Please proceed.", null, List.of()));
    }
}
