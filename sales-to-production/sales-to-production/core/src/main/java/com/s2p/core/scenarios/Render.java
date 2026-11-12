package com.s2p.core.scenarios;

import com.s2p.core.knowledge.Assessment;
import com.s2p.core.knowledge.Confidence;
import com.s2p.core.knowledge.Describable;
import com.s2p.core.knowledge.Fact;
import com.s2p.core.knowledge.Freshness;

/** Plain-text rendering of an assessment for the run log and the ask box. */
public final class Render {
    private Render() {
    }

    public static String output(Assessment<?> a) {
        if (a.answer() instanceof Describable d) {
            String text = d.describe();
            if (text.startsWith(a.headline())) {
                return text;
            }
            String firstLine = text.split("\n", 2)[0];
            if (a.headline().startsWith(firstLine.replaceFirst(":", ".")) || a.headline().contains(firstLine)) {
                return a.headline() + (text.contains("\n") ? "\n" + text.split("\n", 2)[1] : "");
            }
            return a.headline() + "\n" + text;
        }
        return a.headline();
    }

    public static String reasoning(Assessment<?> a) {
        StringBuilder b = new StringBuilder("Confidence ").append(a.confidence().describe());
        for (Confidence.Factor f : a.confidence().factors()) {
            b.append("\n  ").append(f.describe());
        }
        b.append("\nData freshness:");
        for (Freshness f : a.freshness()) {
            b.append("\n  ").append(f.describe());
        }
        if (!a.basis().isEmpty()) {
            b.append("\nBasis:");
            for (Fact f : a.basis()) {
                b.append("\n  - ").append(f.describe());
            }
        }
        if (!a.notChecked().isEmpty()) {
            b.append("\nNot checked:");
            a.notChecked().forEach(n -> b.append("\n  - ").append(n));
        }
        if (!a.wouldChange().isEmpty()) {
            b.append("\nWhat would change the answer:");
            a.wouldChange().forEach(n -> b.append("\n  - ").append(n));
        }
        if (a.decisionId() != null) {
            b.append("\nDecision record: ").append(a.decisionId());
        }
        return b.toString();
    }
}
