package com.company.campaign.builder.campaignagent;

import com.company.campaign.builder.assembly.CampaignSkillIds;
import com.company.campaign.builder.assembly.CampaignToolIds;
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
 * Requirement 12: the core Campaign Agent package is framework-independent and cannot reach lifecycle, Apply,
 * Publish, Runtime, Provider, SQL, JDBC, JPA, Flyway or controller code, and its routing vocabulary cannot name
 * a Tool, a Tool argument or a host-only action.
 *
 * <p>The scan has two scopes. The <em>core</em> scope is the package itself, without the {@code http}
 * subpackage. The <em>wire</em> scope is that {@code http} subpackage: it is the only place a Spring binding
 * may exist, it exists only because a host explicitly supplies a turn service, and it is asserted to contain
 * nothing but the bounded HTTP adapter.
 */
class CampaignAgentBoundaryTest {

    private static final Path CORE_ROOT =
            Path.of("src", "main", "java", "com", "company", "campaign", "builder", "campaignagent");
    private static final Path WIRE_ROOT = CORE_ROOT.resolve("http");

    private static final List<String> ALLOWED_IMPORTS = List.of(
            "java.", "com.company.campaign.", "com.fasterxml.jackson.");

    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "org.springframework.", "jakarta.", "javax.", "java.sql", "javax.sql", "org.hibernate",
            "org.springframework.data", "com.openai", "dev.langchain4j", "java.net", "java.nio.file",
            "java.time.Clock", "javax.script", "jdk.internal", "org.junit",
            "com.company.campaign.lifecycle", "com.company.campaign.fullpublish", "com.company.campaign.runtime",
            "com.company.campaign.action", "com.company.campaign.preview", "com.company.campaign.metadata",
            "com.company.campaign.query", "com.company.campaign.CampaignApplication");

    private static final List<String> FORBIDDEN_CODE = List.of(
            "CampaignCompiler", "CampaignPlanStore", "InMemoryCampaignPlanStore", "compileAndStore",
            "LifecycleStore", "PublishPreparation", "new ChangeSet", "ResolvedCampaignManifest",
            "Clock.system", "Instant.now", "LocalDate.now", "LocalDateTime.now", "OffsetDateTime.now",
            "Math.random", "UUID.randomUUID", "ThreadLocal", "System.getenv", "System.getProperty",
            "System.currentTimeMillis", "System.out", "System.err", "Class.forName", "getDeclaredMethod",
            "getDeclaredField", "setAccessible", "Method.invoke", "ScriptEngine", "HttpClient", "WebClient",
            "ProcessBuilder", "DriverManager", "DataSource", "JdbcTemplate", "EntityManager", "SqlSession",
            "new Random(", "jdbc:", "Files.write");

    private static final Pattern IMPORT =
            Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([A-Za-z0-9_.]+)\\s*;");

    @Test
    void theCoreAgentPackageDependsOnNothingOutsideTheDeterministicBoundary() {
        List<String> violations = new ArrayList<>();
        for (Path file : coreFiles()) {
            Matcher matcher = IMPORT.matcher(read(file));
            while (matcher.find()) {
                String imported = matcher.group(2);
                if (ALLOWED_IMPORTS.stream().noneMatch(imported::startsWith)) {
                    violations.add(file + " -> " + imported);
                }
                for (String forbidden : FORBIDDEN_IMPORTS) {
                    if (imported.startsWith(forbidden)) {
                        violations.add(file + " -> " + imported);
                    }
                }
            }
        }
        assertThat(violations).as("imports outside the Campaign Agent boundary").isEmpty();
    }

    @Test
    void theCoreAgentPackageCarriesNoLifecycleApplyPublishRuntimeProviderStorageOrClockCode() {
        List<String> violations = new ArrayList<>();
        for (Path file : coreFiles()) {
            String source = read(file);
            for (String forbidden : FORBIDDEN_CODE) {
                if (source.contains(forbidden)) {
                    violations.add(file.getFileName() + " -> " + forbidden);
                }
            }
        }
        assertThat(violations)
                .as("the Campaign Agent must route, assemble and project, never Apply, Publish, Save or reach a Provider")
                .isEmpty();
    }

    @Test
    void theCoreAgentPackageExistsWithoutAControllerStoreEntityOrSecondCompiler() {
        List<String> names = fileNames(coreFiles());
        assertThat(names).contains(
                "CampaignConversationAgent.java", "CampaignAgentSession.java", "CampaignAgentPhase.java",
                "CampaignRouteKind.java", "CampaignRoutingModelPort.java", "CampaignRoutingDecision.java",
                "CampaignRoutingDecisionDecoder.java", "CampaignRoutingRequest.java", "CampaignSkillRouter.java",
                "CampaignSkillCommand.java", "CampaignSkillOutcome.java", "CampaignSkillRunner.java",
                "CampaignConversationProjection.java", "CampaignAgentTurnResult.java", "CampaignAgentException.java");
        assertThat(names).noneMatch(name -> name.endsWith("Repository.java") || name.endsWith("Entity.java")
                || name.endsWith("Controller.java") || name.endsWith("Service.java") || name.endsWith("Dao.java")
                || name.endsWith("Table.java") || name.endsWith("Schema.java") || name.contains("Draft"));
    }

    @Test
    void theHttpAdapterIsTheOnlySpringBindingAndExistsOnlyBehindAnExplicitHostService() {
        List<String> names = fileNames(wireFiles());
        assertThat(names).containsExactlyInAnyOrder(
                "CampaignAgentConversationCodec.java", "CampaignAgentConversationController.java",
                "CampaignAgentConversationEnvelope.java", "CampaignAgentConversationHttpResponse.java",
                "CampaignAgentConversationService.java",
                "CampaignAgentConversationHistoryController.java",
                "CampaignAgentConversationHistoryListResponse.java",
                "CampaignAgentConversationHistoryService.java",
                "CampaignAgentHistoryEnvelope.java",
                "CampaignAgentHttpErrorCodes.java", "CampaignAgentHttpResponses.java",
                "CampaignAgentTurnCodec.java", "CampaignAgentTurnController.java",
                "CampaignAgentTurnEnvelope.java", "CampaignAgentTurnHttpRequest.java",
                "CampaignAgentTurnHttpResponse.java", "CampaignAgentTurnService.java",
                "CampaignPlanCompileService.java", "CampaignPlanHttpController.java",
                "CampaignPlanHttpEnvelope.java", "CampaignPlanHttpErrorCodes.java",
                "CampaignPlanHttpException.java", "CampaignPlanHttpResponses.java",
                "CampaignPlanSummaryResponse.java");
        String controller = read(WIRE_ROOT.resolve("CampaignAgentTurnController.java"));
        assertThat(controller)
                .contains("@ConditionalOnBean(CampaignAgentTurnService.class)")
                .contains("/api/v1/campaign-agent")
                .doesNotContain("CampaignConversationAgent")
                .doesNotContain("CampaignRoutingModelPort")
                .doesNotContain("CampaignAssemblyWorkspace");
        String conversationController = read(WIRE_ROOT.resolve("CampaignAgentConversationController.java"));
        assertThat(conversationController)
                .contains("@ConditionalOnBean(CampaignAgentConversationService.class)")
                .contains("/api/v1/campaign-agent")
                .doesNotContain("CampaignConversationAgent")
                .doesNotContain("CampaignRoutingModelPort")
                .doesNotContain("CampaignAssemblyWorkspace");
        // The read-only history route is registered only when a host supplies the history service, and like the
        // create and turn routes it never reaches the agent, the routing model or the assembly workspace directly.
        String historyController = read(WIRE_ROOT.resolve("CampaignAgentConversationHistoryController.java"));
        assertThat(historyController)
                .contains("@ConditionalOnBean(CampaignAgentConversationHistoryService.class)")
                .contains("/api/v1/campaign-agent/history")
                .doesNotContain("CampaignConversationAgent")
                .doesNotContain("CampaignRoutingModelPort")
                .doesNotContain("CampaignAssemblyWorkspace");
        // The plan-compile route is registered only when a host supplies the compile service, and like the
        // create and turn routes it never reaches the agent, the routing model or the assembly workspace directly.
        String planController = read(WIRE_ROOT.resolve("CampaignPlanHttpController.java"));
        assertThat(planController)
                .contains("@ConditionalOnBean(CampaignPlanCompileService.class)")
                .contains("/api/v1/campaign-plans")
                .doesNotContain("CampaignConversationAgent")
                .doesNotContain("CampaignRoutingModelPort")
                .doesNotContain("CampaignAssemblyWorkspace");
        // No class in this build implements the service, so the application starts with no conversation route.
        List<String> implementations = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(CORE_ROOT)) {
            for (Path file : walk.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (read(file).contains("implements CampaignAgentTurnService")) {
                    implementations.add(file.toString());
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        assertThat(implementations).as("a default conversation route must not ship").isEmpty();
    }

    @Test
    void theSkillRouterIsClosedAndCannotBeExtendedOrReplacedDynamically() {
        String router = read(CORE_ROOT.resolve("CampaignSkillRouter.java"));
        assertThat(router)
                .contains("private final Map<String, CampaignSkillRunner> runners")
                .contains("CampaignSkillIds.SEGMENT_RULE_BASED")
                .contains("CampaignSkillIds.MESSAGE_TEMPLATE")
                .contains("CampaignSkillIds.PROMOTION_REFERENCE")
                .contains("CampaignSkillIds.JOURNEY_FLOW")
                .contains("CampaignSkillIds.SCHEDULE_BASIC")
                .contains("CampaignSkillIds.GOVERNANCE_LIMIT")
                .doesNotContain("Class.forName")
                .doesNotContain("getDeclaredMethod")
                .doesNotContain("ServiceLoader");
        assertThat(router).doesNotContain("public Map<String, CampaignSkillRunner>");
        assertThat(router).doesNotContain("registerRunner").doesNotContain("replaceRunner");
    }

    @Test
    void theRoutingVocabularyCannotNameAToolAToolArgumentOrAProofOperation() {
        String decision = read(CORE_ROOT.resolve("CampaignRoutingDecision.java"));
        assertThat(decision)
                .contains("CampaignRouteKind routeKind")
                .contains("List<String> skillIds")
                .doesNotContain("String toolId")
                .doesNotContain("ToolResultEnvelope")
                .doesNotContain("CapabilityPayload")
                .doesNotContain("CampaignProposalPatch")
                .doesNotContain("ChangeSet");

        String vocabulary = read(CORE_ROOT.resolve("CampaignRoutingVocabulary.java"));
        assertThat(vocabulary)
                .contains("CampaignToolIds.attemptsForbiddenCapability")
                .contains("CampaignSkillIds.isKnown");

        assertThat(CampaignSkillIds.ordered()).hasSize(6);
        assertThat(CampaignToolIds.ordered()).isNotEmpty();
    }

    @Test
    void theAgentNeverCompilesPlansSavesOrReachesALifecyclePort() {
        String agent = read(CORE_ROOT.resolve("CampaignConversationAgent.java"));
        assertThat(agent)
                .contains("CampaignAssemblyWorkspace")
                .contains("CampaignRoutingDecisionDecoder")
                .doesNotContain("CampaignCompiler")
                .doesNotContain("compileAndStore")
                .doesNotContain("CampaignPlanStore")
                .doesNotContain("LifecycleStore")
                .doesNotContain("PublishPreparation");
    }

    // ------------------------------------------------------------------ scanning helpers

    private static List<Path> coreFiles() {
        try (Stream<Path> walk = Files.walk(CORE_ROOT)) {
            return walk.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.startsWith(WIRE_ROOT))
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static List<Path> wireFiles() {
        try (Stream<Path> walk = Files.walk(WIRE_ROOT)) {
            return walk.filter(path -> path.toString().endsWith(".java")).toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static List<String> fileNames(List<Path> files) {
        return files.stream().map(path -> path.getFileName().toString()).toList();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
