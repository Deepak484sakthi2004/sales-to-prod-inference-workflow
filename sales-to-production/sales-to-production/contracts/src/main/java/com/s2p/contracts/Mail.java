package com.s2p.contracts;

import java.time.OffsetDateTime;
import java.util.List;

/** Mailbox contract: enquiries and customer replies, with threads and attachments. */
public final class Mail {
    private Mail() {
    }

    public record Attachment(String name, String contentType, String text) {
    }

    public record Message(String id, String messageId, String threadId, String inReplyTo, String from, String to,
                          String subject, String body, OffsetDateTime receivedAt, List<Attachment> attachments) {
        public Message {
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }
    }

    public record Outbound(String idempotencyKey, String to, String subject, String body, String threadId,
                           String inReplyTo) {
    }

    public record Sent(String id, String idempotencyKey, String to, String subject, String body, String threadId,
                       OffsetDateTime sentAt) {
    }
}
