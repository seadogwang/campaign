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
 * Requirement 17: T15 production code depends only on the accepted shared canonical/contracts and T02
 * security boundaries. It reaches no database, Web layer, model or provider client, Query/Preview/Segment
 * pipeline, Apply/Publish/Runtime path, clock, random source, script engine or global/ThreadLocal context.
 *
 * <p>The import rules apply to the two T15 subtrees, {@code builder/proposal} and {@code builder/compiler}.
 * The sibling {@code builder/segment} tree is the T11A development validation slice, which by design composes
 * the accepted T04/T05/T07/T08/T09B/T10 components; it is covered instead by the dedicated
 * {@code DevelopmentAiToSqlSliceBoundaryTest}. The {@code builder/authoring} tree is the T16 integration that
 * legitimately implements the concrete rule-based Segment capability; it is covered by
 * {@code SegmentAuthoringDependencyBoundaryTest}, and this test still proves the generic T15 subtrees stay
 * capability-agnostic. The clock/random/global-context and deserialization rules still apply to every file
 * under {@code builder}, including {@code builder/segment} and {@code builder/authoring}.
 */
class BuilderDependencyBoundaryTest {

    private static final Path BUILDER_ROOT =
            Path.of("src", "main", "java", "com", "company", "campaign", "builder");
    private static final Path PROPOSAL_ROOT = BUILDER_ROOT.resolve("proposal");
    private static final Path COMPILER_ROOT = BUILDER_ROOT.resolve("compiler");
    private static final Path REFINE_ROOT = BUILDER_ROOT.resolve("refine");
    private static final List<Path> T15_ROOTS = List.of(PROPOSAL_ROOT, COMPILER_ROOT);

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
            "java.net",
            "javax.script",
            "jdk.internal",
            "com.company.campaign.query",
            "com.company.campaign.preview",
            "com.company.campaign.runtime",
            "com.company.campaign.segment",
            "com.company.campaign.evaluation",
            "com.company.campaign.metadata",
            "com.company.campaign.repository",
            "com.company.campaign.CampaignApplication");

    private static final List<String> FORBIDDEN_TEXT = List.of(
            "Instant.now", "LocalDate.now", "LocalDateTime.now", "OffsetDateTime.now", "ZonedDateTime.now",
            "Clock.system", "ZoneId.systemDefault", "Locale.getDefault", "UUID.randomUUID", "Math.random",
            "new Random(", "ThreadLocal", "System.getenv", "System.getProperty", "System.getProperties",
            "System.currentTimeMillis", "System.nanoTime", "System.out", "System.err",
            "Class.forName", "getDeclaredMethod", "getDeclaredField", "setAccessible", "Method.invoke",
            "ProcessBuilder", "Runtime.getRuntime", "DriverManager", "jdbc:", "DataSource", "ScriptEngine",
            "Thread.sleep", "FileWriter", "Files.write");

    private static final Pattern IMPORT =
            Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([A-Za-z0-9_.]+)\\s*;");

    @Test
    void theProductionBuilderSourcesExist() {
        assertThat(fileNames(PROPOSAL_ROOT)).contains(
                "CampaignProposalPatch.java", "CampaignProposalCodec.java", "ProposalOperation.java",
                "ProposalIdentity.java", "ProposalErrorCodes.java", "ProposalException.java",
                "ProposalReservedFields.java", "ProposalLimits.java");
        assertThat(fileNames(COMPILER_ROOT)).contains(
                "CampaignCompiler.java", "CampaignPlan.java", "CampaignPlanStore.java",
                "InMemoryCampaignPlanStore.java", "ResolvedCampaignManifest.java", "ResolvedComponent.java",
                "CapabilityContract.java", "CapabilityPayload.java", "CapabilityResolution.java",
                "FrozenCapabilityCatalog.java", "FrozenAuthoringPolicy.java", "TrustedCurrentCampaign.java",
                "SemanticDiff.java", "ChangeSet.java", "ChangeSetNode.java", "CompileInstants.java");
    }

    @Test
    void builderImportsStayInsideTheAllowedBoundary() {
        List<String> violations = new ArrayList<>();
        for (Path file : t15Files()) {
            Matcher matcher = IMPORT.matcher(read(file));
            while (matcher.find()) {
                String imported = matcher.group(2);
                if (ALLOWED_IMPORTS.stream().noneMatch(imported::startsWith)) {
                    violations.add(file + " -> " + imported);
                }
            }
        }
        assertThat(violations).as("imports outside the T15 boundary").isEmpty();
    }

    @Test
    void builderDoesNotReachPersistenceWebProvidersAiQueryPreviewOrRuntime() {
        List<String> violations = new ArrayList<>();
        for (Path file : t15Files()) {
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
        assertThat(violations).as("forbidden imports in T15").isEmpty();
    }

    @Test
    void builderContainsNoClockRandomGlobalContextOrReflectionText() {
        List<String> violations = new ArrayList<>();
        for (Path file : builderFiles()) {
            String source = read(file);
            for (String forbidden : FORBIDDEN_TEXT) {
                if (source.contains(forbidden)) {
                    violations.add(file + " -> " + forbidden);
                }
            }
        }
        assertThat(violations).as("forbidden text in T15").isEmpty();
    }

    @Test
    void theGenericT15BoundaryImplementsNoCapabilitySemantics() {
        // T16 adds the concrete rule-based Segment capability under builder/authoring, so the generic T15
        // subtrees (proposal, compiler) are what must stay free of any concrete capability semantics; the new
        // authoring subtree is covered by its own architecture test.
        List<String> violations = new ArrayList<>();
        for (Path file : t15Files()) {
            String source = read(file);
            for (String code : List.of("segment.rule-based", "message.template", "promotion.reference",
                    "flow.wait", "flow.message", "flow.start", "flow.reward", "schedule.basic",
                    "governance.limit")) {
                if (source.contains(code)) {
                    violations.add(file + " -> " + code);
                }
            }
        }
        assertThat(violations).as("the generic T15 boundary stays capability-agnostic").isEmpty();
    }

    @Test
    void theOnlyDeserializationBoundaryIsTheStrictProposalCodec() {
        // Jackson binding is reachable from two places only: the strict wire codec and the payload ingest
        // helper it calls. No Manifest, Plan, Diff or ChangeSet is ever deserialized. The T12 refinement
        // codec names the same token only because it reuses the accepted T05 SegmentDslCodec accessor; the
        // assertions below prove it adds no Jackson binding of its own.
        List<String> deserializing = new ArrayList<>();
        for (Path file : builderFiles()) {
            String source = read(file);
            if (source.contains("readValue") || source.contains("treeToValue")
                    || source.contains("valueToTree")) {
                deserializing.add(file.getFileName().toString());
            }
        }
        assertThat(deserializing)
                .as("the only deserialization boundaries")
                .containsExactlyInAnyOrder(
                        "CapabilityPayload.java", "CampaignProposalCodec.java", "SegmentRefinementPatchCodec.java");
        String refinementCodec = read(REFINE_ROOT.resolve("SegmentRefinementPatchCodec.java"));
        assertThat(refinementCodec)
                .contains("DSL_CODEC.readValue")
                .doesNotContain("ObjectMapper")
                .doesNotContain("mapper.")
                .doesNotContain("treeToValue")
                .doesNotContain("valueToTree");
        assertThat(read(COMPILER_ROOT.resolve("CampaignCompiler.java")))
                .contains("public CampaignPlan compile(CompileRequest request)")
                .doesNotContain("readValue");
    }

    private static List<Path> builderFiles() {
        try (Stream<Path> walk = Files.walk(BUILDER_ROOT)) {
            return walk.filter(path -> path.toString().endsWith(".java")).toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** The two T15 subtrees whose import graph must stay inside the T15 dependency boundary. */
    private static List<Path> t15Files() {
        List<Path> files = new ArrayList<>();
        for (Path root : T15_ROOTS) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(path -> path.toString().endsWith(".java")).forEach(files::add);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
        return files;
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