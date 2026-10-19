package com.company.campaign.segment.intent;

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
 * Requirement 14: the Intent and Resolver packages have no Agent/Skill, model/provider, T12+, Web,
 * persistence/JDBC/SQL, DSL generation, Preview/Runtime, clock/random/default-locale/global-context,
 * Save/Publish or human-review mutation dependency.
 *
 * <p>The scan is over T10's own {@code src/main/java} subtrees only, using import allow/deny lists plus a
 * bounded forbidden-construct text scan. Word-level scans are deliberately avoided so an explanatory comment
 * cannot be mistaken for a dependency.
 */
class IntentAndResolutionDependencyBoundaryTest {

    private static final Path SEGMENT_ROOT =
            Path.of("src", "main", "java", "com", "company", "campaign", "segment");
    private static final List<Path> T10_ROOTS = List.of(
            SEGMENT_ROOT.resolve("intent"), SEGMENT_ROOT.resolve("resolution"));

    private static final List<String> ALLOWED_IMPORTS = List.of(
            "java.",
            "com.company.campaign.contracts.",
            "com.company.campaign.shared.",
            "com.company.campaign.metadata.MetadataKind",
            "com.company.campaign.metadata.MetadataTextNormalizer",
            "com.company.campaign.metadata.domain.",
            "com.company.campaign.segment.intent.",
            "com.company.campaign.segment.resolution.",
            // T18 admits exactly one further dependency: the host-owned, read-only Existing Audience
            // Reference core. An audience reference is resolved by asking that directory which immutable
            // published version a request may name, so the resolver must be able to speak its contracts.
            // The package is proven clean by referencePackageStaysHostOnly below, so admitting it cannot
            // smuggle an Agent/model/persistence/DSL dependency into T10 through the back door.
            "com.company.campaign.segment.reference.",
            "com.fasterxml.jackson.");

    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "org.springframework.", "jakarta.", "javax.",
            "java.sql", "java.util.UUID", "java.util.Random",
            "org.hibernate", "com.openai", "dev.langchain4j",
            "com.company.campaign.query",
            "com.company.campaign.preview",
            "com.company.campaign.builder",
            "com.company.campaign.runtime",
            "com.company.campaign.model",
            "com.company.campaign.audience",
            "com.company.campaign.journey",
            "com.company.campaign.schedule",
            "com.company.campaign.message",
            "com.company.campaign.reward",
            "com.company.campaign.segment.dsl",
            "com.company.campaign.segment.validation",
            "com.company.campaign.segment.canonical",
            "com.company.campaign.evaluation",
            "com.company.campaign.metadata.repository",
            "com.company.campaign.metadata.service",
            "com.company.campaign.contracts.ToolEnvelope",
            "com.company.campaign.contracts.ToolInput",
            "com.company.campaign.contracts.SkillEnvelope",
            "com.company.campaign.contracts.AgentEnvelope");

    private static final List<String> FORBIDDEN_TEXT = List.of(
            "Instant.now", "LocalDate.now", "LocalDateTime.now", "OffsetDateTime.now",
            "Clock.system", "System.currentTimeMillis", "System.nanoTime",
            "UUID.randomUUID", "ThreadLocal", "Math.random", "new Random(",
            "Locale.getDefault", "TimeZone.getDefault", "System.getenv", "System.getProperty",
            "Files.write", "Files.delete", "Files.createDirectories",
            "DriverManager", "EntityManager", "SELECT ", "INSERT INTO", "DELETE FROM", "jdbc:");

    private static final Pattern IMPORT =
            Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([A-Za-z0-9_.]+)\\s*;");

    /** The one source root the T10 boundary now admits, and the exact set it may itself reach. */
    private static final Path REFERENCE_ROOT = SEGMENT_ROOT.resolve("reference");

    /**
     * What the admitted reference core may import. It is the host-only world of immutable ports and its own
     * security/metadata inputs: no DSL, validation, canonicalization, query, preview, builder, evaluation,
     * Agent or persistence package, and nothing outside the JDK.
     */
    private static final List<String> REFERENCE_ALLOWED_IMPORTS = List.of(
            "java.",
            "com.company.campaign.contracts.",
            "com.company.campaign.shared.",
            "com.company.campaign.metadata.domain.",
            "com.company.campaign.security.");

    @Test
    void importsStayInsideTheAllowedT10Boundary() {
        List<String> violations = new ArrayList<>();
        for (Path file : t10Files()) {
            Matcher matcher = IMPORT.matcher(read(file));
            while (matcher.find()) {
                String imported = matcher.group(2);
                if (ALLOWED_IMPORTS.stream().noneMatch(imported::startsWith)) {
                    violations.add(file + " -> " + imported);
                }
            }
        }
        assertThat(violations).as("imports outside the T10 boundary").isEmpty();
    }

    @Test
    void forbiddenDependenciesAreAbsent() {
        List<String> violations = new ArrayList<>();
        for (Path file : t10Files()) {
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
        assertThat(violations).as("forbidden imports in T10").isEmpty();
    }

    @Test
    void clockRandomLocaleSqlAndGlobalStateConstructsAreAbsent() {
        List<String> violations = new ArrayList<>();
        for (Path file : t10Files()) {
            String source = read(file);
            for (String forbidden : FORBIDDEN_TEXT) {
                if (source.contains(forbidden)) {
                    violations.add(file + " -> " + forbidden);
                }
            }
        }
        assertThat(violations).as("clock/random/locale/SQL constructs in T10").isEmpty();
    }

    @Test
    void t10DoesNotConstructASegmentDslOrReachTheDslContract() {
        List<Path> dslImports = new ArrayList<>();
        for (Path file : t10Files()) {
            if (read(file).contains("com.company.campaign.segment.dsl")) {
                dslImports.add(file);
            }
        }
        assertThat(dslImports).as("T10 must not depend on the T05 DSL").isEmpty();
    }

    @Test
    void referencePackageStaysHostOnly() {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(REFERENCE_ROOT)) {
            Matcher matcher = IMPORT.matcher(read(file));
            while (matcher.find()) {
                String imported = matcher.group(2);
                if (REFERENCE_ALLOWED_IMPORTS.stream().noneMatch(imported::startsWith)) {
                    violations.add(file + " -> " + imported);
                }
            }
        }
        assertThat(violations).as("the admitted reference core must stay host-only").isEmpty();
    }

    private static List<Path> javaFiles(Path root) {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(path -> path.toString().endsWith(".java")).forEach(files::add);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return files;
    }

    private static List<Path> t10Files() {
        List<Path> files = new ArrayList<>();
        for (Path root : T10_ROOTS) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(path -> path.toString().endsWith(".java")).forEach(files::add);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
        return files;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}