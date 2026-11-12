package com.s2p.core.sales;

import com.s2p.contracts.Erp;
import com.s2p.core.CoreContext;
import com.s2p.core.ai.AiDtos;
import com.s2p.core.ai.AiGateway;
import com.s2p.core.ai.TextCheck;
import com.s2p.core.persistence.StoreCollection;
import com.s2p.core.state.CentralView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reads CRM activity notes with the language model: did the customer mention a competitor or a lower price elsewhere?
 * The model quotes the note it read that from; the core keeps the reading only if that text is in that note. Readings
 * are kept per quotation until its notes change, so ranking quotations does not call the model each time.
 */
final class ActivityReader {
    /** {@code evidence} and {@code activityId}: the quoted text and the note it is in, when a competitor was mentioned. */
    record Reading(boolean competitor, String evidence, String activityId, String model) {
    }

    record Cached(int notesHash, Reading reading) {
    }

    private final AiGateway ai;
    private final StoreCollection<Cached> cache;

    ActivityReader(CoreContext ctx) {
        this.ai = ctx.ai();
        this.cache = ctx.store().collection("activity_readings", Cached.class);
    }

    /** Quotation id to reading. A quotation is absent when its notes could not be read. */
    Map<String, Reading> read(List<Erp.Quotation> quotes, CentralView v) {
        Map<String, Reading> out = new LinkedHashMap<>();
        Map<String, AiDtos.QuotationNotes> toRead = new LinkedHashMap<>();
        Map<String, Integer> hashes = new LinkedHashMap<>();
        for (Erp.Quotation q : quotes) {
            List<AiDtos.Note> notes = v.crm().activities().stream()
                    .filter(a -> q.id().equals(a.quotationId()) && a.note() != null && !a.note().isBlank())
                    .map(a -> new AiDtos.Note(a.id(), a.note())).toList();
            if (notes.isEmpty()) {
                out.put(q.id(), new Reading(false, null, null, null));
                continue;
            }
            int hash = Objects.hash(notes.toArray());
            hashes.put(q.id(), hash);
            cache.get(q.id()).filter(c -> c.notesHash() == hash).ifPresentOrElse(c -> out.put(q.id(), c.reading()),
                    () -> toRead.put(q.id(), new AiDtos.QuotationNotes(q.id(), notes)));
        }
        if (toRead.isEmpty()) {
            return out;
        }
        ai.readActivities(new AiDtos.ActivitiesRequest(new ArrayList<>(toRead.values()))).ifPresent(r -> {
            for (AiDtos.ActivityReading x : r.readings()) {
                AiDtos.QuotationNotes qn = toRead.get(x.quotationId());
                if (qn == null || x.competitorMentioned() == null || x.competitorMentioned().value() == null) {
                    continue;
                }
                Reading reading;
                if (Boolean.TRUE.equals(x.competitorMentioned().value())) {
                    String note = qn.notes().stream().filter(n -> n.id().equals(x.activityId())).map(AiDtos.Note::text)
                            .findFirst().orElse(null);
                    if (note == null || !TextCheck.quoted(x.competitorMentioned().evidence(), TextCheck.norm(note))) {
                        ai.telemetry().rejectedField();
                        continue;
                    }
                    reading = new Reading(true, x.competitorMentioned().evidence(), x.activityId(), r.model());
                } else {
                    reading = new Reading(false, null, null, r.model());
                }
                cache.upsert(x.quotationId(), new Cached(hashes.get(x.quotationId()), reading), Map.of());
                out.put(x.quotationId(), reading);
            }
        });
        return out;
    }

    static List<String> unread(List<Erp.Quotation> quotes, Map<String, Reading> read) {
        return quotes.stream().map(Erp.Quotation::id).filter(id -> !read.containsKey(id)).toList();
    }
}
