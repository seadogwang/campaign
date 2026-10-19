package com.company.campaign.automation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the automation adapter: a completed custom-model report must not start a duplicate
 * attempt.
 *
 * <p>The coordinating script treats a non-zero process exit as retryable, but the current custom gateway can
 * exit non-zero after producing a complete final report. The script therefore scans its stdout for a
 * completion phrase and skips the retry. This test is a lightweight parser over that script file: it extracts
 * the real completion pattern, proves it recognises the {@code All verification commands pass} phrasing a
 * corrected task emits, and proves the guard exits before the retry path.
 */
class AutomationCompletionReportTest {

    private static final Path SCRIPT = Path.of("scripts", "Invoke-ClaudeTask.ps1");
    private static final Pattern COMPLETION_PATTERN = Pattern.compile("-Pattern\\s+\"([^\"]+)\"");

    @Test
    void theCoordinatorRecognizesACompletedVerificationReport() {
        Pattern completion = scriptCompletionPattern();

        // The phrasing a corrected task must emit to be recognised.
        assertThat(completion.matcher("All verification commands pass").find()).isTrue();
        // The previously accepted completion phrasings stay recognised.
        assertThat(completion.matcher("All required work is complete").find()).isTrue();
        assertThat(completion.matcher("All work is complete").find()).isTrue();
        assertThat(completion.matcher("All verification passes").find()).isTrue();
        assertThat(completion.matcher("T08-R2 is complete. Here is the report.").find()).isTrue();
        assertThat(completion.matcher("T09A-R6 is complete.").find()).isTrue();
        assertThat(completion.matcher("Scope confirmation: no T08+ behavior added.").find()).isTrue();
        assertThat(completion.matcher("See the completion report above.").find()).isTrue();

        // A report that has not finished must not be mistaken for a completed one.
        assertThat(completion.matcher("Verification is still running; nothing is complete yet.").find())
                .isFalse();
    }

    @Test
    void aRecognizedCompletionReportSkipsTheDuplicateRetry() {
        String script = read(SCRIPT);

        int guard = script.indexOf("if ($completionReported)");
        int retry = script.indexOf("Retrying in");
        assertThat(guard).as("the completion guard must exist").isGreaterThan(0);
        assertThat(retry).as("the retry path must exist").isGreaterThan(guard);
        // The guard exits before the retry sleep, so a recognised report performs no duplicate attempt.
        assertThat(script.substring(guard, retry)).contains("exit 0");
    }

    private static Pattern scriptCompletionPattern() {
        String script = read(SCRIPT);
        Matcher matcher = COMPLETION_PATTERN.matcher(script);
        assertThat(matcher.find()).as("the script must define a completion pattern").isTrue();
        return Pattern.compile(matcher.group(1));
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
