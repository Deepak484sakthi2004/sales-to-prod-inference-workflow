package com.s2p.core.enquiry;

import com.s2p.contracts.Erp;
import com.s2p.contracts.Mail;
import com.s2p.core.ai.AiDtos;
import com.s2p.core.ai.AiGateway;
import com.s2p.core.ai.AiTelemetry;
import com.s2p.core.ai.TextCheck;
import com.s2p.core.engine.WorkCalendar;
import com.s2p.core.knowledge.Confidence;
import com.s2p.core.state.CentralView;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Reads customer mail with the language model. The model does all the reading: intent, products, quantities, dates,
 * references, attachments included. The core then checks each value before anything uses it:
 * <ul>
 * <li>the text the model quotes for a value must be in the mail, otherwise the value is dropped;</li>
 * <li>a quantity must match the digits in its quote ("250 units" cannot become 25);</li>
 * <li>a product must exist in the catalogue, match any size the customer gave, and, where the catalogue has several
 * products of that kind and size, be named by the customer's own words; otherwise it becomes a candidate and order
 * history or a question to the customer decides;</li>
 * <li>a date must fall between the mail and a year later and agree with the day and month in its phrase.</li>
 * </ul>
 * If the model cannot be reached the mail is not read: it stays in the inbox and is read on a later poll.
 */
public final class EnquiryReader {
    private final AiGateway ai;

    public EnquiryReader(AiGateway ai) {
        this.ai = ai;
    }

    /** Empty when the model cannot be reached. */
    public Optional<MailReading> read(Mail.Message m, CentralView v) {
        return ai.extract(request(m, v)).map(r -> check(m, r, v));
    }

    static AiDtos.ExtractRequest request(Mail.Message m, CentralView v) {
        List<AiDtos.CatalogItem> catalogue = v.erp().products().stream()
                .map(p -> new AiDtos.CatalogItem(p.sku(), p.name(), p.family(), p.sizeMm(), p.variant())).toList();
        List<AiDtos.Attachment> atts = m.attachments().stream()
                .map(a -> new AiDtos.Attachment(a.name(), a.contentType(), a.text())).toList();
        return new AiDtos.ExtractRequest(m.messageId(), m.receivedAt().toString(), v.today().toString(), m.from(),
                m.subject(), m.body(), atts, catalogue);
    }

    private MailReading check(Mail.Message m, AiDtos.ExtractResponse r, CentralView v) {
        AiTelemetry t = ai.telemetry();
        String source = TextCheck.norm(source(m));
        String model = r.model() == null ? "the language model" : r.model();
        List<String> evidence = new ArrayList<>();

        MailReading.Intent intent = intent(r.intent());
        if (intent == null || !TextCheck.quoted(r.intent().evidence(), source)) {
            t.rejectedField();
            evidence.add("intent " + (r.intent() == null ? "missing" : r.intent().value())
                    + " not accepted: its quoted text is not in the mail; left for a person");
            intent = MailReading.Intent.OTHER;
        } else {
            evidence.add("intent " + intent + " from \"" + r.intent().evidence() + "\"");
        }
        boolean urgent = r.urgent() != null && Boolean.TRUE.equals(r.urgent().value())
                && TextCheck.quoted(r.urgent().evidence(), source);
        String quoteRef = ref(r.quotationRef(), source, t);
        String po = ref(r.customerPo(), source, t);
        String orderRef = ref(r.orderRef(), source, t);

        List<MailReading.Line> lines = new ArrayList<>();
        for (AiDtos.Line ml : r.lines()) {
            lines.add(line(ml, source, m.receivedAt().toLocalDate(), v, t));
        }
        r.notes().forEach(n -> evidence.add("model note: " + n));
        evidence.add("read by " + model + "; every value checked against the mail text and the catalogue");
        return new MailReading(intent, lines, urgent, quoteRef, po, orderRef, evidence);
    }

    private static MailReading.Line line(AiDtos.Line ml, String source, LocalDate received, CentralView v, AiTelemetry t) {
        List<Confidence.Factor> fs = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Integer qty = number(ml.qty(), source, "quantity", fs, notes, t);
        Integer size = number(ml.sizeMm(), source, "size", fs, notes, t);

        String said = ml.productEvidence() != null ? ml.productEvidence() : ml.productText();
        boolean saidInMail = TextCheck.quoted(said, source);
        Set<String> candidates = new LinkedHashSet<>();
        for (String c : ml.candidateSkus()) {
            v.product(c).filter(p -> size == null || p.sizeMm() == size).ifPresent(p -> candidates.add(p.sku()));
        }
        String sku = null;
        if (ml.sku() != null) {
            Optional<Erp.Product> p = v.product(ml.sku());
            String why = p.isEmpty() ? ml.sku() + " is not in the catalogue"
                    : !saidInMail ? "\"" + said + "\" is not in the mail"
                    : size != null && p.get().sizeMm() != size ? ml.sku() + " is not " + size + "mm"
                    : unnamed(p.get(), said, v);
            if (why == null) {
                sku = p.get().sku();
            } else {
                t.rejectedField();
                notes.add("product " + ml.sku() + " not taken: " + why);
                p.ifPresent(x -> {
                    candidates.add(x.sku());
                    siblings(x, v).forEach(s -> candidates.add(s.sku()));
                });
            }
        }
        if (!saidInMail) {
            candidates.clear();
        }
        LocalDate date = date(ml.date(), source, received, fs, notes, t);
        String ev = "quantity " + (qty == null ? "not stated" : "\"" + ml.qty().evidence() + "\"")
                + "; product " + (sku != null ? sku : candidates.isEmpty() ? "not identified" : "one of " + candidates)
                + (saidInMail ? " from \"" + said + "\"" : "")
                + "; date " + (date == null ? "not stated" : "\"" + phrase(ml.date()) + "\" -> " + date)
                + (notes.isEmpty() ? "" : "; " + String.join("; ", notes));
        return new MailReading.Line(ml.productText() == null ? "" : ml.productText(), sku, List.copyOf(candidates), qty,
                date, ev, fs);
    }

