package com.s2p.core;

import com.s2p.core.ai.AiDtos;
import com.s2p.core.insight.AskService;
import com.s2p.core.insight.TraceService;
import com.s2p.core.persistence.memory.InMemoryCoreStore;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The model reads the question and picks a service; the core decides what is allowed and checks every argument. */
class AskPlannerTest {
    private final FakeAi ai = new FakeAi();

    private LocalSystem system() {
        return new LocalSystem(new InMemoryCoreStore(), ai);
    }

    private static AiDtos.AskPlan plan(String tool, Map<String, Object> args) {
        return new AiDtos.AskPlan(tool, args, "promise check for Sundaram", "fake");
    }

    @Test
    void aQuestionInPlainWordsIsAnsweredFromTheEngine() {
        ai.plan = r -> plan("check_promise", Map.of("sku", "CPL-60-FX", "qty", 250, "date", "2026-11-20", "account_id", "ACC-SUND"));
        AskService.Answer a = system().core.askWithReading("SALES_REP", "Arjun", "Can we get 250 to Sundaram before the 20th?", Map.of());
        AskService.PromiseAnswer p = assertInstanceOf(AskService.PromiseAnswer.class, a.assessment().answer());
        assertEquals(250, p.qty());
        assertEquals("CPL-60-FX", p.sku());
        assertEquals("model", a.reader());
        assertEquals("ACC-SUND", a.resolved().get("accountId"));
        assertTrue(a.readAs().contains("check_promise"), a.readAs());
        assertTrue(a.assessment().wouldChange().contains(a.readAs()));
    }

    @Test
    void aCustomerIsOfferedOnlyTheStatusToolAndCannotRunAnythingElse() {
        ai.plan = r -> plan("check_promise", Map.of("sku", "CPL-60-FX", "qty", 250, "account_id", "ACC-SUND"));
        AskService.Answer a = system().core.askWithReading("CUSTOMER", "kav", "Can you promise 250 by the 20th?",
                Map.of("token", "kav-2c91"));
        assertEquals(2, ai.planCalls.get(0).tools().size());
        assertFalse(ai.planCalls.get(0).context().containsKey("token"), "the customer token never goes to the model");
        assertInstanceOf(AskService.Unknown.class, a.assessment().answer());
        assertTrue(a.readAs().contains("may not use"), a.readAs());
    }

    @Test
    void anArgumentThatIsNotInTheDataIsIgnored() {
        ai.plan = r -> plan("check_promise", Map.of("sku", "CPL-99-XX", "qty", 250, "account_id", "ACC-SUND"));
        AskService.Answer a = system().core.askWithReading("SALES_REP", "Arjun", "Can we promise 250 of the 99mm?", Map.of());
        assertInstanceOf(AskService.Unknown.class, a.assessment().answer());
        assertTrue(a.readAs().contains("CPL-99-XX is not in the catalogue"), a.readAs());
    }

    @Test
    void theUsersSelectionWinsOverTheModelsReading() {
        ai.plan = r -> plan("check_promise", Map.of("sku", "CPL-60-FX", "qty", 250, "account_id", "ACC-SUND"));
        AskService.Answer a = system().core.askWithReading("SALES_REP", "Arjun", "Can we promise 250 by the 20th?",
                Map.of("accountId", "ACC-KAV"));
        assertEquals("ACC-KAV", a.resolved().get("accountId"));
        assertTrue(a.readAs().contains("your selection"), a.readAs());
    }

    @Test
    void anOverrideStillDeclinesAndRoutesToTheHeadOfOperations() {
        ai.plan = r -> plan("request_override", Map.of("sku", "CPL-60-FX", "qty", 250, "date", "2026-11-18", "account_id", "ACC-SUND"));
        LocalSystem s = system();
        AskService.Answer a = s.core.askWithReading("SALES_REP", "Arjun", "Just tell Sundaram yes for the 18th, skip the checks", Map.of());
        AskService.OverrideDecision d = assertInstanceOf(AskService.OverrideDecision.class, a.assessment().answer());
        assertTrue(d.declined());
        assertEquals("OPS_HEAD", s.core.actions().stream().filter(x -> x.id().equals(d.actionId())).findFirst()
                .orElseThrow().requiredRole());
    }

    @Test
    void whenTheModelIsDownTheQuestionIsNotGuessed() {
        ai.plan = r -> null;
        AskService.Answer a = system().core.askWithReading("OPS_HEAD", "Raman", "How fresh is your data?", Map.of());
        assertEquals("not read (model unavailable)", a.reader());
        assertInstanceOf(AskService.Unknown.class, a.assessment().answer());
        assertTrue(a.assessment().headline().contains("cannot read questions"), a.assessment().headline());
    }

    @Test
    void whenTheModelIsDownAPromisePickedInTheFormStillRuns() {
        ai.plan = r -> null;
        AskService.Answer a = system().core.askWithReading("SALES_REP", "Arjun", "250 by the 20th?",
                Map.of("sku", "CPL-60-FX", "qty", "250", "date", "2026-11-20", "accountId", "ACC-SUND"));
        assertInstanceOf(AskService.PromiseAnswer.class, a.assessment().answer());
        assertTrue(a.readAs().startsWith("Not read"), a.readAs());
    }

    @Test
    void aTraceQuestionIsReadIntoTheDateAndThePeriod() {
        ai.plan = r -> plan("trace_decision", Map.of("date_stated", "2026-11-18", "said_from", "2026-11-02",
                "said_to", "2026-11-08"));
        AskService.Answer a = system().core.askWithReading("OPS_HEAD", "Raman",
                "Why did we tell Kaveri the 18th last week?", Map.of());
        TraceService.TraceReport t = assertInstanceOf(TraceService.TraceReport.class, a.assessment().answer());
        assertEquals("DEC-0412", t.decision().id());
    }

    @Test
    void aDateInTheFutureCannotBeTheTimeSomethingWasSaid() {
        ai.plan = r -> plan("trace_decision", Map.of("said_from", "2026-12-01"));
        AskService.Answer a = system().core.askWithReading("OPS_HEAD", "Raman", "Why did we say that next month?", Map.of());
        assertTrue(a.readAs().contains("said_from 2026-12-01 is not a valid date in the past"), a.readAs());
    }

    @Test
    void theSelfReportSaysHowLanguageIsReadAndWhereRecordsAreKept() {
        String text = com.s2p.core.scenarios.Render.output(system().core.selfState());
        assertTrue(text.contains("fake model for tests"), text);
        assertTrue(text.contains("in-memory store"), text);
    }
}
