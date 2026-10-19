package com.company.campaign.builder;

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
 * Requirement 8: the Campaign builder orchestration layer reaches no lifecycle, runtime, Provider, storage or
 * scheduling dependency, and no clock, random source or global state.
 *
 * <p>The orchestration layer is the T27 {@code builder/assembly} tree and the T28 {@code builder/campaignagent}
 * tree: they compose accepted boundaries, they never implement a publish, an apply, a runtime materialization,
 * a Provider call or a persistence concern. A future change that pulled any of those into the orchestration
 * layer would be caught here rather than silently widening the boundary.
 */
class CampaignOrchestrationDependencyBoundaryTest {

    private static final Path BUILDER_ROOT =
            Path.of("src", "main", "java", "com", "company", "campaign", "builder");
    private static final List<Path> ORCHESTRATION_ROOTS = List.of(
            BUILDER_ROOT.resolve("assembly"), BUILDER_ROOT.resolve("campaignagent"));

    /**
     * The T28 HTTP transport adapter is a Web edge, not orchestration: it legitimately binds to Spring MVC and
     * turns a bounded typed envelope into a response. It is excluded from the import rule only; the
     * determinism rule still covers it.
     */
    private static final Path WEB_TRANSPORT = BUILDER_ROOT.resolve("campaignagent").resolve("http");

    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "org.springframework.",
            "jakarta.persistence",
            "javax.persistence",
            "java.sql",
            "javax.sql",
            "javax.script",
            "java.net",
            "java.util.Random",
            "com.company.campaign.lifecycle",
            "com.company.campaign.runtime",
            "com.company.campaign.fullpublish",
            "com.company.campaign.action",
            "com.company.campaign.query",
            "com.company.campaign.preview",
            "com.company.campaign.metadata",
            "com.company.campaign.journey",
            "com.company.campaign.conversation",
            "com.company.campaign.evaluation",
            "com.company.campaign.dev",
            "com.company.campaign.localhealth",
            "com.company.campaign.CampaignApplication");

    private static final List<String> FORBIDDEN_TEXT = List.of(
            "Instant.now", "LocalDate.now", "LocalDateTime.now", "OffsetDateTime.now", "ZonedDateTime.now",
            "Clock.system", "ZoneId.systemDefault", "Locale.getDefault", "UUID.randomUUID", "Math.random",
            "new Random(", "ThreadLocal", "System.getenv", "System.getProperty", "System.currentTimeMillis",
            "System.nanoTime", "Class.forName", "ProcessBuilder", "Runtime.getRuntime", "DriverManager",
            "jdbc:", "ScriptEngine", "Thread.sleep");

    private static final Pattern IMPORT =
            Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([A-Za-z0-9_.]+)\\s*;");

    @Test
    void theOrchestrationLayerReachesNoLifecycleRuntimeProviderOrStorageDependency() {
        List<String> violations = new ArrayList<>();
        for (Path file : orchestrationFiles()) {
            if (file.startsWith(WEB_TRANSPORT)) {
                continue;
            }
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
        assertThat(violations).as("forbidden orchestration imports").isEmpty();
    }

    @Test
    void theOrchestrationLayerReadsNoClockRandomSourceOrGlobalState() {
        List<String> violations = new ArrayList<>();
        for (Path file : orchestrationFiles()) {
            String source = read(file);
            for (String forbidden : FORBIDDEN_TEXT) {
                if (source.contains(forbidden)) {
                    violations.add(file + " -> " + forbidden);
                }
            }
        }
        assertThat(violations).as("forbidden orchestration text").isEmpty();
    }

    @Test
    void theOrchestrationLayerAdmitsOnlyTypedRequestsAndPublishesOnlySafePreviewCards() {
        // Two structural facts of the closed boundary: a Tool request is a closed typed union, and the only
        // projections the orchestration layer emits are the bounded non-disclosing cards.
        assertThat(read(BUILDER_ROOT.resolve("assembly").resolve("CampaignToolRequest.java")))
                .contains("public sealed interface CampaignToolRequest")
                .doesNotContain("Map<String, Object> execute");
        assertThat(read(BUILDER_ROOT.resolve("assembly").resolve("CampaignToolCatalog.java")))
                .contains("private static CampaignToolCatalog build()")
                .doesNotContain("public void register")
                .doesNotContain("register(");
    }

    private static List<Path> orchestrationFiles() {
        List<Path> files = new ArrayList<>();
        for (Path root : ORCHESTRATION_ROOTS) {
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