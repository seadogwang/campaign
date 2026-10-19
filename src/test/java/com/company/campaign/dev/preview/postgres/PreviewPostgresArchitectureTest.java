package com.company.campaign.dev.preview.postgres;

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
 * T34C requirement 3.6: the PostgreSQL Preview package owns no model path, no request SQL path, no mutation and
 * no ambient state.
 *
 * <p>It is a source scan and not a behavioural proof; it exists so a future change that reintroduces a request
 * SQL path, a second raw statement, a mutation, a driver-manager connection or a wall clock fails loudly.
 */
class PreviewPostgresArchitectureTest {

    private static final Path PACKAGE = Path.of(
            "src", "main", "java", "com", "company", "campaign", "dev", "preview", "postgres");

    private static final Path ADAPTER = PACKAGE.resolve("PostgresPreviewExecutionBackend.java");
    private static final Path BOUNDARY = PACKAGE.resolve("PreviewSqlBoundary.java");

    /** Concrete constructs that would mean the adapter left its bounded, read-only, injected boundary. */
    private static final List<String> FORBIDDEN_CONSTRUCTS = List.of(
            "DriverManager", "createStatement", "executeUpdate", "executeLargeUpdate", "addBatch",
            "System.out", "System.err", "java.util.logging", "org.slf4j",
            "Instant.now", "Clock.system", "System.currentTimeMillis", "System.nanoTime",
            "Math.random", "new Random", "UUID.randomUUID", "Locale.getDefault", "TimeZone.getDefault",
            "ZoneId.systemDefault", "ThreadLocal", "SecurityContextHolder");

    /** Imports that would give the adapter a model, a request or a raw SQL source. */
    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "com.company.campaign.builder.segment", "com.company.campaign.preview.api",
            "com.company.campaign.preview.PreviewRequest", "com.company.campaign.preview.PreviewRequestCodec",
            "com.company.campaign.query.fixture", "com.company.campaign.dev.segment.DevelopmentStructuredModelPort");

    /** The SQL statement words a file must not carry as a literal; only the closed scanner may name them. */
    private static final List<String> MUTATION_LITERALS = List.of(
            "\"INSERT", "\"UPDATE", "\"DELETE", "\"DROP", "\"ALTER", "\"TRUNCATE", "\"CREATE", "\"GRANT",
            "\"REVOKE", "\"MERGE");

    private static final Pattern IMPORT =
            Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([A-Za-z0-9_.]+)\\s*;");

    @Test
    void thePackageCarriesNoModelRequestOrFixturePath() {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles()) {
            Matcher matcher = IMPORT.matcher(read(file));
            while (matcher.find()) {
                String imported = matcher.group(2);
                for (String forbidden : FORBIDDEN_IMPORTS) {
                    if (imported.startsWith(forbidden)) {
                        violations.add(file + " -> " + imported);
                    }
                }
            }
        }
        assertThat(violations).as("no model, request-SQL or fixture dependency").isEmpty();
    }

    @Test
    void thePackageCarriesNoAmbientStateMutationOrUnboundedConstruct() {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles()) {
            String source = read(file);
            for (String forbidden : FORBIDDEN_CONSTRUCTS) {
                if (source.contains(forbidden)) {
                    violations.add(file + " -> " + forbidden);
                }
            }
        }
        assertThat(violations).as("no ambient clock, randomness, logging, thread-local or raw statement").isEmpty();
    }

    @Test
    void onlyTheClosedSqlScannerNamesAMutationKeyword() {
        List<String> filesNamingAMutation = new ArrayList<>();
        for (Path file : javaFiles()) {
            String source = read(file);
            for (String literal : MUTATION_LITERALS) {
                if (source.contains(literal)) {
                    filesNamingAMutation.add(file.getFileName().toString());
                    break;
                }
            }
        }
        assertThat(filesNamingAMutation)
                .as("only the closed scanner may carry a mutation keyword, and it never executes anything")
                .containsExactly(BOUNDARY.getFileName().toString());
    }

    @Test
    void theSqlScannerItselfCannotExecuteAnything() {
        String source = read(BOUNDARY);
        assertThat(source)
                .doesNotContain("java.sql")
                .doesNotContain("Connection")
                .doesNotContain("PreparedStatement")
                .doesNotContain("executeQuery")
                .doesNotContain("executeUpdate");
    }

    @Test
    void theAdapterPreparesExactlyOneStatementAndThatStatementIsTheCompiledArtifact() {
        String source = read(ADAPTER);
        assertThat(countOccurrences(source, "prepareStatement(")).isEqualTo(1);
        assertThat(source).contains("prepareStatement(compiled.sql())");
        assertThat(source).contains("PreviewJdbcBinding.bind(statement, compiled.parameters())");
        assertThat(source)
                .as("the adapter never renders, concatenates or stores a query of its own")
                .doesNotContain("append(\"SELECT")
                .doesNotContain("StringBuilder");
    }

    @Test
    void theAutoPublishedPortIsTheAcceptedHostSafeAdapterAndNothingElse() {
        List<String> implementations = new ArrayList<>();
        for (Path file : javaFiles()) {
            if (read(file).contains("implements SegmentPreviewPort")) {
                implementations.add(file.getFileName().toString());
            }
        }
        assertThat(implementations).isEmpty();
        assertThat(read(PACKAGE.resolve("PreviewPostgresConfiguration.java")))
                .contains("new HostSafePreviewPort(");
    }

    private static int countOccurrences(String source, String token) {
        int count = 0;
        int index = source.indexOf(token);
        while (index >= 0) {
            count++;
            index = source.indexOf(token, index + token.length());
        }
        return count;
    }

    private static List<Path> javaFiles() {
        try (Stream<Path> walk = Files.walk(PACKAGE)) {
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
}