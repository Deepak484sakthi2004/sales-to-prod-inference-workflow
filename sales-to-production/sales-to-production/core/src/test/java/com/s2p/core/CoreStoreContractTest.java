package com.s2p.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.s2p.contracts.Mail;
import com.s2p.core.governance.ActionPayload;
import com.s2p.core.governance.DateCommitment;
import com.s2p.core.governance.ProposedAction;
import com.s2p.core.knowledge.Assessment;
import com.s2p.core.knowledge.Confidence;
import com.s2p.core.knowledge.Fact;
import com.s2p.core.knowledge.Freshness;
import com.s2p.core.persistence.CoreStore;
import com.s2p.core.persistence.StoreCollection;
import com.s2p.core.persistence.memory.InMemoryCoreStore;
import com.s2p.core.state.Source;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One contract, every backend: in memory, in memory with the database codec, and MongoDB when MONGODB_TEST_URI is set
 * (a local mongod or a throwaway Atlas database; each run uses its own database name and drops it afterwards).
 */
class CoreStoreContractTest {
    record Named(String name, Supplier<CoreStore> open) {
        @Override
        public String toString() {
            return name;
        }
    }

    static List<Named> stores() {
        List<Named> out = new ArrayList<>();
        out.add(new Named("memory", InMemoryCoreStore::new));
        out.add(new Named("memory+codec", () -> new InMemoryCoreStore(true)));
        String uri = System.getenv("MONGODB_TEST_URI");
        if (uri != null && !uri.isBlank()) {
            out.add(new Named("mongo", () -> MongoTestSupport.open(uri)));
        }
        return out;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    void insertIfAbsentIsTheDuplicateGuard(Named n) {
        try (CoreStore s = n.open().get()) {
            StoreCollection<String> c = s.collection("processed", String.class);
            assertTrue(c.insertIfAbsent("MSG-1", "<a>", Map.of()));
            assertFalse(c.insertIfAbsent("MSG-1", "<b>", Map.of()));
            assertEquals("<a>", c.get("MSG-1").orElseThrow());
            assertTrue(c.exists("MSG-1"));
            assertFalse(c.exists("MSG-2"));
            assertTrue(c.delete("MSG-1"), "a claim can be released");
            assertFalse(c.delete("MSG-1"));
            assertTrue(c.insertIfAbsent("MSG-1", "<c>", Map.of()));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    void orderIndexesAndConditionalReplace(Named n) {
        try (CoreStore s = n.open().get()) {
            StoreCollection<String> c = s.collection("things", String.class, "status", "subject");
            c.upsert("a", "A1", Map.of("status", "PENDING", "subject", "replan:L2(x)"));
            c.upsert("b", "B1", Map.of("status", "PENDING", "subject", "quote:Q-1"));
            c.upsert("c", "C1", Map.of("status", "EXECUTED", "subject", "replan:L2(y)"));
            c.upsert("a", "A2", Map.of("status", "PENDING", "subject", "replan:L2(x)"));
            assertEquals(List.of("A2", "B1", "C1"), c.all(), "a replaced record keeps its place");
            assertEquals(List.of("A2", "B1"), c.findBy("status", "PENDING"));
            assertEquals(List.of("C1", "B1"), c.latest(2));
            assertTrue(c.replaceIf("a", "A3", Map.of("status", "DECIDING", "subject", "replan:L2(x)"), "status", "PENDING"));
            assertFalse(c.replaceIf("a", "A4", Map.of("status", "DECIDING", "subject", "replan:L2(x)"), "status",
                    "PENDING"), "a second decider loses");
            assertEquals("A3", c.get("a").orElseThrow());
            assertEquals(List.of(), c.findBy("status", "PENDING").stream().filter(x -> x.startsWith("A")).toList());
            assertEquals(0, c.deleteByPrefix("subject", "replan:L2(z"), "the prefix is literal, not a regex");
            assertEquals(2, c.deleteByPrefix("subject", "replan:L2("));
            assertEquals(List.of("B1"), c.all());
            assertThrows(IllegalArgumentException.class, () -> c.findBy("undeclared", "x"));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    void sequencesOnlyGoUp(Named n) {
        try (CoreStore s = n.open().get()) {
            long a = s.nextSequence("decision");
            long b = s.nextSequence("decision");
            assertEquals(a + 1, b);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    void recordsSurviveExactly(Named n) {
        try (CoreStore s = n.open().get()) {
            OffsetDateTime at = OffsetDateTime.parse("2026-11-11T09:30:00+05:30");
            ProposedAction a = new ProposedAction("A-0001", "SEND_QUOTATION", "Send Q-3001", new ActionPayload.SendQuotation(
                    "ACC-SUND", "Q-3001", new Mail.Outbound("send-quote:Q-3001", "meena@x", "Re: RFQ", "Dear Meena", "T", "<m>")),
                    "SALES_REP", "send-quote:Q-3001", at, Map.of("confidence", 0.6, "dataFresh", true),
                    List.of(new DateCommitment("ACC-SUND", "CPL-60-FX", 250, LocalDate.of(2026, 11, 20), null)), "DEC-0500",
                    "PENDING", null, null, null, null, null);
            StoreCollection<ProposedAction> c = s.collection("actions", ProposedAction.class, "status");
            c.upsert(a.id(), a, Map.of("status", a.status()));
            ProposedAction back = c.get("A-0001").orElseThrow();
            assertInstanceOf(ActionPayload.SendQuotation.class, back.payload());
            assertEquals(a, back);
            assertEquals(at.getOffset(), back.createdAt().getOffset());

            record Priced(BigDecimal price) {
            }
            StoreCollection<Priced> p = s.collection("prices", Priced.class);
            p.upsert("x", new Priced(new BigDecimal("1234.50")), Map.of());
            assertEquals(new BigDecimal("1234.50"), p.get("x").orElseThrow().price(), "money keeps its scale");

            Assessment<String> as = new Assessment<>("q", "h", "answer", Confidence.start().apply("slack", 0.6, "zero slack").build(),
                    List.of(new Freshness(Source.PLANNING, at, 4, 30, Freshness.FRESH, null)),
                    List.of(new Fact("ERP", "CPL-60-FX", "stock 120", 2001, 4)), List.of("carrier"), List.of("if L2 slips"), "DEC-1");
            StoreCollection<Assessment<String>> h = s.collection("history", new TypeReference<Assessment<String>>() {
            });
            h.upsert("M", as, Map.of());
            assertEquals(as, h.get("M").orElseThrow());
        }
    }
}
