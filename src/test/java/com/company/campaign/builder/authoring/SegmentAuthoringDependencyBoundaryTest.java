package com.company.campaign.builder.authoring;

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
 * Requirement 32: the T16 production Segment authoring integration depends only on accepted deterministic
 * boundaries and immutable host inputs. It reaches no repository, database, Web layer, model or provider
 * client, network, mutable registry or clock, and it never gains a dependency on a Segment Draft store or a
 * second compiler. The generic T15 compiler boundary must stay free of concrete capability semantics and must
 * not depend on {@code builder.authoring}.
 */
class SegmentAuthoringDependencyBoundaryTest {

    private static final Path BUILDER_ROOT =
            Path.of("src", "main", "java", "com", "company", "campaign", "builder");
    private static final Path AUTHORING_ROOT = BUILDER_ROOT.resolve("authoring");
    private static final Path COMPILER_ROOT = BUILDER_ROOT.resolve("compiler");
    private static final Path PROPOSAL_ROOT = BUILDER_ROOT.resolve("proposal");

    private static final List<String> ALLOWED_IMPORTS = List.of(
            "java.",
            "com.company.campaign.",
            "com.fasterxml.jackson.");

    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "org.springframework.",
            "jakarta.",
            "javax.",
            "java.sql",
            "javax.sql",
            "org.hibernate",
            "org.springframework.data",
            "org.springframework.ai",
            "com.openai",
            "dev.langchain4j",
            "java.net",
            "java.nio.file",
            "java.time.Clock",
            "javax.script",
            "jdk.internal",
            "com.company.campaign.metadata.repository",
            "com.company.campaign.metadata.infrastructure",
            "com.company.campaign.segment.repository",
            "com.company.campaign.runtime",
            "com.company.campaign.message",
            "com.company.campaign.reward",
            "com.company.campaign.schedule",
            "com.company.campaign.journey",
            "com.company.campaign.audience",
            "com.company.campaign.CampaignApplication");

    private static final List<String> FORBIDDEN_TEXT = List.of(
            "Instant.now", "LocalDate.now", "LocalDateTime.now", "OffsetDateTime.now", "ZonedDateTime.now",
            "Clock.system", "ZoneId.systemDefault", "Locale.getDefault", "UUID.randomUUID", "Math.random",
            "new Random(", "ThreadLocal", "System.getenv", "System.getProperty", "System.getProperties",
            "System.currentTimeMillis", "System.nanoTime", "System.out", "System.err",
            "Class.forName", "getDeclaredMethod", "getDeclaredField", "setAccessible", "Method.invoke",
            "ProcessBuilder", "Runtime.getRuntime", "DriverManager", "jdbc:", "DataSource", "ScriptEngine",
            "Thread.sleep", "FileWriter", "Files.write", "Files.read", "readValue", "treeToValue", "valueToTree");

    private static final List<String> CAPABILITY_CODES = List.of(
            "segment.rule-based", "message.template", "promotion.reference", "flow.wait", "flow.message",
            "flow.start", "flow.reward", "schedule.basic", "governance.limit");

    private static final Pattern IMPORT =
            Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([A-Za-z0-9_.]+)\\s*;");

    @Test
    void theAuthoringIntegrationItselfExistsWithoutADraftStoreOrSecondCompiler() {
        List<String> names = fileNames(AUTHORING_ROOT);
        assertThat(names).contains(
                "SegmentRuleBasedCapability.java", "SegmentAuthoringInputs.java", "SegmentAuthoringVersions.java",
                "SegmentAuthoringEnvelope.java", "SegmentAuthoringErrorCodes.java", "SegmentAuthoringException.java");
        // The shared integration, the three proof-bound sources and the narrow activation guard all exist.
        assertThat(names).contains(
                "SegmentAuthoringIntegration.java", "SegmentAuthoringProof.java", "SegmentAuthoringSource.java",
                "SegmentAuthoringRequest.java", "SegmentAuthoringResult.java", "SegmentAuthoringKind.java",
                "SegmentAuthoringReadiness.java", "SegmentActivationGuard.java");
        // No Segment Draft repository, entity, table or session truth is added anywhere in production.
        assertThat(names).noneMatch(name -> name.contains("Draft") && name.contains("Repository"));
        assertThat(names).noneMatch(name -> name.endsWith("Repository.java") || name.endsWith("Entity.java")
                || name.endsWith("Controller.java") || name.endsWith("Session.java") || name.endsWith("Table.java")
                || name.endsWith("Dao.java") || name.endsWith("Schema.java"));
        // No Save, Apply, Publish or Runtime behavior is implemented by this task.
        assertThat(names).noneMatch(name -> name.contains("Save") || name.contains("Apply")
                || name.contains("Publish") || name.contains("Runtime") || name.contains("Activation")
                        && name.contains("Service"));
        assertThat(fileNames(BUILDER_ROOT)).noneMatch(name -> name.contains("SegmentDraft"));
        assertThat(fileNames(BUILDER_ROOT)).noneMatch(name -> name.contains("VisualBuilder"));
        assertThat(fileNames(BUILDER_ROOT)).noneMatch(name -> name.contains("AiCompiler"));
    }

    @Test
    void authoringImportsStayInsideTheDeterministicBoundary() {
        List<String> violations = new ArrayList<>();
        for (Path file : authoringFiles()) {
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
        assertThat(violations).as("imports outside the T16 authoring boundary").isEmpty();
    }

    @Test
    void authoringContainsNoClockRandomGlobalContextReflectionOrDeserialization() {
        List<String> violations = new ArrayList<>();
        for (Path file : authoringFiles()) {
            String source = read(file);
            for (String forbidden : FORBIDDEN_TEXT) {
                if (source.contains(forbidden)) {
                    violations.add(file + " -> " + forbidden);
                }
            }
        }
        assertThat(violations).as("forbidden text in T16 authoring").isEmpty();
    }

    @Test
    void theGenericCompilerBoundaryKeepsNoCapabilitySemanticsAndDependsOnNoAuthoringCode() {
        List<String> violations = new ArrayList<>();
        List<Path> t15 = new ArrayList<>(filesUnder(PROPOSAL_ROOT));
        t15.addAll(filesUnder(COMPILER_ROOT));
        for (Path file : t15) {
            String source = read(file);
            for (String code : CAPABILITY_CODES) {
                if (source.contains(code)) {
                    violations.add(file + " -> " + code);
                }
            }
            Matcher matcher = IMPORT.matcher(source);
            while (matcher.find()) {
                if (matcher.group(2).startsWith("com.company.campaign.builder.authoring")) {
                    violations.add(file + " -> " + matcher.group(2));
                }
            }
        }
        assertThat(violations)
                .as("the generic T15 boundary must stay free of capability semantics and authoring code")
                .isEmpty();
    }

    @Test
    void theContractHoldsOnlyInjectedDeterministicBoundaries() {
        String contract = read(AUTHORING_ROOT.resolve("SegmentRuleBasedCapability.java"));
        assertThat(contract)
                .contains("implements CapabilityContract")
                .contains("SegmentValidator validator")
                .contains("SegmentQueryPlanner planner")
                .doesNotContain("Repository")
                .doesNotContain("HttpClient")
                .doesNotContain("WebClient")
                .doesNotContain("Clock")
                .doesNotContain("Random")
                .doesNotContain("readValue");
    }

    @Test
    void theSharedIntegrationDelegatesToTheOneAcceptedCompilerAndProducesNoSecondTruth() {
        String integration = read(AUTHORING_ROOT.resolve("SegmentAuthoringIntegration.java"));
        assertThat(integration)
                .as("the shared integration must invoke the one accepted T15 compiler")
                .contains("CampaignCompiler")
                .contains("compiler.compile(")
                .contains("SegmentAuthoringEnvelope.payloadOf")
                .as("no second Manifest, Plan, ChangeSet or diff producer may exist here")
                .doesNotContain("new CampaignPlan")
                .doesNotContain("CampaignPlan.create")
                .doesNotContain("ResolvedCampaignManifest")
                .doesNotContain("new ChangeSet")
                .doesNotContain("SemanticDiff")
                .as("no AI-specific or Visual-Builder-specific compiler path may exist")
                .doesNotContain("AiCompiler")
                .doesNotContain("VisualBuilderCompiler");

        List<String> compilers = new ArrayList<>();
        List<String> compilersSeen = new ArrayList<>();
        for (Path file : authoringFiles()) {
            String source = read(file);
            if (source.contains("CampaignCompiler")) {
                compilersSeen.add(file.getFileName().toString());
            }
            if (source.contains("new CampaignCompiler(")) {
                compilers.add(file.getFileName().toString());
            }
        }
        // Exactly one authoring file may name the compiler and no authoring file may construct one: the
        // facade consumes an injected compiler, so equivalence can never come from duplicated compilers.
        assertThat(compilersSeen).containsExactly("SegmentAuthoringIntegration.java");
        assertThat(compilers).isEmpty();
    }

    @Test
    void theAuthoringIntegrationCarriesNoLoyaltyBusinessLiteral() {
        List<String> violations = new ArrayList<>();
        for (Path file : authoringFiles()) {
            String source = read(file);
            for (String business : List.of(
                    "GOLD", "SILVER", "member.tier", "member.name", "member.balance", "order.net_paid_amount",
                    "member.orders", "CNY", "Shanghai", "net_paid", "loyalty", "Loyalty", "Natural Language",
                    "tenant-001", "program-001",
                    "flow.wait", "schedule.basic", "governance.limit", "promotion.reference", "message.template")) {
                if (source.contains(business)) {
                    violations.add(file + " -> " + business);
                }
            }
        }
        assertThat(violations)
                .as("Segment semantics come from the typed DSL and Metadata, never from T16 code")
                .isEmpty();
    }

    @Test
    void theActivationGuardIsANarrowPortAndImplementsNoApplyPublishSaveOrRuntime() {
        String guard = read(AUTHORING_ROOT.resolve("SegmentActivationGuard.java"));
        assertThat(guard)
                .contains("requireActivatable")
                .contains("isActivatable")
                .as("the guard must reject everything that is not an exact ACTIVE match")
                .contains("ACTIVATION_REJECTED")
                .contains("CapabilityState.ACTIVE")
                .as("the guard is a port, not a path")
                .doesNotContain("Repository")
                .doesNotContain("save(")
                .doesNotContain("apply(")
                .doesNotContain("publish(")
                .doesNotContain("Clock")
                .doesNotContain("Random");
        // The guard consumes the T14 projection and the compiled Plan only; it produces no artifact.
        assertThat(guard).doesNotContain("new CampaignPlan").doesNotContain("new CampaignProposalPatch");
    }

    @Test
    void theGatePackageStillDependsOnNothingInTheAuthoringIntegration() {
        List<String> violations = new ArrayList<>();
        Path gate = Path.of("src", "main", "java", "com", "company", "campaign", "evaluation", "segment", "gate");
        for (Path file : filesUnder(gate)) {
            Matcher matcher = IMPORT.matcher(read(file));
            while (matcher.find()) {
                if (matcher.group(2).startsWith("com.company.campaign.builder.authoring")) {
                    violations.add(file + " -> " + matcher.group(2));
                }
            }
        }
        assertThat(violations).as("the T14 Gate must not depend on the T16 authoring integration").isEmpty();
    }

    private static List<Path> authoringFiles() {
        return filesUnder(AUTHORING_ROOT);
    }

    private static List<Path> filesUnder(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(path -> path.toString().endsWith(".java")).toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static List<String> fileNames(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(path -> path.toString().endsWith(".java"))
                    .map(path -> path.getFileName().toString())
                    .toList();
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