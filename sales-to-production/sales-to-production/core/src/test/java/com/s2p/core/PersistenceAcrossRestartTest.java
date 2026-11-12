package com.s2p.core;

import com.s2p.core.governance.ProposedAction;
import com.s2p.core.persistence.CoreStore;
import com.s2p.core.persistence.memory.InMemoryCoreStore;
import com.s2p.core.scenarios.ScenarioSuite;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the store is for: the core's own records survive a restart and are shared by two instances. */
class PersistenceAcrossRestartTest {

    @Test
    void everyScenarioRunsWithEveryRecordPassingThroughTheDatabaseCodec() {
        LocalSystem s = new LocalSystem(new InMemoryCoreStore(true), new ScriptedModel());
        List<ScenarioSuite.Step> steps = new ScenarioSuite(s.core, s.sources.simulationPort()).run();
        assertTrue(steps.size() >= 10, "scenarios run: " + steps.size());
    }

    @Test
    void theDefaultInMemoryStoreForgetsOnRestart() {
        LocalSystem s = new LocalSystem();
        s.core.pollMailbox();
        int pending = s.core.actions().size();
        assertTrue(pending > 0);
        CoreSystem restarted = s.restart(new InMemoryCoreStore(), new ScriptedModel());
        assertEquals(0, restarted.actions().size());
    }

    @Test
    void withMongoPendingDecisionsAndReadMailSurviveARestart() {
        String uri = System.getenv("MONGODB_TEST_URI");
        Assumptions.assumeTrue(uri != null && !uri.isBlank(), "set MONGODB_TEST_URI to run against MongoDB");
        String db = "s2p_restart_" + Long.toHexString(System.nanoTime());
        try {
            LocalSystem s = new LocalSystem(MongoTestSupport.openShared(uri, db), new ScriptedModel());
            s.core.pollMailbox();
            List<ProposedAction> before = s.core.actions();
            String lastDecision = s.core.decisions(1).get(0).id();
            int seeded = s.core.context().log().all().size();
            s.core.close();

            CoreStore again = MongoTestSupport.openShared(uri, db);
            CoreSystem restarted = s.restart(again, new ScriptedModel());
            assertEquals(before.stream().map(ProposedAction::id).toList(),
                    restarted.actions().stream().map(ProposedAction::id).toList(), "pending approvals survive");
            assertTrue(restarted.pollMailbox().isEmpty(), "mail already read is not read again");
            assertTrue(restarted.context().log().all().size() >= seeded, "the log was not re-seeded or lost");
            assertFalse(restarted.context().log().find(r -> r.id().equals(lastDecision)).isEmpty());
            String next = restarted.context().log().nextId();
            assertTrue(next.compareTo(lastDecision) > 0, next + " continues after " + lastDecision);

            ProposedAction quote = restarted.actions().stream().filter(a -> a.type().equals("SEND_QUOTATION"))
                    .findFirst().orElseThrow();
            restarted.decide(quote.id(), "Arjun", "SALES_REP", true, "send");
            CoreSystem second = s.restart(MongoTestSupport.openShared(uri, db), new ScriptedModel());
            assertEquals("EXECUTED", second.actions().stream().filter(a -> a.id().equals(quote.id())).findFirst()
                    .orElseThrow().status(), "a second instance sees the decision");
            assertTrue(org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> second.decide(quote.id(), "Priya", "SALES_REP", true, "again")).getMessage().contains("already decided"));
            restarted.close();
            second.close();
        } finally {
            MongoTestSupport.drop(uri, db);
        }
    }
}
