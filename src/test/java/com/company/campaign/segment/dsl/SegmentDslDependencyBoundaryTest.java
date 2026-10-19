package com.company.campaign.segment.dsl;

import com.company.campaign.segment.dsl.contract.SegmentDsl;
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
 * Guards the {@code segment} boundary: every Segment package stays framework-independent and contains no
 * query, AI, database or Web implementation.
 *
 * <p>T18 Phase A appends the {@code segment.reference} package and the {@code AUDIENCE_REFERENCE} predicate,
 * so the earlier blanket "this tree never mentions an audience reference" assertion is superseded. What it
 * protected is now protected two stronger ways: the reference subtype's shape is pinned as a closed five-field
 * node by {@code SegmentDslAudienceReferenceTest} and the checked-in JSON Schema, and the checks below still
 * forbid every framework, persistence, Web, provider and query import across the whole tree — including the
 * new reference package, which is exactly the package most tempting to couple to storage.
 */
class SegmentDslDependencyBoundaryTest {

    private static final Path SOURCE_ROOT = Path.of("src", "main", "java", "com", "company", "campaign", "segment");

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

    /**
     * Text that must never appear in the DSL package itself.
     *
     * <p>The reference subtype is part of T18's contract, so its discriminator is expected in
     * {@code segment.dsl}; what must never appear there is a trusted fact about a reference. The reference
     * node is a pin and nothing else, so these spellings have no business in the editable contract.
     */
    private static final List<String> FORBIDDEN_IN_DSL_TEXT = List.of(
            "tenantId",
            "programId",
            "dataScopeId",
            "isLatest",
            "findCurrent",
            "findLatest");

    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([A-Za-z0-9_.]+)\\s*;");

    @Test
    void segmentSourcesStayFrameworkIndependent() {
        List<Path> files = javaFiles();
        assertThat(files).as("segment source files").hasSizeGreaterThan(20);

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
        assertThat(violations).as("imports outside the segment boundary").isEmpty();
    }

    @Test
    void segmentSourcesDoNotReachPersistenceWebProvidersQueryOrAi() {
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
        assertThat(violations).as("forbidden imports in segment").isEmpty();
    }

    @Test
    void theEditableDslContractCarriesNoTrustedReferenceFact() {
        List<Path> dslFiles = javaFiles().stream()
                .filter(file -> file.startsWith(SOURCE_ROOT.resolve("dsl")))
                .toList();
        assertThat(dslFiles).as("T05 DSL source files").hasSizeGreaterThan(10);

        List<String> violations = new ArrayList<>();
        for (Path file : dslFiles) {
            String source = read(file);
            for (String forbidden : FORBIDDEN_IN_DSL_TEXT) {
                if (source.contains(forbidden)) {
                    violations.add(file + " -> " + forbidden);
                }
            }
        }
        assertThat(violations)
                .as("a trusted reference fact leaked into the editable DSL contract")
                .isEmpty();
    }

    @Test
    void noCanonicalAstHashArtifactExistsInTheT05Contract() {
        assertThat(java.util.Arrays.stream(SegmentDsl.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .doesNotContain("canonicalAstHash");
        assertThat(java.util.Arrays.stream(SegmentDsl.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName))
                .doesNotContain("canonicalAstHash");
    }

    /**
     * The {@code segment} package holds the accepted T05 DSL, the T07 validator/canonicalizer packages, the
     * T10 Intent/Resolver packages and the T18 Existing Audience Reference core. T07 legitimately adds
     * {@code segment/validation} and {@code segment/canonical}; T10 legitimately adds {@code segment/intent}
     * and {@code segment/resolution}; T18 legitimately adds {@code segment/reference}. The framework
     * independence and forbidden-import checks above already cover every file under {@code segment} — the new
     * reference package included — so this assertion only pins the exact package set rather than claiming the
     * tree is DSL-only.
     */
    @Test
    void segmentPackageHoldsOnlyTheAcceptedSegmentPackages() {
        List<Path> files = javaFiles();
        List<Path> allowedRoots = List.of(
                SOURCE_ROOT.resolve("dsl"),
                SOURCE_ROOT.resolve("validation"),
                SOURCE_ROOT.resolve("canonical"),
                SOURCE_ROOT.resolve("intent"),
                SOURCE_ROOT.resolve("resolution"),
                SOURCE_ROOT.resolve("reference"));
        assertThat(files).allMatch(file -> allowedRoots.stream().anyMatch(file::startsWith));
        assertThat(files.stream().map(file -> file.getFileName().toString()))
                .contains("SegmentDsl.java", "SegmentDslCodec.java", "TypedValue.java", "Predicate.java",
                        "TimeWindow.java", "MetadataBinding.java");
    }

    private static List<Path> javaFiles() {
        try (Stream<Path> walk = Files.walk(SOURCE_ROOT)) {
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