    /**
     * Null if the customer's words single out this product among others of the same kind and size; otherwise why not.
     * "60mm coupling" does not choose between a flexible and a rigid 60mm coupling; "jaw coupling" does.
     */
    private static String unnamed(Erp.Product p, String said, CentralView v) {
        List<Erp.Product> siblings = siblings(p, v);
        if (siblings.isEmpty()) {
            return null;
        }
        Set<String> own = TextCheck.words(p.name() + " " + p.variant().replace('_', ' '));
        siblings.forEach(s -> own.removeAll(TextCheck.words(s.name() + " " + s.variant().replace('_', ' '))));
        Set<String> words = TextCheck.words(said);
        return own.stream().anyMatch(words::contains) ? null
                : "\"" + said + "\" does not say which of " + p.sku() + " and "
                + String.join(", ", siblings.stream().map(Erp.Product::sku).toList()) + " is meant";
    }

    private static List<Erp.Product> siblings(Erp.Product p, CentralView v) {
        return v.erp().products().stream().filter(o -> !o.sku().equals(p.sku()) && o.family().equals(p.family())
                && o.sizeMm() == p.sizeMm()).toList();
    }

    private static Integer number(AiDtos.Field<Integer> f, String source, String label, List<Confidence.Factor> fs,
                                  List<String> notes, AiTelemetry t) {
        if (f == null || f.value() == null) {
            return null;
        }
        String why = f.value() <= 0 ? "not a positive number"
                : !TextCheck.quoted(f.evidence(), source) ? "\"" + f.evidence() + "\" is not in the mail"
                : !TextCheck.numbers(f.evidence()).isEmpty() && !TextCheck.numbers(f.evidence()).contains(f.value())
                ? "the quoted text \"" + f.evidence() + "\" says " + TextCheck.numbers(f.evidence()) : null;
        if (why != null) {
            t.rejectedField();
            notes.add(label + " " + f.value() + " not taken: " + why);
            return null;
        }
        if (TextCheck.numbers(f.evidence()).isEmpty()) {
            fs.add(new Confidence.Factor(label + " reading", 0.95, label + " " + f.value() + " read from words: \""
                    + f.evidence() + "\""));
        }
        return f.value();
    }

    private static LocalDate date(AiDtos.DateField d, String source, LocalDate received, List<Confidence.Factor> fs,
                                  List<String> notes, AiTelemetry t) {
        if (d == null || d.iso() == null) {
            if (d != null && phrase(d) != null) {
                notes.add("date phrase \"" + phrase(d) + "\" not resolved by the model");
            }
            return null;
        }
        String phrase = phrase(d);
        LocalDate date;
        try {
            date = LocalDate.parse(d.iso().trim());
        } catch (DateTimeParseException e) {
            date = null;
        }
        String why = date == null ? "\"" + d.iso() + "\" is not a date"
                : !TextCheck.quoted(d.evidence() != null ? d.evidence() : phrase, source) ? "\"" + phrase + "\" is not in the mail"
                : date.isBefore(received) || date.isAfter(received.plusDays(366)) ? date + " is before the mail or a year out"
                : TextCheck.dateMismatch(phrase, date);
        if (why != null) {
            t.rejectedField();
            notes.add("date not taken: " + why);
            return null;
        }
        fs.add(new Confidence.Factor("date reading", TextCheck.relative(phrase) ? 0.93 : 0.98, "\"" + phrase
                + "\" read as " + WorkCalendar.fmt(date) + (TextCheck.relative(phrase) ? " (a relative date: the model "
                + "counted it; if the customer counts differently the date moves)" : "")));
        return date;
    }

    private static String phrase(AiDtos.DateField d) {
        return d == null ? null : d.phrase() != null ? d.phrase() : d.evidence();
    }

    private static String ref(AiDtos.Field<String> f, String source, AiTelemetry t) {
        if (f == null || f.value() == null || f.value().isBlank()) {
            return null;
        }
        if (TextCheck.quoted(f.evidence(), source) && source.contains(TextCheck.norm(f.value()))) {
            return f.value().trim();
        }
        t.rejectedField();
        return null;
    }

    private static MailReading.Intent intent(AiDtos.Field<String> f) {
        if (f == null || f.value() == null) {
            return null;
        }
        try {
            return MailReading.Intent.valueOf(f.value().trim().toUpperCase(Locale.ROOT).replace(' ', '_'));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String source(Mail.Message m) {
        StringBuilder b = new StringBuilder();
        b.append(m.subject() == null ? "" : m.subject()).append('\n').append(m.body() == null ? "" : m.body());
        m.attachments().forEach(a -> b.append('\n').append(a.name()).append('\n').append(a.text() == null ? "" : a.text()));
        return b.toString();
    }
}
