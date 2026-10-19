package com.company.campaign.evaluation.segment.review;

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
 * Guards the T06R review boundary: the host-only boundary stays deterministic and reaches no later task.
 *
 * <p>The import allowlist has to be justified for every new dependency, so a controller, a model Tool, a
 * repository, a security adapter, a second serializer or a T10+ package cannot quietly appear in a package
 * whose whole purpose is to record human decisions deterministically.
 */
class ReviewWorkflowDependencyBoundaryTest {

    private static final Path SOURCE_ROOT = Path.of(
            "src", "main", "java", "com", "company", "campaign", "evaluation", "segment", "review");

    private static final List<String> ALLOWED_IMPORTS = List.of(
            "java.",
            "com.company.campaign.",
            "com.fasterxml.jackson.");

    /** Later tasks, the security adapter, persistence and web are all outside this boundary. */
    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "com.company.campaign.security",
            "com.company.campaign.query",
            "com.company.campaign.runtime",
            "com.company.campaign.model",
            "com.company.campaign.builder",
            "com.company.campaign.preview",
            "com.company.campaign.metadata",
            "com.company.campaign.audience",
            "com.company.campaign.journey",
            "com.company.campaign.schedule",
            "com.company.campaign.message",
            "com.company.campaign.reward",
            "com.company.campaign.segment.",
            "org.springframework.",
            "jakarta.",
            "javax.",
            "java.sql",
            "java.nio.file",
            "java.util.UUID",
            "java.util.Random",
            "org.hibernate",
            "com.openai",
            "dev.langchain4j");

    /** Nondeterministic or mutating constructs and model-Tool shapes the boundary may never contain. */
    private static final List<String> FORBIDDEN_TEXT = List.of(
            "Instant.now",
            "LocalDate.now",
            "LocalDateTime.now",
            "Clock.system",
            "System.currentTimeMillis",
            "System.nanoTime",
            "UUID.randomUUID",
            "ThreadLocal",
            "Math.random",
            "new Random(",
            "System.getenv",
            "System.getProperty",
            "Files.write",
            "Files.delete",
            "Files.createDirectories",
            "DriverManager",
            "EntityManager",
            "@RestController",
            "@Controller",
            "ToolEnvelope",
            "ToolInput");

    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([A-Za-z0-9_.]+)\\s*;");

    @Test
    void reviewSourcesStayInsideTheDeterministicBoundary() {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles()) {
            String source = read(file);
            Matcher matcher = IMPORT.matcher(source);
            while (matcher.find()) {
                String imported = matcher.group(2);
                if (ALLOWED_IMPORTS.stream().noneMatch(imported::startsWith)) {
                    violations.add(file + " -> " + imported);
                }
                for (String forbidden : FORBIDDEN_IMPORTS) {
                    if (imported.startsWith(forbidden)) {
                        violations.add(file + " -> forbidden import " + imported);
                    }
                }
            }
        }
        assertThat(violations).as("imports outside the review boundary").isEmpty();
    }

    @Test
    void reviewSourcesContainNoClockRandomGlobalContextOrModelTool() {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles()) {
            String source = read(file);
            for (String forbidden : FORBIDDEN_TEXT) {
                if (source.contains(forbidden)) {
                    violations.add(file + " -> " + forbidden);
                }
            }
        }
        assertThat(violations).as("nondeterministic or out-of-boundary constructs").isEmpty();
    }

    @Test
    void theReviewBoundaryIsPresent() {
        assertThat(javaFiles()).as("review boundary sources").hasSizeGreaterThanOrEqualTo(10);
    }

    private static List<Path> javaFiles() {
        try (Stream<Path> walk = Files.walk(SOURCE_ROOT)) {
            List<Path> files = walk.filter(path -> path.toString().endsWith(".java")).toList();
            assertThat(files).as("review source files").isNotEmpty();
            return files;
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