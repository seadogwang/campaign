package com.company.campaign.query.fixture;

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
 * Requirement 11: the T09A deterministic fixture executor introduces no SQL/JDBC, Spring Web, Preview,
 * persistence, model/AI or T10+ dependency, exposes no mutation, and reads no wall clock or process default.
 */
class FixtureDependencyBoundaryTest {

    private static final Path FIXTURE_ROOT =
            Path.of("src", "main", "java", "com", "company", "campaign", "query", "fixture");

    private static final List<String> ALLOWED_IMPORTS = List.of(
            "java.",
            "com.company.campaign.",
            "com.fasterxml.jackson.");

    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "org.springframework.",
            "jakarta.",
            "javax.",
            "java.sql",
            "javax.sql",
            "org.hibernate",
            "org.springframework.data",
            "org.springframework.ai",
            "com.openai",
            "dev.langchain4j",
            "com.company.campaign.builder",
            "com.company.campaign.preview",
            "com.company.campaign.runtime",
            "com.company.campaign.message",
            "com.company.campaign.reward",
            "com.company.campaign.schedule",
            "com.company.campaign.audience",
            "com.company.campaign.journey");

    private static final List<String> FORBIDDEN_TEXT = List.of(
            "SELECT ", "INSERT INTO", "UPDATE ", "DELETE FROM", "jdbc:", "DriverManager", "DataSource",
            "PreparedStatement", "ConnectionFactory", "Sql");

    private static final List<String> FORBIDDEN_WALL_CLOCK = List.of(
            "Instant.now", "LocalDate.now", "LocalDateTime.now", "ZonedDateTime.now", "OffsetDateTime.now",
            "System.currentTimeMillis", "System.nanoTime", "Math.random", "new Random", "UUID.randomUUID",
            "TimeZone.getDefault", "Locale.getDefault", "ZoneId.systemDefault", "ChronoUnit.NANOS");

    private static final List<String> FORBIDDEN_NAMES = List.of("Sql", "Preview", "Controller", "MockMvc", "Jdbc");

    private static final Pattern IMPORT =
            Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([A-Za-z0-9_.]+)\\s*;");

    @Test
    void theFixtureExecutorSourcesExist() {
        assertThat(javaFiles().stream().map(Path::getFileName).map(Path::toString))
                .contains(
                        "FixtureErrorCodes.java", "FixtureException.java", "FixtureDataset.java",
                        "FixtureDatasetLoader.java", "FixtureMapping.java", "FixtureScopeBinding.java",
                        "FxRateSnapshot.java", "FixtureExecutor.java", "FixtureExecutionResult.java",
                        "DatasetField.java", "DatasetValues.java");
    }

    @Test
    void importsStayInsideTheAllowedBoundary() {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles()) {
            Matcher matcher = IMPORT.matcher(read(file));
            while (matcher.find()) {
                String imported = matcher.group(2);
                if (ALLOWED_IMPORTS.stream().noneMatch(imported::startsWith)) {
                    violations.add(file + " -> " + imported);
                }
            }
        }
        assertThat(violations).as("imports outside the T09A boundary").isEmpty();
    }

    @Test
    void noDatabaseWebProviderPreviewOrT10PlusDependencyWasIntroduced() {
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
        assertThat(violations).as("forbidden imports in T09A").isEmpty();
    }

    @Test
    void noSqlJdbcTextOrDatabaseApiAppearsInT09ASources() {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles()) {
            String source = read(file);
            for (String forbidden : FORBIDDEN_TEXT) {
                if (source.contains(forbidden)) {
                    violations.add(file + " -> " + forbidden);
                }
            }
        }
        assertThat(violations).as("SQL or database text in T09A").isEmpty();
    }

    @Test
    void noSqlPreviewOrControllerArtifactExistsInTheFixturePackage() {
        assertThat(javaFiles().stream().map(Path::getFileName).map(Path::toString))
                .noneMatch(name -> FORBIDDEN_NAMES.stream().anyMatch(name::contains));
    }

    @Test
    void noWallClockOrProcessDefaultIsRead() {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles()) {
            String source = read(file);
            for (String forbidden : FORBIDDEN_WALL_CLOCK) {
                if (source.contains(forbidden)) {
                    violations.add(file + " -> " + forbidden);
                }
            }
        }
        assertThat(violations).as("wall clock or process default in T09A").isEmpty();
    }

    @Test
    void noMutationPersistenceOrOutboxCapabilityIsReferenced() {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles()) {
            Matcher matcher = IMPORT.matcher(read(file));
            while (matcher.find()) {
                String imported = matcher.group(2);
                for (String forbidden : List.of(
                        "Repository", "Outbox", "JpaRepository", "EntityManager", "AuditSink", "Publisher")) {
                    if (imported.contains(forbidden)) {
                        violations.add(file + " -> " + imported);
                    }
                }
            }
        }
        assertThat(violations).as("a mutation or outbox capability was referenced").isEmpty();
    }

    private static List<Path> javaFiles() {
        try (Stream<Path> walk = Files.walk(FIXTURE_ROOT)) {
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