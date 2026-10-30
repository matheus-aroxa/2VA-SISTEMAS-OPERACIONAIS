package fsim;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Units {
    private static final Pattern SIZE = Pattern.compile("\\s*(\\d+(?:[.,]\\d+)?)\\s*([A-Za-z]*)\\s*");

    private Units() {
    }

    /** Converte '10KB', '512B', '1.5MB' ou '4096' em bytes (base 1024). */
    static long parseSize(String text) {
        Matcher m = SIZE.matcher(text);
        long factor = -1;
        if (m.matches()) {
            factor = switch (m.group(2).toUpperCase(Locale.ROOT)) {
                case "", "B" -> 1L;
                case "K", "KB", "KIB" -> 1024L;
                case "M", "MB", "MIB" -> 1024L * 1024;
                case "G", "GB", "GIB" -> 1024L * 1024 * 1024;
                default -> -1L;
            };
        }
        if (factor < 0) {
            throw new FSException("tamanho inválido: '" + text + "' (use, p.ex., 512B, 4KB, 1MB)");
        }
        return (long) (Double.parseDouble(m.group(1).replace(',', '.')) * factor);
    }

    static String fmtSize(long n) {
        String[] units = {"GB", "MB", "KB"};
        long[] factors = {1024L * 1024 * 1024, 1024L * 1024, 1024L};
        for (int i = 0; i < units.length; i++) {
            if (n >= factors[i]) {
                return n % factors[i] == 0
                        ? (n / factors[i]) + " " + units[i]
                        : String.format(Locale.ROOT, "%.1f %s", (double) n / factors[i], units[i]);
            }
        }
        return n + " B";
    }

    static String pad(int value, int width) {
        return String.format("%0" + width + "d", value);
    }
}
