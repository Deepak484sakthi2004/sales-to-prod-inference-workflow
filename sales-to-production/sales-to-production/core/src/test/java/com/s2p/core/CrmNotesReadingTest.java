package com.s2p.core;

import com.s2p.core.ai.AiDtos;
import com.s2p.core.knowledge.Assessment;
import com.s2p.core.persistence.memory.InMemoryCoreStore;
import com.s2p.core.sales.QuotationRiskService;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Whether a customer mentioned a competitor is read from CRM notes by the model, quoted, checked and kept. */
class CrmNotesReadingTest {

    private static double competitor(Assessment<QuotationRiskService.QuoteRiskReport> a, String quote) {
        return a.answer().ranked().stream().filter(q -> q.quotationId().equals(quote)).findFirst().orElseThrow()
                .signals().get("competitorMentioned");
    }

    @Test
    void aCompetitorMentionIsReadFromTheNoteAndShownWithItsQuote() {
        LocalSystem s = new LocalSystem();
        Assessment<QuotationRiskService.QuoteRiskReport> a = s.core.quoteRisks();
        assertEquals(1.0, competitor(a, "Q-2201"));
        assertEquals(0.0, competitor(a, "Q-2209"));
        assertTrue(a.basis().stream().anyMatch(f -> f.statement().contains("a Pune vendor quoted lower")), a.basis().toString());
    }

    @Test
    void aQuoteThatIsNotInTheNoteIsNotTaken() {
        FakeAi model = new FakeAi();
        model.activities = r -> new AiDtos.ActivitiesResponse(r.quotations().stream().map(q -> new AiDtos.ActivityReading(
                q.quotationId(), new AiDtos.Field<>(true, "they signed with a rival"), q.notes().get(0).id())).toList(),
                "fake");
        Assessment<QuotationRiskService.QuoteRiskReport> a = new LocalSystem(new InMemoryCoreStore(), model).core.quoteRisks();
        assertEquals(0.0, competitor(a, "Q-2201"));
        assertTrue(model.telemetry().rejectedFields() > 0);
        assertTrue(a.notChecked().stream().anyMatch(n -> n.contains("could not read")), a.notChecked().toString());
    }

    @Test
    void whenTheModelIsDownTheRankingSaysWhatItCouldNotRead() {
        FakeAi model = new FakeAi();
        Assessment<QuotationRiskService.QuoteRiskReport> a = new LocalSystem(new InMemoryCoreStore(), model).core.quoteRisks();
        assertTrue(a.notChecked().stream().anyMatch(n -> n.contains("Q-2201") && n.contains("could not read")));
        assertTrue(a.confidence().factors().stream().anyMatch(f -> f.name().equals("CRM notes")));
    }

    @Test
    void notesAlreadyReadAreNotSentAgain() {
        int[] calls = {0};
        FakeAi model = new FakeAi();
        model.activities = r -> {
            calls[0]++;
            return new AiDtos.ActivitiesResponse(r.quotations().stream().map(q -> new AiDtos.ActivityReading(
                    q.quotationId(), new AiDtos.Field<>(false, null), null)).toList(), "fake");
        };
        LocalSystem s = new LocalSystem(new InMemoryCoreStore(), model);
        s.core.quoteRisks();
        s.core.quoteRisks();
        assertEquals(1, calls[0]);
    }
}
