package io.freemytv.airplay;

import java.util.LinkedHashMap;
import java.util.Map;

/** The "key=value" lines of files/airplay.txt and its friends: one per line, the first '=' splits, blanks trimmed. */
public final class KeyValues {
    private KeyValues() {}

    public static Map<String, String> parse(String text) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) m.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        return m;
    }

    /** Line breaks in values become spaces (a name can't break the file). */
    public static String format(Map<String, String> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            sb.append(e.getKey().replaceAll("[=\\r\\n]", " ").trim()).append('=')
                    .append(e.getValue().replaceAll("[\\r\\n]", " ").trim()).append('\n');
        }
        return sb.toString();
    }
}
