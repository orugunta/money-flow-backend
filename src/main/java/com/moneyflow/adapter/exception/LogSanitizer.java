package com.moneyflow.adapter.exception;

/**
 * Makes text that may contain raw upstream data safe to log. Applied where logging happens, so exception messages
 * themselves keep the original text.
 */
final class LogSanitizer {

    private static final int MAX_LENGTH = 300;
    private static final int MAX_CAUSES = 10;

    private LogSanitizer() {
    }

    static String sanitize(String text) {
        if (text == null) {
            return "null";
        }

        String safe = text
                .replace("\r", "\\r")
                .replace("\n", "\\n");

        return safe.length() <= MAX_LENGTH
                ? safe
                : safe.substring(0, MAX_LENGTH) + "...";
    }

    /**
     * The exception and its causes on one line, outermost first: {@code class: message <- class: message}, each
     * message sanitized. At most 10 entries, which also stops a cause cycle.
     */
    static String describe(Throwable throwable) {
        StringBuilder out = new StringBuilder();
        Throwable current = throwable;
        for (int i = 0; current != null && i < MAX_CAUSES; i++, current = current.getCause()) {
            if (i > 0) {
                out.append(" <- ");
            }
            out.append(current.getClass().getName());
            if (current.getMessage() != null) {
                out.append(": ").append(sanitize(current.getMessage()));
            }
        }
        if (current != null) {
            out.append(" <- ...");
        }
        return out.toString();
    }
}
