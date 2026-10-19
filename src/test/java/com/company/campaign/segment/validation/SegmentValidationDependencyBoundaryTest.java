package com.company.campaign.segment.validation;

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
 * Requirement 19 (boundary part): the T07 validator and canonicalizer stay framework-independent and
 * introduce no T08+ behavior — no Query AST/planner, no SQL, no database, no Web and no model/AI dependency.
 */
class SegmentValidationDependencyBoundaryTest {

    private static final Path SEGMENT_ROOT =
            Path.of("src", "main", "java", "com", "company", "campaign", "segment");
    private static final Path VALIDATION_ROOT = SEGMENT_ROOT.resolve("validation");
    private static final Path CANONICAL_ROOT = SEGMENT_ROOT.resolve("canonical");

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
            "com.company.campaign.query",
            "com.company.campaign.builder",
            "com.company.campaign.runtime",
            "com.company.campaign.metadata.service",
            "com.company.campaign.metadata.repository");

    private static final List<String> FORBIDDEN_TEXT = List.of(
            "SELECT ", "INSERT INTO", "UPDATE ", "DELETE FROM", "jdbc:", "DriverManager", "DataSource");

    private static final Pattern IMPORT =
            Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([A-Za-z0-9_.]+)\\s*;");

    @Test
    void validatorAndCanonicalizerSourcesExist() {
        assertThat(javaFiles(VALIDATION_ROOT).stream().map(Path::getFileName).map(Path::toString))
                .contains("SegmentValidator.java", "SegmentValidationResult.java", "SegmentValidationPolicy.java",
                        "SegmentValidationErrorCodes.java", "SegmentValidationStage.java");
        assertThat(javaFiles(CANONICAL_ROOT).stream().map(Path::getFileName).map(Path::toString))
                .contains("CanonicalSegment.java", "CanonicalNode.java", "CanonicalValue.java",
                        "CanonicalWindow.java", "CanonicalSegmentCodec.java");
    }

    @Test
    void validatorAndCanonicalizerImportsStayInsideTheAllowedBoundary() {
        List<String> violations = new ArrayList<>();
        for (Path file : allT07Files()) {
            Matcher matcher = IMPORT.matcher(read(file));
            while (matcher.find()) {
                String imported = matcher.group(2);
                if (ALLOWED_IMPORTS.stream().noneMatch(imported::startsWith)) {
                    violations.add(file + " -> " + imported);
                }
            }
        }
        assertThat(violations).as("imports outside the T07 boundary").isEmpty();
    }

    @Test
    void validatorAndCanonicalizerDoNotReachPersistenceWebProvidersAiQueryOrPlanner() {
        List<String> violations = new ArrayList<>();
        for (Path file : allT07Files()) {
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
        assertThat(violations).as("forbidden imports in T07").isEmpty();
    }

    @Test
    void validatorAndCanonicalizerContainNoSqlOrDatabaseText() {
        List<String> violations = new ArrayList<>();
        for (Path file : allT07Files()) {
            String source = read(file);
            for (String forbidden : FORBIDDEN_TEXT) {
                if (source.contains(forbidden)) {
                    violations.add(file + " -> " + forbidden);
                }
            }
        }
        assertThat(violations).as("SQL or database text in T07").isEmpty();
    }

    @Test
    void noQueryAstOrPlanArtifactExistsInT07() {
        assertThat(javaFiles(VALIDATION_ROOT).stream().map(Path::getFileName).map(Path::toString))
                .noneMatch(name -> name.contains("Query") || name.contains("Plan") || name.contains("Sql"));
        assertThat(javaFiles(CANONICAL_ROOT).stream().map(Path::getFileName).map(Path::toString))
                .noneMatch(name -> name.contains("Query") || name.contains("Plan") || name.contains("Sql"));
    }

    private static List<Path> allT07Files() {
        List<Path> files = new ArrayList<>();
        files.addAll(javaFiles(VALIDATION_ROOT));
        files.addAll(javaFiles(CANONICAL_ROOT));
        return files;
    }

    private static List<Path> javaFiles(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
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