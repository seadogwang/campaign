package com.company.campaign.metadata;

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
 * Guards the T04 boundaries: the metadata package stays framework-independent, and the T03 evaluation
 * package does not reach into T04 implementation classes (the descriptor adapter is deliberately one-way).
 */
class MetadataDependencyBoundaryTest {

    private static final Path SOURCE_ROOT = Path.of("src", "main", "java", "com", "company", "campaign");

    private static final List<String> ALLOWED_IMPORTS = List.of(
            "java.",
            "com.company.campaign.",
            "com.fasterxml.jackson.",
            "jakarta.validation.");

    private static final List<String> FORBIDDEN_IN_METADATA = List.of(
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
            "org.springframework.security");

    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([A-Za-z0-9_.]+)\\s*;");

    @Test
    void metadataSourcesStayFrameworkIndependent() {
        List<Path> files = javaFiles(SOURCE_ROOT.resolve("metadata"));
        assertThat(files).as("metadata source files").hasSizeGreaterThan(20);

        List<String> violations = new ArrayList<>();
        for (Path file : files) {
            Matcher matcher = IMPORT.matcher(read(file));
            while (matcher.find()) {
                String imported = matcher.group(2);
                if (ALLOWED_IMPORTS.stream().noneMatch(imported::startsWith)) {
                    violations.add(file + " -> " + imported);
                }
            }
        }
        assertThat(violations).as("imports outside the metadata boundary").isEmpty();
    }

    @Test
    void metadataSourcesDoNotReachPersistenceWebOrProviders() {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(SOURCE_ROOT.resolve("metadata"))) {
            Matcher matcher = IMPORT.matcher(read(file));
            while (matcher.find()) {
                String imported = matcher.group(2);
                for (String forbidden : FORBIDDEN_IN_METADATA) {
                    if (imported.startsWith(forbidden)) {
                        violations.add(file + " -> " + imported);
                    }
                }
            }
        }
        assertThat(violations).as("forbidden imports in metadata").isEmpty();
    }

    /**
     * T03's evaluation contract and loader must not couple to T04 implementation classes: the loader hashes
     * Metadata payloads and treats them as opaque bytes, and the T04 descriptor adapter is deliberately
     * one-way.
     *
     * <p>T06 narrows that rule rather than dropping it. A candidate corpus is built against a real T04
     * snapshot, so T06's validation layer consumes T04 as a typed input: it parses the payload, binds the
     * expected Segment DSL to the snapshot's identity and checks that referenced definition ids exist. That
     * interpretation lives only in {@code evaluation/segment/validation}; the T03 contract and loader in
     * {@code evaluation/segment/contract} and {@code evaluation/segment/loader} stay Metadata-opaque, which
     * is what this check still enforces for them.
     *
     * <p>T13 narrows it once more for its own runner subpackage. An evaluation run must execute the accepted
     * pipeline against the exact pinned per-Case snapshot, so the runner parses the reviewed payload as a
     * typed T04 snapshot and freezes its hash. The T03 contract and loader stay untouched: the runner is a
     * consumer of the loader, not part of it.
     */
    @Test
    void evaluationContractAndLoaderStayIndependentOfMetadataImplementationClasses() {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(SOURCE_ROOT.resolve("evaluation"))) {
            String normalized = file.toString().replace('\\', '/');
            if (normalized.contains("/evaluation/segment/validation/")
                    || normalized.contains("/evaluation/segment/runner/")) {
                continue;
            }
            Matcher matcher = IMPORT.matcher(read(file));
            while (matcher.find()) {
                String imported = matcher.group(2);
                if (imported.startsWith("com.company.campaign.metadata")) {
                    violations.add(file + " -> " + imported);
                }
            }
        }
        assertThat(violations).as("T03 contract/loader must not couple to T04 implementation classes").isEmpty();
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