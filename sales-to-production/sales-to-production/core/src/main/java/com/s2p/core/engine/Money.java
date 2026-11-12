package com.s2p.core.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Indian-grouped rupee formatting (4,60,000) because every figure here is read by people who count that way. */
public final class Money {
    private Money() {
    }

    public static String inr(BigDecimal v) {
        if (v == null) {
            return "n/a";
        }
        long n = v.setScale(0, RoundingMode.HALF_UP).longValue();
        boolean neg = n < 0;
        String s = Long.toString(Math.abs(n));
        StringBuilder out = new StringBuilder();
        if (s.length() <= 3) {
            out.append(s);
        } else {
            String last3 = s.substring(s.length() - 3);
            String rest = s.substring(0, s.length() - 3);
            StringBuilder grouped = new StringBuilder();
            while (rest.length() > 2) {
                grouped.insert(0, "," + rest.substring(rest.length() - 2));
                rest = rest.substring(0, rest.length() - 2);
            }
            grouped.insert(0, rest);
            out.append(grouped).append(',').append(last3);
        }
        return (neg ? "-" : "") + "₹" + out;
    }

    public static BigDecimal of(double d) {
        return BigDecimal.valueOf(d).setScale(0, RoundingMode.HALF_UP);
    }
}
