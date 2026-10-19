package com.company.campaign.evaluation.segment.gate;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section 5 architecture scans: T14 contains no model, provider, network, SQL or Repository mutation, no
 * Save/Publish/Runtime path, no wall clock, default zone or random source, no request-specific Loyalty rule
 * and no raw sensitive evidence.
 *
 * <p>The scans read code, not prose: comments and Javadoc are stripped first, because a contract that
 * documents what it deliberately does not do must not be confused with one that does it.
 */
class GateArchitectureTest {

    private static final Path GATE = Path.of(
            "src", "main", "java", "com", "company", "campaign", "evaluation", "segment", "gate");

    private static final List<String> FORBIDDEN_CODE = List.of(
            // No model, provider or agent path.
            "SegmentIntentModelPort", "modelPort", "Anthropic", "anthropic", "openai", "OpenAI",
            "langchain4j", "Provider", "provider",
            // No persistence, SQL or Repository mutation.
            "Repository", "EntityManager", "JdbcTemplate", "DataSource", "Flyway", "Hibernate",
            "java.sql", "javax.sql", "save(", "insert(", "delete(", "update(",
            // No Save, Apply, Publish or Runtime path.
            "publish(", "apply(", "Runtime.", "outbox", "Outbox", "MockMvc", "Controller",
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

    private static final List<String> FORBIDDEN_SENSITIVE = List.of(
            "utterance", "Utterance", "memberId", "MemberId", "member-id", "tenantId", "TenantId",
            "dataScope", "DataScope", "credential", "Credential", "password", "Password", "SELECT ",
            " FROM ", "DROP TABLE", "INSERT INTO", "sk-", "apiKey", "ApiKey");

    @Test
    void theGateSourcesContainNoOutOfScopeConstruct() {
        List<String> violations = new ArrayList<>();
        for (Path file : files()) {
            String code = stripComments(read(file));
            for (String forbidden : FORBIDDEN_CODE) {
                if (code.contains(forbidden)) {
                    violations.add(file + " contains " + forbidden);
                }
            }
        }

        assertThat(violations).as("out-of-scope constructs in T14 code").isEmpty();
    }

    @Test
    void theGateSourcesCarryNoRawSensitiveEvidenceShape() {
        List<String> violations = new ArrayList<>();
        for (Path file : files()) {
            String code = stripComments(read(file));
            for (String forbidden : FORBIDDEN_SENSITIVE) {
                if (code.contains(forbidden)) {
                    violations.add(file + " contains " + forbidden);
                }
            }
        }

        assertThat(violations).as("raw sensitive evidence in T14 code").isEmpty();
    }

    @Test
    void theGateImportsStayInsideTheAcceptedBoundary() {
        List<String> allowed = List.of("java.", "com.company.campaign.", "com.fasterxml.jackson.");
        List<String> forbidden = List.of(
                "org.springframework.", "jakarta.persistence", "javax.persistence", "java.sql", "javax.sql",
                "java.net", "javax.script", "org.hibernate", "org.flywaydb", "com.zaxxer.hikari",
                "com.openai", "dev.langchain4j", "com.company.campaign.query.sql",
                "com.company.campaign.repository", "com.company.campaign.runtime",
                "com.company.campaign.builder.proposal", "com.company.campaign.builder.compiler",
                "com.company.campaign.builder.agent", "com.company.campaign.builder.segment");
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

        assertThat(violations).as("imports outside the T14 boundary").isEmpty();
    }

    @Test
    void noGateSourceDeclaresAModelProviderRepositoryOrRuntimeDependency() {
        // The gate package may reference only value contracts and the accepted evaluation contracts: the
        // model, provider, repository and runtime dependencies of a host are injected through its ports.
        assertThat(files()).isNotEmpty();
        for (Path file : files()) {
            String source = stripComments(read(file));
            assertThat(source).as(file.toString())
                    .doesNotContain("SegmentIntentModelPort")
                    .doesNotContain("Repository")
                    .doesNotContain("Runtime.")
                    .doesNotContain("Provider");
        }
    }

    private static List<Path> files() {
        try (Stream<Path> walk = Files.walk(GATE)) {
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