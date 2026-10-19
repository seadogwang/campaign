package com.company.campaign.dev.campaignagent;

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
 * T33 requirement 8: the real development Segment source stays free of every test dependency and of every
 * shortcut the task forbids — no phrase interpreter, no static pending-provider store, no synthetic Preview
 * backend and no directly constructed Agent artifact.
 */
class DevelopmentSegmentAgentArchitectureTest {

    private static final Path MAIN = Path.of("src", "main", "java");
    private static final Path DEVELOPMENT_HOST =
            MAIN.resolve(Path.of("com", "company", "campaign", "dev", "campaignagent"));
    private static final Path PROVIDER =
            DEVELOPMENT_HOST.resolve("DevelopmentSegmentAgentSourceProvider.java");

    private static final List<String> FORBIDDEN_HOST_IMPORTS = List.of(
            "org.junit.",
            "org.assertj.",
            "org.mockito.",
            ".testsupport.",
            "com.company.campaign.dev.campaignagent.testsupport.");

    private static final Pattern IMPORT =
            Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([A-Za-z0-9_.]+)\\s*;");

    @Test
    void theDevelopmentHostImportsNoTestDependency() {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(DEVELOPMENT_HOST)) {
            Matcher matcher = IMPORT.matcher(read(file));
            while (matcher.find()) {
                String imported = matcher.group(2);
                for (String forbidden : FORBIDDEN_HOST_IMPORTS) {
                    if (imported.contains(forbidden)) {
                        violations.add(file + " -> " + imported);
                    }
                }
            }
        }
        assertThat(violations).as("the development host stays free of test dependencies").isEmpty();
    }

    @Test
    void theRealProviderReadsNoClockRandomSourceThreadOrStaticStore() {
        String source = read(PROVIDER);

        assertThat(source)
                .doesNotContain("Instant.now(")
                .doesNotContain("System.currentTimeMillis(")
                .doesNotContain("System.nanoTime(")
                .doesNotContain("new Random(")
                .doesNotContain("Math.random(")
                .doesNotContain("Thread")
                .doesNotContain("Executors")
                .doesNotContain("ThreadLocal")
                .doesNotContain("ConcurrentHashMap")
                .doesNotContain("WeakHashMap");
        assertThat(Pattern.compile("(?m)^\\s*(private|public|protected)\\s+static\\s+(final\\s+)?Map<")
                .matcher(source).find()).isFalse();
    }

    @Test
    void theRealProviderNeverConstructsAnAgentArtifactItself() {
        String source = read(PROVIDER);

        // The one thing the provider may assemble is the accepted typed proof from the artifacts its run
        // observed; it must never build a DSL, a validation result, a plan, a Preview response or an Agent
        // result of its own.
        assertThat(source)
                .doesNotContain("new SegmentDsl(")
                .doesNotContain("SegmentDsl.publish(")
                .doesNotContain("new SemanticQueryPlan(")
                .doesNotContain("new PreviewResponse(")
                .doesNotContain("new SegmentValidationResult(")
                .doesNotContain("SegmentAgentResult.publish(")
                .doesNotContain("new SegmentAgentResult(")
                .doesNotContain("new SegmentValidationOutcome(")
                .doesNotContain("parseUserIntent")
                .doesNotContain("equalsIgnoreCase");
    }

    @Test
    void noMainSourceClassIsASyntheticSegmentPreviewBackend() {
        List<String> implementations = new ArrayList<>();
        for (Path file : javaFiles(MAIN)) {
            String source = read(file);
            if (source.contains("implements SegmentPreviewPort")) {
                implementations.add(file.getFileName().toString());
            }
        }
        assertThat(implementations)
                .as("only the accepted host Preview adapter may implement the typed Preview port")
                .containsExactly("HostSafePreviewPort.java");
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