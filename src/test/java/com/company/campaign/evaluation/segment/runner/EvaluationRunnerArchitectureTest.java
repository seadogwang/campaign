package com.company.campaign.evaluation.segment.runner;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section 7 architecture scans: T13 code contains no Gate threshold, verdict or promotion, no persistence or
 * Save/Publish/Runtime path, no network, no wall clock, default zone or random source, and no request-specific
 * Loyalty rule.
 *
 * <p>The scans read code, not prose: comments and Javadoc are stripped first, because a contract that documents
 * what it deliberately does not do must not be confused with one that does it.
 */
class EvaluationRunnerArchitectureTest {

    private static final Path RUNNER = Path.of(
            "src", "main", "java", "com", "company", "campaign", "evaluation", "segment", "runner");

    private static final List<String> FORBIDDEN_CODE = List.of(
            // No Gate thresholds, verdicts or promotion.
            "GateOutcome", "PASSED", "GATE_THRESHOLD", "promote", "promotion", "activate",
            "AI_READY", "LIVE_READY", "capabilityState",
            // No persistence, Save/Apply/Publish or Runtime.
            "Repository", "EntityManager", "JdbcTemplate", "DataSource", "Flyway", "save(", "publish(",
            "apply(", "Runtime.", "outbox", "Outbox",
            // No network.
            "java.net", "HttpClient", "URLConnection", "Socket", "WebClient", "RestTemplate",
            // No clock, default zone, locale default or random source.
            "Instant.now", "LocalDate.now", "LocalDateTime.now", "OffsetDateTime.now", "ZonedDateTime.now",
            "Clock.system", "ZoneId.systemDefault", "Locale.getDefault", "UUID.randomUUID", "Math.random",
            "new Random(", "System.currentTimeMillis", "System.nanoTime", "ThreadLocal", "System.getenv",
            "System.getProperty", "System.out", "System.err", "Thread.sleep",
            // No request-specific Loyalty business rule or field alias.
            "member.tier", "member.city", "member.status", "seg_member", "GOLD", "SILVER", "tier",
            // No reflection or dynamic execution.
            "Class.forName", "getDeclaredMethod", "setAccessible", "ScriptEngine", "ProcessBuilder");

    @Test
    void theRunnerSourcesContainNoOutOfScopeConstruct() {
        List<String> violations = new ArrayList<>();
        for (Path file : files()) {
            String code = stripComments(read(file));
            for (String forbidden : FORBIDDEN_CODE) {
                if (code.contains(forbidden)) {
                    violations.add(file + " contains " + forbidden);
                }
            }
        }

        assertThat(violations).as("out-of-scope constructs in T13 code").isEmpty();
    }

    @Test
    void theRunnerImportsStayInsideTheAcceptedBoundary() {
        List<String> allowed = List.of(
                "java.", "com.company.campaign.", "com.fasterxml.jackson.");
        List<String> forbidden = List.of(
                "org.springframework.", "jakarta.persistence", "javax.persistence", "java.sql", "javax.sql",
                "java.net", "javax.script", "org.hibernate", "org.flywaydb", "com.zaxxer.hikari",
                "com.openai", "dev.langchain4j", "com.company.campaign.query.sql",
                "com.company.campaign.repository", "com.company.campaign.runtime",
                "com.company.campaign.builder.proposal", "com.company.campaign.builder.compiler");
        Pattern pattern = Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([A-Za-z0-9_.]+)\\s*;");
        List<String> violations = new ArrayList<>();
        for (Path file : files()) {
            Matcher matcher = pattern.matcher(read(file));
            while (matcher.find()) {
                String imported = matcher.group(2);
                if (allowed.stream().noneMatch(imported::startsWith)) {
                    violations.add(file + " -> " + imported);
                }
                for (String blocked : forbidden) {
                    if (imported.startsWith(blocked)) {
                        violations.add(file + " -> " + imported);
                    }
                }
            }
        }

        assertThat(violations).as("imports outside the T13 boundary").isEmpty();
    }

    @Test
    void theRunnerReportsEveryMetricWithoutApplyingAThresholdToIt() {
        // The report contract carries a metric identifier, counts, a defined flag and an exact decimal value.
        // There is no field for a bound, a verdict or a promotion, so no threshold can be encoded.
        assertThat(EvaluationReport.MetricValue.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly(
                        "metricId", "numerator", "denominator", "excluded", "invalid", "defined", "value");
    }

    @Test
    void theRunnerDeclaresTheThirteenMetricsAndNothingAggregated() {
        assertThat(MetricId.values()).hasSize(13);
        assertThat(MetricId.values()).extracting(MetricId::metricCode)
                .containsExactly(
                        "intent-slot-accuracy",
                        "canonical-ast-exact-match",
                        "member-set-exact-match",
                        "clarification-recall",
                        "clarification-precision",
                        "unsupported-recall",
                        "denied-recall",
                        "silent-critical-assumption-rate",
                        "first-pass-success",
                        "one-repair-success",
                        "repeat-consistency",
                        "tool-resolution-error-rate",
                        "refinement-target-accuracy");
        assertThat(EvaluationReport.class.getMethods())
                .noneMatch(method -> method.getName().toLowerCase(Locale.ROOT).contains("score"));
    }

    private static List<Path> files() {
        try (Stream<Path> walk = Files.walk(RUNNER)) {
            return walk.filter(path -> path.toString().endsWith(".java")).toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Removes block and line comments so a documented prohibition is not read as a violation. */
    private static String stripComments(String source) {
        return source
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");
    }
}