package com.s2p.core;

import com.s2p.contracts.Mail;
import com.s2p.core.ai.AiDtos;
import com.s2p.core.enquiry.EnquiryReader;
import com.s2p.core.enquiry.EnquiryService;
import com.s2p.core.enquiry.MailReading;
import com.s2p.core.knowledge.Assessment;
import com.s2p.core.persistence.memory.InMemoryCoreStore;
import com.s2p.core.state.CentralView;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static com.s2p.core.FakeAi.f;
import static com.s2p.core.FakeAi.line;
import static com.s2p.core.FakeAi.reply;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The model reads every mail; the core accepts only what the mail text and the catalogue support. */
class EnquiryReaderTest {
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-11T09:05:00+05:30");
    private final LocalSystem s = new LocalSystem();
    private final CentralView v = s.core.context().state().view();
    private final FakeAi ai = new FakeAi();
    private final EnquiryReader reader = new EnquiryReader(ai);

    private static Mail.Message mail(String body, Mail.Attachment... att) {
        return new Mail.Message("M", "<m>", "T", null, "meena.iyer@sundarampumps.example", "s", "RFQ", body, AT, List.of(att));
    }

    private MailReading read(String body, AiDtos.Line... lines) {
        ai.extract = r -> reply("ENQUIRY", body.substring(0, 10), List.of(lines));
        return reader.read(mail(body), v).orElseThrow();
    }

    @Test
    void readsSeveralProductsInOneMail() {
        MailReading r = read("Please send 100 units of 45mm and 150 units of 80mm flexible couplings by the 24th",
                line("45mm coupling", "CPL-45-FX", List.of(), "45mm", 100, "100 units", "by the 24th", "2026-11-24"),
                line("80mm flexible coupling", "CPL-80-FX", List.of(), "80mm flexible couplings", 150, "150 units",
                        "by the 24th", "2026-11-24"));
        assertEquals(MailReading.Intent.ENQUIRY, r.intent());
        assertEquals(List.of("CPL-45-FX", "CPL-80-FX"), r.lines().stream().map(MailReading.Line::sku).toList());
        assertEquals(150, r.lines().get(1).qty());
        assertEquals(LocalDate.of(2026, 11, 24), r.lines().get(0).requestedDate());
    }

    @Test
    void aQuantityThatDoesNotMatchItsQuotedTextIsNotTaken() {
        MailReading.Line l = read("We need 250 units of the 60mm coupling by the 20th",
                line("60mm coupling", null, List.of("CPL-60-FX", "CPL-60-RG"), "the 60mm coupling", 25, "250 units",
                        "by the 20th", "2026-11-20")).lines().get(0);
        assertNull(l.qty());
        assertTrue(l.evidence().contains("quantity 25 not taken"), l.evidence());
        assertEquals(1, ai.telemetry().rejectedFields());
    }

    @Test
    void aValueQuotedFromTextThatIsNotInTheMailIsNotTaken() {
        MailReading.Line l = read("Please quote 60mm coupling, quantity to follow",
                line("60mm coupling", null, List.of("CPL-60-FX", "CPL-60-RG"), "60mm coupling", 300, "300 pcs",
                        null, null)).lines().get(0);
        assertNull(l.qty());
    }

    @Test
    void aProductTheCustomerDidNotNameBecomesACandidate() {
        MailReading.Line l = read("We need 250 units of the 60mm coupling by the 20th",
                line("60mm coupling", "CPL-60-FX", List.of(), "the 60mm coupling", 250, "250 units",
                        "by the 20th", "2026-11-20")).lines().get(0);
        assertNull(l.sku());
        assertEquals(List.of("CPL-60-FX", "CPL-60-RG"), l.candidates());
        assertTrue(l.evidence().contains("does not say which"), l.evidence());
    }

    @Test
    void theCustomersOwnWordsForAProductAreEnough() {
        MailReading.Line l = read("Need 250 jaw type couplings, 60 mm bore, by the 20th",
                line("jaw type coupling 60mm", "CPL-60-FX", List.of(), "jaw type couplings, 60 mm", 250, "250 jaw",
                        "by the 20th", "2026-11-20")).lines().get(0);
        assertEquals("CPL-60-FX", l.sku());
    }

    @Test
    void aDateThatDoesNotAgreeWithItsPhraseIsNotTaken() {
        MailReading.Line l = read("We need 250 units of the 60mm coupling by the 20th",
                line("60mm coupling", null, List.of("CPL-60-FX", "CPL-60-RG"), "the 60mm coupling", 250, "250 units",
                        "by the 20th", "2026-11-21")).lines().get(0);
        assertNull(l.requestedDate());
        assertTrue(l.evidence().contains("date not taken"), l.evidence());
    }

    @Test
    void aRelativeDateIsTakenAndSaidToBeTheModelsCount() {
        MailReading.Line l = read("Need two hundred and fifty rigid 60mm couplings by end of next week",
                line("rigid coupling 60mm", "CPL-60-RG", List.of(), "rigid 60mm couplings", 250,
                        "two hundred and fifty", "by end of next week", "2026-11-20")).lines().get(0);
        assertEquals(250, l.qty());
        assertEquals(LocalDate.of(2026, 11, 20), l.requestedDate());
        assertTrue(l.factors().stream().anyMatch(x -> x.name().equals("date reading") && x.multiplier() == 0.93));
        assertTrue(l.factors().stream().anyMatch(x -> x.name().equals("quantity reading")));
    }

