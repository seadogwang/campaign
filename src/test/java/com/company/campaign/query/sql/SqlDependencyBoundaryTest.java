package com.company.campaign.query.sql;

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
 * Requirement 17: the T09B storage catalog, SQL AST, compiler and renderer introduce no JDBC/database
 * execution, Spring Web, Preview, fixture, model/provider, mutation, persistence or wall-clock dependency, and
 * expose no raw-SQL or controller artifact.
 */
class SqlDependencyBoundaryTest {

    private static final Path SQL_ROOT =
            Path.of("src", "main", "java", "com", "company", "campaign", "query", "sql");

    private static final List<String> ALLOWED_IMPORTS = List.of(
            "java.",
            "com.company.campaign.",
            "com.fasterxml.jackson.");

    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "java.sql",
            "javax.sql",
            "org.springframework.",
            "jakarta.",
            "javax.",
            "org.hibernate",
            "org.springframework.data",
            "org.flywaydb",
            "com.zaxxer",
            "com.company.campaign.preview",
            "com.company.campaign.query.fixture",
            "com.company.campaign.builder",
            "com.company.campaign.runtime",
            "com.company.campaign.message",
            "com.company.campaign.reward",
            "com.company.campaign.schedule",
            "com.company.campaign.audience",
            "com.company.campaign.journey",
            "com.openai",
            "dev.langchain4j");

    private static final List<String> FORBIDDEN_TEXT = List.of(
            "jdbc:",
            "DriverManager",
            "DataSource",
            "PreparedStatement",
            "EntityManager",
            "JpaRepository",
            "Repository",
            "Outbox",
            "Publisher",
            "Flyway",
            "HikariPool",
            "ThreadLocal");

    private static final List<String> FORBIDDEN_WALL_CLOCK = List.of(
            "Instant.now",
            "LocalDate.now",
            "LocalDateTime.now",
            "ZonedDateTime.now",
            "OffsetDateTime.now",
            "System.currentTimeMillis",
            "System.nanoTime",
            "Math.random",
            "new Random",
            "UUID.randomUUID",
            "TimeZone.getDefault",
            "Locale.getDefault",
            "ZoneId.systemDefault",
            "Clock.system");

    private static final List<String> FORBIDDEN_NAMES = List.of("Preview", "Controller", "Jdbc", "MockMvc");

    private static final Pattern IMPORT =
            Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([A-Za-z0-9_.]+)\\s*;");

    @Test
    void theSqlBoundarySourcesExist() {
        assertThat(javaFiles().stream().map(Path::getFileName).map(Path::toString))
                .contains(
                        "SqlErrorCodes.java", "SqlException.java", "SqlIdentifier.java", "SqlVersions.java",
                        "SqlParameterType.java", "SqlParameter.java", "TableRef.java",
                        "SqlComparisonOperator.java", "SqlExpression.java", "SqlSelect.java",
                        "SqlCompilePolicy.java", "FxRateBinding.java", "StorageCatalog.java",
                        "SqlRenderer.java", "SqlWire.java", "CompiledSql.java", "SqlCompiler.java");
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
        assertThat(violations).as("imports outside the T09B boundary").isEmpty();
    }

    @Test
    void noDatabaseWebProviderPreviewFixtureOrT10PlusDependencyWasIntroduced() {
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
        assertThat(violations).as("forbidden imports in T09B").isEmpty();
    }

    @Test
    void noDatabaseMutationOrAmbientStateApiAppearsInTheSources() {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles()) {
            String source = read(file);
            for (String forbidden : FORBIDDEN_TEXT) {
                if (source.contains(forbidden)) {
                    violations.add(file + " -> " + forbidden);
                }
            }
        }
        assertThat(violations).as("forbidden database, mutation or ambient-state API in T09B").isEmpty();
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
        assertThat(violations).as("wall clock or process default in T09B").isEmpty();
    }

    @Test
    void noPreviewControllerOrJdbcArtifactExistsInTheSqlPackage() {
        assertThat(javaFiles().stream().map(Path::getFileName).map(Path::toString))
                .noneMatch(name -> FORBIDDEN_NAMES.stream().anyMatch(name::contains));
    }

    private static List<Path> javaFiles() {
        try (Stream<Path> walk = Files.walk(SQL_ROOT)) {
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