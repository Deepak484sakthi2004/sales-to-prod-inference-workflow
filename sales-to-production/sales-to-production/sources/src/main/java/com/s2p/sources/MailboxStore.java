package com.s2p.sources;

import com.s2p.contracts.Command;
import com.s2p.contracts.Mail;
import com.s2p.contracts.RelativeJson;
import com.s2p.contracts.SimClock;
import com.s2p.contracts.SourceResponse;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mailbox stand-in. Like a real mailbox it can deliver the same message twice; deduplication is the reader's job.
 * Outbound sends are idempotent by key so a retried send never reaches the customer twice.
 */
public final class MailboxStore {
    public record Seed(List<Mail.Message> messages) {
    }

    private final SimClock clock;
    private final LocalDate anchor;
    private final AtomicLong version = new AtomicLong();
    private final List<Mail.Message> inbox = new ArrayList<>();
    private final List<Mail.Sent> outbox = new ArrayList<>();
    private final Map<String, Mail.Sent> sentByKey = new HashMap<>();
    private int nextIn;
    private int nextOut;

    public MailboxStore(SimClock clock, LocalDate anchor) {
        this.clock = clock;
        this.anchor = anchor;
        reset();
    }

    public synchronized void reset() {
        inbox.clear();
        outbox.clear();
        sentByKey.clear();
        Seed seed = new RelativeJson(anchor).load("seed/mailbox.json", Seed.class);
        inbox.addAll(seed.messages());
        nextIn = Ids.next(inbox.stream().map(Mail.Message::id).toList(), "MSG-", 100);
        nextOut = 1;
        version.set(100);
    }

    public synchronized SourceResponse<List<Mail.Message>> inbox() {
        List<Mail.Message> visible = inbox.stream().filter(m -> !m.receivedAt().isAfter(clock.now())).toList();
        return new SourceResponse<>("mailbox", version.get(), clock.now(), visible);
    }

    public synchronized SourceResponse<List<Mail.Sent>> outbox() {
        return new SourceResponse<>("mailbox", version.get(), clock.now(), List.copyOf(outbox));
    }

    public synchronized Mail.Message inject(Mail.Message m) {
        Mail.Message stored = new Mail.Message(
                m.id() == null ? "MSG-" + nextIn++ : m.id(),
                m.messageId() == null ? "<gen-" + nextIn + "@mailbox>" : m.messageId(),
                m.threadId() == null ? "T-" + nextIn : m.threadId(),
                m.inReplyTo(), m.from(), m.to() == null ? "sales@precisioncouplings.example" : m.to(),
                m.subject(), m.body(), m.receivedAt() == null ? clock.now() : m.receivedAt(), m.attachments());
        inbox.add(stored);
        version.incrementAndGet();
        return stored;
    }

    public synchronized Command<Mail.Sent> send(Mail.Outbound out) {
        if (out.idempotencyKey() == null || out.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("Outbound mail needs an idempotency key");
        }
        Mail.Sent existing = sentByKey.get(out.idempotencyKey());
        if (existing != null) {
            return new Command<>(existing, true);
        }
        Mail.Sent sent = new Mail.Sent("OUT-" + nextOut++, out.idempotencyKey(), out.to(), out.subject(), out.body(),
                out.threadId(), clock.now());
        outbox.add(sent);
        sentByKey.put(out.idempotencyKey(), sent);
        version.incrementAndGet();
        return new Command<>(sent, false);
    }
}
