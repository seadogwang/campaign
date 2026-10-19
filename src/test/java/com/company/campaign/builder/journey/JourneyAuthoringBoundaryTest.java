package com.company.campaign.builder.journey;

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
 * Requirement 15: the host-owned Journey authoring adapter is pure and reaches no lifecycle, runtime,
 * Provider, storage, scheduling, model or transport dependency, and no topology edge ever enters a Flow node
 * payload.
 */
class JourneyAuthoringBoundaryTest {

    private static final Path JOURNEY_ROOT = Path.of(
            "src", "main", "java", "com", "company", "campaign", "builder", "journey");

    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "org.springframework.",
            "jakarta.persistence",
            "javax.persistence",
            "java.sql",
            "javax.sql",
            "java.net",
            "java.util.Random",
            "java.util.UUID",
            "javax.script",
            "com.company.campaign.lifecycle",
            "com.company.campaign.runtime",
            "com.company.campaign.fullpublish",
            "com.company.campaign.action",
            "com.company.campaign.query",
            "com.company.campaign.preview",
            "com.company.campaign.metadata",
            "com.company.campaign.conversation",
            "com.company.campaign.evaluation",
            "com.company.campaign.dev",
            // The accepted T02 trusted execution context is an allowed compile input; authorization, audit and
            // data-projection reaches are not.
            "com.company.campaign.security.authorization",
            "com.company.campaign.security.audit",
            "com.company.campaign.security.data",
            "com.company.campaign.localhealth");

    private static final List<String> FORBIDDEN_TEXT = List.of(
            "Instant.now", "LocalDate.now", "LocalDateTime.now", "OffsetDateTime.now", "ZonedDateTime.now",
            "Clock.system", "ZoneId.systemDefault", "Locale.getDefault", "UUID.randomUUID", "Math.random",
            "new Random(", "ThreadLocal", "System.getenv", "System.getProperty", "System.currentTimeMillis",
            "System.nanoTime", "System.out", "System.err", "Class.forName", "ProcessBuilder",
            "Runtime.getRuntime", "DriverManager", "jdbc:", "ScriptEngine", "Thread.sleep", "Files.write",
            "readValue", "treeToValue", "valueToTree");

    private static final Pattern IMPORT =
            Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([A-Za-z0-9_.]+)\\s*;");

    @Test
    void theAuthoringAdapterReachesNoLifecycleRuntimeProviderStorageOrModelDependency() {
        List<String> violations = new ArrayList<>();
        for (Path file : sources()) {
            Matcher matcher = IMPORT.matcher(read(file));
            while (matcher.find()) {
                String imported = matcher.group(2);
                for (String forbidden : FORBIDDEN_IMPORTS) {
                    if (imported.startsWith(forbidden)) {
                        violations.add(file.getFileName() + " -> " + imported);
                    }
                }
            }
            String source = read(file);
            for (String forbidden : FORBIDDEN_TEXT) {
                if (source.contains(forbidden)) {
                    violations.add(file.getFileName() + " -> " + forbidden);
                }
            }
        }
        assertThat(violations).as("the Journey authoring adapter must stay pure").isEmpty();
    }

    @Test
    void theAdapterUsesOnlyTheAcceptedT19AndT20Boundaries() {
        // The adapter is the one place the orchestration layer reaches T19/T20, so its imports are exactly the
        // accepted capability packs, the accepted compiler/frozen inputs and the accepted Journey contracts.
        List<String> outside = new ArrayList<>();
        for (Path file : sources()) {
            Matcher matcher = IMPORT.matcher(read(file));
            while (matcher.find()) {
                String imported = matcher.group(2).replaceAll("static ", "");
                boolean allowed = imported.startsWith("java.")
                        || imported.startsWith("com.company.campaign.builder.capability.")
                        || imported.startsWith("com.company.campaign.builder.compiler.")
                        || imported.startsWith("com.company.campaign.journey.")
                        || imported.startsWith("com.company.campaign.contracts.")
                        || imported.startsWith("com.company.campaign.security.context.")
                        || imported.startsWith("com.company.campaign.shared.");
                if (!allowed) {
                    outside.add(file.getFileName() + " -> " + imported);
                }
            }
        }
        assertThat(outside).as("imports outside the accepted T19/T20 boundary").isEmpty();
    }

    @Test
    void noTopologyEdgeEverEntersAFlowNodePayload() {
        // The T20 topology is the only owner of edge data, so the authoring declarations keep edges in their
        // own typed values and the Flow payload contracts declare no edge, successor or branch field.
        for (String forbidden : List.of("edge", "successor", "branch", "from", "to")) {
            for (var pack : com.company.campaign.builder.capability.flow.FlowCapabilityPacks.all()) {
                assertThat(pack.packMetadata().payloadContract().declares(forbidden))
                        .as(pack.capabilityCode() + " must not declare " + forbidden)
                        .isFalse();
            }
        }
        assertThat(read(JOURNEY_ROOT.resolve("JourneyGraphDraft.java")))
                .contains("List<JourneyEdgeDeclaration> edges");
        assertThat(read(JOURNEY_ROOT.resolve("JourneyGraphAdapter.java")))
                .contains("JourneyTopology.of");
    }

    private static List<Path> sources() {
        try (Stream<Path> walk = Files.walk(JOURNEY_ROOT)) {
            return walk.filter(path -> path.toString().endsWith(".java")).sorted().toList();
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