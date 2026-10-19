package com.company.campaign.readiness;

import com.company.campaign.runtime.RuntimeVersions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T25 architecture and isolation regression tests.
 *
 * <p>They prove three separate boundaries:
 *
 * <ul>
 *   <li>production Runtime and Action execution reaches no Builder application service, no model client, no
 *       prompt/Skill execution and no AI evaluation runner, so an already-persisted Runtime scenario cannot be
 *       stopped by a Builder or LLM outage — there is nothing for such an outage to stop;</li>
 *   <li>the readiness slice is not reachable from a model-callable Tool, an Agent envelope or the one exposed
 *       public API slice, and it exposes no HTTP controller, so no untrusted browser or model payload can
 *       assert that evidence is trusted;</li>
 *   <li>Runtime and Action do not depend on the readiness slice in either direction.</li>
 * </ul>
 */
class ReadinessIsolationTest {

    private static final Path REPOSITORY_ROOT = Path.of("").toAbsolutePath();
    private static final Path MAIN_SOURCES = REPOSITORY_ROOT.resolve("src/main/java/com/company/campaign");

    /** Import prefixes production Runtime and Action execution must never reach for. */
    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "com.company.campaign.builder.agent.",
            "com.company.campaign.builder.skill.",
            "com.company.campaign.builder.tool.",
            "com.company.campaign.builder.proposal.",
            "com.company.campaign.builder.refine.",
            "com.company.campaign.builder.segment.",
            "com.company.campaign.builder.authoring.",
            "com.company.campaign.evaluation.",
            "com.company.campaign.metadata.",
            "com.company.campaign.preview.",
            "com.company.campaign.query.",
            "com.company.campaign.journey.simulation.",
            "com.company.campaign.readiness.",
            "jakarta.persistence.",
            "javax.persistence.",
            "org.flywaydb.",
            "org.hibernate.",
            "org.springframework.web.",
            "org.springframework.stereotype.");

    /** Identifiers that would mean an active Builder/LLM service, Prompt or Skill execution was reachable. */
    private static final List<String> FORBIDDEN_IDENTIFIERS = List.of(
            "CampaignCompiler",
            "SegmentAuthoringIntegration",
            "ModelClient",
            "LlmClient",
            "PromptRunner",
            "PromptTemplate",
            "SkillRunner",
            "ToolInvoker",
            "AgentRunner",
            "AiEvaluationRunner",
            "EvaluationRunner");

    /** Packages that must never be able to reach the readiness slice. */
    private static final List<String> TOOL_AND_COMMAND_PACKAGES = List.of(
            "builder/tool", "builder/agent", "builder/proposal", "builder/refine", "preview");

    @Test
    void runtimeAndActionReachNoBuilderApplicationServiceModelClientOrAiEvaluationRunner() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path source : sourcesUnder("runtime", "action")) {
            for (String line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("import ")) {
                    continue;
                }
                for (String forbidden : FORBIDDEN_IMPORTS) {
                    if (trimmed.contains(forbidden)) {
                        violations.add(source.getFileName() + ": " + trimmed);
                    }
                }
            }
        }

        assertThat(violations).as("forbidden Runtime/Action imports").isEmpty();
    }

    @Test
    void runtimeAndActionCarryNoModelClientPromptSkillOrAgentRuntimeIdentifier() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path source : sourcesUnder("runtime", "action")) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            for (String forbidden : FORBIDDEN_IDENTIFIERS) {
                if (text.contains(forbidden)) {
                    violations.add(source.getFileName() + ": " + forbidden);
                }
            }
        }

        assertThat(violations).as("forbidden Runtime/Action builder or LLM identifiers").isEmpty();
    }

    @Test
    void anAlreadyPersistedRuntimeScenarioDependsOnlyOnImmutablePublishedManifests() throws IOException {
        List<String> fullpublishImports = new ArrayList<>();
        for (Path source : sourcesUnder("runtime")) {
            for (String line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("import com.company.campaign.fullpublish.")) {
                    fullpublishImports.add(source.getFileName() + ": " + trimmed);
                }
            }
        }

        assertThat(fullpublishImports).isNotEmpty();
        assertThat(fullpublishImports).allSatisfy(entry -> assertThat(entry).containsAnyOf(
                "ScheduleRegistrationOutboxEvent",
                "ScheduledOccurrenceRecord",
                "OccurrenceDecision",
                "FullPublishedDefinition",
                "FullPublishVersions",
                "RuntimeWorkDeliveryPort"));
    }

    @Test
    void runtimeAndActionDoNotDependOnTheReadinessSlice() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path source : sourcesUnder("runtime", "action")) {
            if (Files.readString(source, StandardCharsets.UTF_8)
                    .contains("com.company.campaign.readiness")) {
                violations.add(source.getFileName().toString());
            }
        }

        assertThat(violations).as("Runtime/Action reaching readiness").isEmpty();
    }

    @Test
    void theReadinessSliceIsNotReachableFromAModelCallableToolAgentOrPublicCommand() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String packageReference : TOOL_AND_COMMAND_PACKAGES) {
            Path directory = MAIN_SOURCES.resolve(packageReference);
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(directory)) {
                for (Path source : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                    if (Files.readString(source, StandardCharsets.UTF_8)
                            .contains("com.company.campaign.readiness")) {
                        violations.add(source.getFileName().toString());
                    }
                }
            }
        }

        assertThat(violations).as("readiness reachable from a Tool, Agent or public command").isEmpty();
    }

    @Test
    void theReadinessSliceAddsNoControllerAndNoPublicEndpoint() throws IOException {
        List<Path> readinessSources = sourcesUnder("readiness");

        assertThat(readinessSources).isNotEmpty();
        assertThat(readinessSources).noneSatisfy(source ->
                assertThat(source.getFileName().toString()).endsWith("Controller.java"));
        for (Path source : readinessSources) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            assertThat(text)
                    .as(source.getFileName().toString())
                    .doesNotContain("@RestController")
                    .doesNotContain("@Controller")
                    .doesNotContain("@RequestMapping")
                    .doesNotContain("@PostMapping")
                    .doesNotContain("@GetMapping")
                    .doesNotContain("HttpServletRequest");
        }
    }

    @Test
    void runtimeRecordsAreNeverPresentedAsProductionGateEvidence() {
        assertThat(RuntimeVersions.PRODUCTION_GATE_EVIDENCE).isFalse();
        assertThat(ReadinessVersions.PRODUCTION_GATE_EVIDENCE).isFalse();
    }

    private static List<Path> sourcesUnder(String... packageReferences) throws IOException {
        List<Path> sources = new ArrayList<>();
        for (String packageReference : packageReferences) {
            Path directory = MAIN_SOURCES.resolve(packageReference);
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(directory)) {
                sources.addAll(paths
                        .filter(path -> path.toString().endsWith(".java"))
                        .sorted()
                        .toList());
            }
        }
        return sources;
    }
}