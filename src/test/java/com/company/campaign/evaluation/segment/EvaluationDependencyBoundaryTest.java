package com.company.campaign.evaluation.segment;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the T03 boundaries: the evaluation package stays framework-independent, and the evaluation asset
 * tree stays JSON plus documentation.
 *
 * <p>The import check is an allowlist rather than a denylist, so a package added later has to be justified
 * here. That is what keeps persistence, web, Spring Security, a Provider SDK or a second serializer from
 * appearing in the package whose whole purpose is to be deterministic and replayable.
 */
class EvaluationDependencyBoundaryTest {

    private static final Path SOURCE_ROOT =
            Path.of("src", "main", "java", "com", "company", "campaign", "evaluation");

    private static final Path ASSET_ROOT = Path.of("evaluation", "segment");

    private static final List<String> ALLOWED_IMPORTS = List.of(
            "java.",
            "com.company.campaign.",
            "com.fasterxml.jackson.");

    /** T03 must not reach into the security layer: trusted context arrives as a referenced fixture. */
    private static final List<String> FORBIDDEN_IN_EVALUATION = List.of(
            "com.company.campaign.security",
            "org.springframework.",
            "jakarta.persistence",
            "javax.persistence",
            "java.sql",
            "javax.sql",
            "org.springframework.data",
            "org.hibernate",
            "org.springframework.ai",
            "com.openai",
            "dev.langchain4j");

    /**
     * The one T13 subpackage that may name the security layer.
     *
     * <p>T03 and T06 stay independent of security on purpose: they load, classify and validate reviewed
     * assets and never execute a pipeline. T13 does execute the accepted pipeline, and the genuine T11 and
     * T12 adapters it invokes are parameterised by a host-owned trusted context, so the runner must be able
     * to name that context and its authorization service. Everything else stays forbidden there too, so the
     * carve-out widens exactly one boundary and no more.
     */
    private static final String RUNNER_PACKAGE = "runner";

    /** Even the runner may not reach these: an evaluation must stay deterministic and offline. */
    private static final List<String> FORBIDDEN_EVERYWHERE = List.of(
            "org.springframework.",
            "jakarta.persistence",
            "javax.persistence",
            "java.sql",
            "javax.sql",
            "java.net",
            "javax.script",
            "org.springframework.data",
            "org.hibernate",
            "org.flywaydb",
            "org.springframework.ai",
            "com.openai",
            "dev.langchain4j",
            "com.company.campaign.repository",
            "com.company.campaign.runtime",
            "com.company.campaign.dev");

    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([A-Za-z0-9_.]+)\\s*;");

    @Test
    void evaluationSourcesStayFrameworkIndependent() {
        List<Path> files = javaFiles(SOURCE_ROOT);
        assertThat(files).as("evaluation source files").hasSizeGreaterThan(15);

        List<String> violations = new ArrayList<>();
        for (Path file : files) {
            String source = read(file);
            Matcher matcher = IMPORT.matcher(source);
            while (matcher.find()) {
                String imported = matcher.group(2);
                if (ALLOWED_IMPORTS.stream().noneMatch(imported::startsWith)) {
                    violations.add(file + " -> " + imported);
                }
            }
        }

        assertThat(violations).as("imports outside the evaluation boundary").isEmpty();
    }

    @Test
    void evaluationSourcesDoNotReachSecurityPersistenceOrProviders() {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(SOURCE_ROOT)) {
            boolean isRunner = file.toString().replace('\\', '/').contains("/" + RUNNER_PACKAGE + "/");
            String source = read(file);
            Matcher matcher = IMPORT.matcher(source);
            while (matcher.find()) {
                String imported = matcher.group(2);
                for (String forbidden : FORBIDDEN_EVERYWHERE) {
                    if (imported.startsWith(forbidden)) {
                        violations.add(file + " -> " + imported);
                    }
                }
                if (!isRunner) {
                    for (String forbidden : FORBIDDEN_IN_EVALUATION) {
                        if (imported.startsWith(forbidden)) {
                            violations.add(file + " -> " + imported);
                        }
                    }
                }
            }
        }

        assertThat(violations).as("forbidden imports in evaluation").isEmpty();
    }

    @Test
    void onlyTheEvaluationRunnerNamesTheSecurityLayer() {
        List<Path> reachingSecurity = new ArrayList<>();
        for (Path file : javaFiles(SOURCE_ROOT)) {
            if (read(file).contains("com.company.campaign.security")) {
                reachingSecurity.add(file);
            }
        }

        assertThat(reachingSecurity).as("security boundary reached outside the T13 runner subpackage")
                .allSatisfy(file -> assertThat(file.toString().replace('\\', '/'))
                        .contains("/" + RUNNER_PACKAGE + "/"));
    }

    @Test
    void theEvaluationPackageReadsNoClockRandomDefaultZoneOrProviderSdk() {
        List<String> forbiddenText = List.of(
                "Instant.now", "LocalDate.now", "LocalDateTime.now", "OffsetDateTime.now", "ZonedDateTime.now",
                "Clock.system", "ZoneId.systemDefault", "Locale.getDefault", "UUID.randomUUID", "Math.random",
                "new Random(", "ThreadLocal", "System.getenv", "System.getProperty", "System.getProperties",
                "System.currentTimeMillis", "System.nanoTime", "System.out", "System.err", "Class.forName",
                "ProcessBuilder", "Runtime.getRuntime", "Thread.sleep", "Files.write", "java.net.",
                "HttpClient", "URLConnection", "DriverManager", "jdbc:");
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(SOURCE_ROOT)) {
            String source = read(file);
            for (String forbidden : forbiddenText) {
                if (source.contains(forbidden)) {
                    violations.add(file + " contains " + forbidden);
                }
            }
        }

        assertThat(violations).as("non-deterministic or out-of-scope constructs in evaluation").isEmpty();
    }

    @Test
    void evaluationAssetsAreJsonOrDocumentationOnly() {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(ASSET_ROOT)) {
            files = walk.filter(Files::isRegularFile).toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }

        assertThat(files).as("evaluation asset files").isNotEmpty();
        assertThat(files).allSatisfy(file -> assertThat(file.getFileName().toString().toLowerCase(Locale.ROOT))
                .as("asset %s must be JSON or Markdown; YAML and a second serializer are out of scope", file)
                .matches(".*\\.(json|md)$"));
    }

    @Test
    void theEvaluationTreeHasTheFiveRequiredDirectories() {
        for (String directory : List.of("cases", "metadata", "fixtures", "expected", "reports")) {
            assertThat(ASSET_ROOT.resolve(directory)).as("evaluation/segment/%s", directory).isDirectory();
        }
    }

    @Test
    void generatedReportsAreSeparatedFromReviewedAssets() {
        assertThat(read(Path.of(".gitignore")))
                .contains("evaluation/segment/reports/generated/");
        assertThat(ASSET_ROOT.resolve("reports").resolve("generated")).doesNotExist();
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