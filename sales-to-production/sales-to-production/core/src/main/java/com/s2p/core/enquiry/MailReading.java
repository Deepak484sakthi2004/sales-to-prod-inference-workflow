package com.s2p.core.enquiry;

import com.s2p.core.knowledge.Confidence;

import java.time.LocalDate;
import java.util.List;

/**
 * What the language model read in a customer mail, after the core checked every value against the mail itself and
 * the catalogue. Nothing here was produced by pattern matching in Java.
 *
 * @param intent       what the customer wants
 * @param lines        one per product the customer asked about (or, for a change request, the new quantity/date)
 * @param quotationRef a quotation the mail refers to, if it names one
 * @param customerPo   the customer's purchase order number, if given
 * @param orderRef     the order a change or cancellation refers to, if the mail names one
 * @param evidence     what was read, by which model, and what was rejected, for the decision log
 */
public record MailReading(Intent intent, List<Line> lines, boolean urgent, String quotationRef, String customerPo,
                          String orderRef, List<String> evidence) {

    public enum Intent { ENQUIRY, ACCEPTANCE, STATUS_QUERY, CHANGE_REQUEST, CANCELLATION, OTHER }

    /**
     * One product line. {@code sku} is set when the customer's own words identify one catalogue product; otherwise
     * {@code candidates} lists the products that fit, and order history or a question to the customer decides.
     */
    public record Line(String text, String sku, List<String> candidates, Integer qty, LocalDate requestedDate,
                       String evidence, List<Confidence.Factor> factors) {
        public Line {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            factors = factors == null ? List.of() : List.copyOf(factors);
        }
    }

    public MailReading {
        lines = lines == null ? List.of() : List.copyOf(lines);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