    @Test
    void urgencyCountsOnlyWhenTheModelQuotesIt() {
        String body = "No rush, we don't need these urgently. 300 pcs 60mm rigid coupling";
        ai.extract = r -> new AiDtos.ExtractResponse(f("ENQUIRY", "300 pcs 60mm rigid coupling"),
                f(false, "we don't need these urgently"), null, null, null, List.of(), List.of(), "fake");
        assertFalse(reader.read(mail(body), v).orElseThrow().urgent());
    }

    @Test
    void attachmentsAreReadByTheModelAndCheckedAgainstTheAttachment() {
        ai.extract = r -> reply("ENQUIRY", "RFQ attached", List.of(line("Weld neck flange 150mm", "FLG-150-WN", List.of(),
                "Weld neck flange 150mm", 12, "Weld neck flange 150mm,12", "2026-12-01", "2026-12-01")));
        MailReading r = reader.read(mail("RFQ attached", new Mail.Attachment("rfq.csv", "text/csv",
                "item,qty,required_by\nWeld neck flange 150mm,12,2026-12-01")), v).orElseThrow();
        assertEquals(12, r.lines().get(0).qty());
        assertEquals("FLG-150-WN", r.lines().get(0).sku());
    }

    @Test
    void whenTheModelIsDownTheMailWaitsUnreadAndIsReadLater() {
        FakeAi model = new FakeAi();
        LocalSystem sys = new LocalSystem(new InMemoryCoreStore(), model);
        Mail.Message m = new Mail.Message(null, "<later@sundarampumps.example>", "T-SUND-9002", null,
                "meena.iyer@sundarampumps.example", null, "60mm couplings", "Qty: 250, 60mm jaw coupling, required by Nov 20",
                null, List.of());
        sys.sources.simulationPort().injectMail(m);
        assertTrue(sys.core.pollMailbox().stream().noneMatch(a -> a.answer().messageId().equals(m.messageId())));
        assertTrue(sys.core.context().ai().telemetry().failures() > 0);
        int waiting = sys.core.selfState().answer().unprocessedMail();
        assertTrue(waiting >= 1, "unread mail is reported: " + waiting);

        model.extract = r -> r.messageId().equals(m.messageId()) ? reply("ENQUIRY", "Qty: 250", List.of(line(
                "60mm jaw coupling", "CPL-60-FX", List.of(), "60mm jaw coupling", 250, "Qty: 250", "Nov 20", "2026-11-20")))
                : null;
        Assessment<EnquiryService.EnquiryResult> a = sys.core.pollMailbox().stream()
                .filter(x -> x.answer().messageId().equals(m.messageId())).findFirst().orElseThrow();
        assertEquals(EnquiryService.Outcome.QUOTATION_DRAFTED, a.answer().outcome(), a.headline());
        assertEquals("CPL-60-FX", a.answer().lines().get(0).sku());
    }

    @Test
    void anUnknownSenderIsNotSentToTheModel() {
        FakeAi model = new FakeAi();
        LocalSystem sys = new LocalSystem(new InMemoryCoreStore(), model);
        sys.sources.simulationPort().injectMail(new Mail.Message(null, "<x@unknown.example>", "T-X", null,
                "someone@unknown.example", null, "Hello", "We need 10 couplings", null, List.of()));
        Assessment<EnquiryService.EnquiryResult> a = sys.core.pollMailbox().stream()
                .filter(x -> x.answer().messageId().equals("<x@unknown.example>")).findFirst().orElseThrow();
        assertEquals(EnquiryService.Outcome.UNKNOWN_SENDER, a.answer().outcome());
        assertTrue(model.extractCalls.stream().noneMatch(r -> "<x@unknown.example>".equals(r.messageId())));
    }

    @Test
    void aChangeRequestIsRoutedAndNothingChanges() {
        FakeAi model = new FakeAi();
        model.extract = r -> r.body().contains("reduce") ? new AiDtos.ExtractResponse(
                f("CHANGE_REQUEST", "Please reduce the quantity on our order to 200"), null, null, null, null,
                List.of(line(null, null, List.of(), null, 200, "to 200", "push delivery to the 27th", "2026-11-27")),
                List.of(), "fake") : null;
        LocalSystem sys = new LocalSystem(new InMemoryCoreStore(), model);
        long ordersBefore = sys.sources.erp.snapshot().data().orders().size();
        sys.sources.simulationPort().injectMail(new Mail.Message(null, "<chg@kaveriagro.example>", "T-KAV-1", null,
                "ravi.shankar@kaveriagro.example", null, "Order change",
                "Please reduce the quantity on our order to 200 and push delivery to the 27th", null, List.of()));
        Assessment<EnquiryService.EnquiryResult> a = sys.core.pollMailbox().stream()
                .filter(x -> x.answer().messageId().equals("<chg@kaveriagro.example>")).findFirst().orElseThrow();
        assertEquals(EnquiryService.Outcome.CHANGE_REQUEST_ROUTED, a.answer().outcome());
        assertTrue(a.headline().contains("quantity 200") && a.headline().contains("Nothing has been changed"), a.headline());
        assertEquals(ordersBefore, sys.sources.erp.snapshot().data().orders().size());
    }
}
