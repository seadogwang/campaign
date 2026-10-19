package com.company.campaign.query;

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
 * Requirement 17: the T08 Query AST and planner introduce no SQL string/AST, database, Preview/Web, fixture
 * execution, model/AI or T10+ dependency, and emit no SQL text.
 */
class QueryDependencyBoundaryTest {

    private static final Path QUERY_ROOT =
            Path.of("src", "main", "java", "com", "company", "campaign", "query");

    /**
     * The later T09A fixture executor owns {@code fixture} and T09B storage catalog/SQL owns {@code sql}. T08's
     * own sources are unchanged, so this test keeps guarding exactly the T08 files and does not apply its
     * no-fixture/no-SQL boundary to later, separately-reviewed tasks' packages. T09A and T09B each assert their
     * own boundary in {@code FixtureDependencyBoundaryTest} and {@code SqlDependencyBoundaryTest} respectively.
     */
    private static final List<String> LATER_TASK_OWNED_SUBPACKAGES = List.of("fixture", "sql");

    private static final List<String> ALLOWED_IMPORTS = List.of(
            "java.",
            "com.company.campaign.",
            "com.fasterxml.jackson.",
            "jakarta.validation.");

    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "org.springframework.",
            "jakarta.persistence",
            "javax.persistence",
            "java.sql",
            "javax.sql",
            "org.springframework.data",
            "org.hibernate",
            "org.springframework.ai",
            "com.openai",
            "dev.langchain4j",
            "org.springframework.web",
            "org.springframework.security",
            "com.company.campaign.evaluation",
            "com.company.campaign.builder",
            "com.company.campaign.runtime",
            "com.company.campaign.audience",
            "com.company.campaign.journey",
            "com.company.campaign.metadata.service",
            "com.company.campaign.metadata.repository",
            "com.company.campaign.segment.preview");

    private static final List<String> FORBIDDEN_TEXT = List.of(
            "SELECT ", "INSERT INTO", "UPDATE ", "DELETE FROM", "jdbc:", "DriverManager", "DataSource");

    private static final Pattern IMPORT =
            Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([A-Za-z0-9_.]+)\\s*;");

    @Test
    void theQueryAstAndPlannerSourcesExist() {
        assertThat(javaFiles(QUERY_ROOT).stream().map(Path::getFileName).map(Path::toString))
                .contains("QueryErrorCodes.java", "QueryWire.java",
                        "QueryAst.java", "QueryAstCompiler.java", "QueryAstCodec.java",
                        "SemanticQueryPlan.java", "SemanticQueryPlanCodec.java", "SegmentQueryPlanner.java",
                        "ResolvedWindow.java", "ResolvedWindowBinding.java", "ResolvedDependency.java",
                        "CurrencyBinding.java", "DataScopePredicate.java", "TrustedPlanContext.java");
    }

    @Test
    void importsStayInsideTheAllowedBoundary() {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(QUERY_ROOT)) {
            Matcher matcher = IMPORT.matcher(read(file));
            while (matcher.find()) {
                String imported = matcher.group(2);
                if (ALLOWED_IMPORTS.stream().noneMatch(imported::startsWith)) {
                    violations.add(file + " -> " + imported);
                }
            }
        }
        assertThat(violations).as("imports outside the T08 boundary").isEmpty();
    }

    @Test
    void noDatabaseWebProviderAiOrT10PlusDependencyWasIntroduced() {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(QUERY_ROOT)) {
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
        assertThat(violations).as("forbidden imports in T08").isEmpty();
    }

    @Test
    void noSqlTextOrDatabaseApiAppearsInT08Sources() {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(QUERY_ROOT)) {
            String source = read(file);
            for (String forbidden : FORBIDDEN_TEXT) {
                if (source.contains(forbidden)) {
                    violations.add(file + " -> " + forbidden);
                }
            }
        }
        assertThat(violations).as("SQL or database text in T08").isEmpty();
    }

    @Test
    void noSqlAstOrPreviewArtifactExistsInT08() {
        assertThat(javaFiles(QUERY_ROOT).stream().map(Path::getFileName).map(Path::toString))
                .noneMatch(name -> name.contains("Sql") || name.contains("Preview") || name.contains("Fixture"));
    }

    private static List<Path> javaFiles(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !isInLaterTaskOwnedSubpackage(root, path))
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static boolean isInLaterTaskOwnedSubpackage(Path root, Path file) {
        Path relative = root.relativize(file);
        if (relative.getNameCount() < 2) {
            return false;
        }
        return LATER_TASK_OWNED_SUBPACKAGES.contains(relative.getName(0).toString());
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}