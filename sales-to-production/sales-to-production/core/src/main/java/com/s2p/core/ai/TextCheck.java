package com.s2p.core.ai;

import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Checks on what the model read. These do not interpret language; they only confirm that the text the model quoted is
 * really in the source and that the value it gives is consistent with that text. Plain character loops, no patterns.
 */
public final class TextCheck {
    private static final Set<String> RELATIVE = Set.of("day", "days", "week", "weeks", "fortnight", "months");

    private TextCheck() {
    }

    /** Lower case, typographic quotes and dashes made plain, runs of whitespace collapsed, ends trimmed. */
    public static String norm(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length());
        boolean space = false;
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            char x = switch (c) {
                case '\u2018', '\u2019' -> '\'';
                case '\u201C', '\u201D' -> '"';
                case '\u2013', '\u2014' -> '-';
                default -> c;
            };
            if (Character.isWhitespace(x)) {
                space = true;
            } else {
                if (space && b.length() > 0) {
                    b.append(' ');
                }
                space = false;
                b.append(x);
            }
        }
        int start = 0;
        int end = b.length();
        while (start < end && "\"' ".indexOf(b.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && "\"' .,;:!?".indexOf(b.charAt(end - 1)) >= 0) {
            end--;
        }
        return b.substring(start, end);
    }

    /** True if every part of the quote (split at an ellipsis) appears in the source, ignoring case and spacing. */
    public static boolean quoted(String quote, String normalisedSource) {
        if (quote == null || quote.isBlank()) {
            return false;
        }
        List<String> parts = new ArrayList<>();
        String q = quote.replace("\u2026", "...");
        int from = 0;
        while (from <= q.length()) {
            int at = q.indexOf("...", from);
            String n = norm(q.substring(from, at < 0 ? q.length() : at));
            if (n.length() >= 2) {
                parts.add(n);
            }
            if (at < 0) {
                break;
            }
            from = at + 3;
        }
        return !parts.isEmpty() && parts.stream().allMatch(normalisedSource::contains);
    }

    /** Whole numbers in the text; "2,500" is 2500. */
    public static Set<Integer> numbers(String s) {
        Set<Integer> out = new LinkedHashSet<>();
        if (s == null) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i <= s.length(); i++) {
            char c = i < s.length() ? s.charAt(i) : ' ';
            if (Character.isDigit(c)) {
                cur.append(c);
                continue;
            }
            if (c == ',' && cur.length() > 0 && thousands(s, i + 1)) {
                continue;
            }
            if (cur.length() > 0 && cur.length() <= 9) {
                out.add(Integer.parseInt(cur.toString()));
            }
            cur.setLength(0);
        }
        return out;
    }

    /** Words of three or more letters, lower case. */
    public static Set<String> words(String s) {
        Set<String> out = new LinkedHashSet<>();
        if (s == null) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i <= s.length(); i++) {
            char c = i < s.length() ? Character.toLowerCase(s.charAt(i)) : ' ';
            if (Character.isLetter(c)) {
                cur.append(c);
            } else {
                if (cur.length() >= 3) {
                    out.add(cur.toString());
                }
                cur.setLength(0);
            }
        }
        return out;
    }

    /**
     * Whether the model's date is consistent with the phrase it was read from. Returns null if consistent, otherwise
     * the reason it is not. A phrase with a day number ("by the 20th", "Nov 20", "20/11") must give that day; a month
     * named must be that month; a full date must match exactly. Relative phrases ("within two weeks", "end of next
     * week") cannot be checked this way; see {@link #relative}.
     */
    public static String dateMismatch(String phrase, LocalDate date) {
        Set<Integer> nums = numbers(phrase);
        if (nums.contains(date.getYear()) && !(nums.contains(date.getMonthValue()) && nums.contains(date.getDayOfMonth()))) {
            return "the phrase gives a full date that is not " + date;
        }
        Month named = null;
        for (String w : words(phrase)) {
            for (Month m : Month.values()) {
                String full = m.name().toLowerCase(Locale.ROOT);
                if (w.equals(full) || w.equals(full.substring(0, 3)) || (m == Month.SEPTEMBER && w.equals("sept"))) {
                    named = m;
                }
            }
        }
        if (named != null && named != date.getMonth()) {
            return "the phrase names " + named + " but the date is in " + date.getMonth();
        }
        Set<Integer> days = new LinkedHashSet<>();
        nums.stream().filter(n -> n >= 1 && n <= 31).forEach(days::add);
        if (!relative(phrase) && !days.isEmpty() && !days.contains(date.getDayOfMonth())) {
            return "the phrase says " + days + " but the date is the " + date.getDayOfMonth() + "th";
        }
        return null;
    }

    /** "within two weeks", "end of next week", "in 10 working days": the model counted; nothing to compare against. */
    public static boolean relative(String phrase) {
        return words(phrase).stream().anyMatch(RELATIVE::contains) || numbers(phrase).isEmpty();
    }

    private static boolean thousands(String s, int i) {
        if (i + 3 > s.length()) {
            return false;
        }
        for (int k = i; k < i + 3; k++) {
            if (!Character.isDigit(s.charAt(k))) {
                return false;
            }
        }
        return i + 3 == s.length() || !Character.isDigit(s.charAt(i + 3));
    }
}
