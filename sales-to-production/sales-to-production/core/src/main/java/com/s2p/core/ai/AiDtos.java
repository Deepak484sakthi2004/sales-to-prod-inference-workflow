package com.s2p.core.ai;

import java.util.List;
import java.util.Map;

/**
 * The JSON contract between the core and the Python AI service (ai-service/app/schemas.py mirrors it). Field names
 * travel in snake_case. Every value the model reads comes with the exact text it was read from, so the core can check
 * it before anything uses it.
 */
public final class AiDtos {
    private AiDtos() {
    }

    // ------------------------------------------------------------------ reading mail

    public record Attachment(String name, String contentType, String text) {
    }

    public record CatalogItem(String sku, String name, String family, Integer sizeMm, String variant) {
    }

    public record ExtractRequest(String messageId, String receivedAt, String today, String fromAddress, String subject,
                                 String body, List<Attachment> attachments, List<CatalogItem> catalogue) {
    }

    /** A value and the verbatim text in the mail it was read from. */
    public record Field<T>(T value, String evidence) {
    }

    /** A delivery date as the customer wrote it, and the model's reading of it as an ISO date. */
    public record DateField(String phrase, String iso, String evidence) {
    }

    /**
     * {@code sku}: set only when the customer's words identify one catalogue product; {@code candidateSkus}: the
     * products that fit otherwise; {@code productEvidence}: the customer's words for the product.
     */
    public record Line(String productText, String sku, List<String> candidateSkus, String productEvidence,
                       Field<Integer> qty, Field<Integer> sizeMm, DateField date) {
        public Line {
            candidateSkus = candidateSkus == null ? List.of() : candidateSkus;
        }
    }

    public record ExtractResponse(Field<String> intent, Field<Boolean> urgent, Field<String> quotationRef,
                                  Field<String> customerPo, Field<String> orderRef, List<Line> lines,
                                  List<String> notes, String model) {
        public ExtractResponse {
            lines = lines == null ? List.of() : lines;
            notes = notes == null ? List.of() : notes;
        }
    }

    // ------------------------------------------------------------------ reading questions

    public record ToolSpec(String name, String description, Map<String, Object> parameters) {
    }

    public record EntityRef(String id, String name) {
    }

    public record AskPlanRequest(String question, String role, String today, List<ToolSpec> tools,
                                 List<EntityRef> accounts, List<EntityRef> products, List<String> lines,
                                 Map<String, String> context) {
    }

    /** Which tool the model would call, with which arguments, and one line saying how it read the question. */
    public record AskPlan(String tool, Map<String, Object> args, String reading, String model) {
        public AskPlan {
            args = args == null ? Map.of() : args;
        }
    }

    // ------------------------------------------------------------------ reading CRM notes

    public record Note(String id, String text) {
    }

    public record QuotationNotes(String quotationId, List<Note> notes) {
    }

    public record ActivitiesRequest(List<QuotationNotes> quotations) {
    }

    /** Whether the customer mentioned a competitor, quoting the note ({@code activityId}) it was read from. */
    public record ActivityReading(String quotationId, Field<Boolean> competitorMentioned, String activityId) {
    }

    public record ActivitiesResponse(List<ActivityReading> readings, String model) {
        public ActivitiesResponse {
            readings = readings == null ? List.of() : readings;
        }
    }
}
