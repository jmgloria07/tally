package app.fitlocal.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Number formatting rules shared by settings storage, the dashboard and the coach context. */
public final class Numbers {

    private Numbers() {
    }

    /** Rounds half-up to one decimal. */
    public static double round1(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * Canonical text for a stored number: integral values without a decimal point ({@code "2000"}),
     * otherwise minimal decimals ({@code "112.5"}).
     */
    public static String canonical(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    /** At most one decimal, trailing ".0" dropped, no thousands separators: 1250, 17.5, 72.1. */
    public static String oneDecimal(double value) {
        String text = BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
        return text.equals("-0") ? "0" : text;
    }

    /** JSON-friendly number: a Long when integral, else a Double, so 2000 is not written as 2000.0. */
    public static Number jsonNumber(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return (long) value;
        }
        return value;
    }
}
