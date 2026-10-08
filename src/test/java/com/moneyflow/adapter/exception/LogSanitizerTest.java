package com.moneyflow.adapter.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class LogSanitizerTest {

    @Test
    void escapesLineBreaks() {
        assertThat(LogSanitizer.sanitize("a\nb\r\nc")).isEqualTo("a\\nb\\r\\nc");
    }

    @Test
    void keepsOrdinaryText() {
        assertThat(LogSanitizer.sanitize("type 'Grüße €' is invalid")).isEqualTo("type 'Grüße €' is invalid");
    }

    @Test
    void capsLengthAt300Characters() {
        assertThat(LogSanitizer.sanitize("x".repeat(300))).isEqualTo("x".repeat(300));
        assertThat(LogSanitizer.sanitize("x".repeat(301))).isEqualTo("x".repeat(300) + "...");
    }

    @Test
    void handlesNull() {
        assertThat(LogSanitizer.sanitize(null)).isEqualTo("null");
    }

    @Test
    void describesTheCauseChainOnOneLine() {
        IllegalStateException failure = new IllegalStateException(
                "Balance API request failed", new IOException("connection reset\n2026-10-08 INFO forged"));

        assertThat(LogSanitizer.describe(failure)).isEqualTo("java.lang.IllegalStateException: Balance API request "
                + "failed <- java.io.IOException: connection reset\\n2026-10-08 INFO forged");
    }

    @Test
    void describesAnExceptionWithoutAMessageByClassName() {
        assertThat(LogSanitizer.describe(new NullPointerException())).isEqualTo("java.lang.NullPointerException");
    }

    @Test
    void describesAtMostTenExceptions() {
        Throwable chain = new IllegalStateException("root");
        for (int i = 0; i < 9; i++) {
            chain = new IllegalStateException("level " + i, chain);
        }
        assertThat(LogSanitizer.describe(chain)).endsWith("root");

        String longer = LogSanitizer.describe(new IllegalStateException("level 9", chain));
        assertThat(longer.split(" <- ", -1)).hasSize(11);
        assertThat(longer).endsWith("level 0 <- ...").doesNotContain("root");
    }

    @Test
    void describeStopsOnACauseCycle() {
        IllegalStateException a = new IllegalStateException("a");
        a.initCause(new IllegalStateException("b", a));

        assertThat(LogSanitizer.describe(a)).endsWith(" <- ...");
    }
}
