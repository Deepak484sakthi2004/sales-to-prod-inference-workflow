package com.s2p.core.knowledge;

import java.util.List;
import java.util.Objects;

/**
 * The envelope every answer and recommendation travels in. Confidence and freshness are required constructor
 * arguments: an answer without them cannot be built, so it cannot be returned.
 */
public record Assessment<T>(String question, String headline, T answer, Confidence confidence,
                            List<Freshness> freshness, List<Fact> basis, List<String> notChecked,
                            List<String> wouldChange, String decisionId) {
    public Assessment {
        Objects.requireNonNull(headline, "an answer needs a headline");
        Objects.requireNonNull(confidence, "confidence is part of the answer");
        if (freshness == null || freshness.isEmpty()) {
            throw new IllegalArgumentException("freshness is part of the answer: state the age of the data used");
        }
        freshness = List.copyOf(freshness);
        basis = basis == null ? List.of() : List.copyOf(basis);
        notChecked = notChecked == null ? List.of() : List.copyOf(notChecked);
        wouldChange = wouldChange == null ? List.of() : List.copyOf(wouldChange);
    }
}
