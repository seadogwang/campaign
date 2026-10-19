package com.company.campaign.builder.assembly;

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
 * Requirement 13: the Campaign assembly package cannot depend on lifecycle, full-publish, schedule-runtime,
 * action, Provider, SQL, JDBC, JPA, Flyway, controller or frontend code, and the model-callable Tool
 * vocabulary cannot name Apply, Publish, Runtime, Provider, credential or storage.
 */
class CampaignAssemblyBoundaryTest {

    private static final Path ASSEMBLY_ROOT =
            Path.of("src", "main", "java", "com", "company", "campaign", "builder", "assembly");

    private static final List<String> ALLOWED_IMPORTS = List.of(
            "java.", "com.company.campaign.", "com.fasterxml.jackson.");

    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "org.springframework.", "jakarta.", "javax.", "java.sql", "javax.sql", "org.hibernate",
            "org.springframework.data", "com.openai", "dev.langchain4j", "java.net", "java.nio.file",
            "java.time.Clock", "javax.script", "jdk.internal", "org.junit",
            "com.company.campaign.lifecycle", "com.company.campaign.fullpublish", "com.company.campaign.runtime",
            "com.company.campaign.action", "com.company.campaign.preview.api", "com.company.campaign.preview.adapter",
            "com.company.campaign.CampaignApplication");

    private static final List<String> FORBIDDEN_CODE = List.of(
            "CampaignCompiler", "CampaignPlanStore", "InMemoryCampaignPlanStore", "compileAndStore",
            "LifecycleStore", "PublishPreparation", "CompileRequest(", "new CampaignPlan",
            "Clock.system", "Math.random", "UUID.randomUUID", "ThreadLocal", "System.getenv",
            "System.getProperty", "System.currentTimeMillis", "Class.forName", "getDeclaredMethod",
            "setAccessible", "ScriptEngine", "HttpClient", "WebClient", "ProcessBuilder",
            "DriverManager", "DataSource", "JdbcTemplate", "EntityManager", "SqlSession", "new Random(",
            "frontend", "campaign.runtime", "campaign.lifecycle", "campaign.action");

    private static final Pattern IMPORT =
            Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([A-Za-z0-9_.]+)\\s*;");

    @Test
    void theAssemblyPackageDependsOnNothingOutsideTheDeterministicBoundary() {
        List<String> violations = new ArrayList<>();
        for (Path file : assemblyFiles()) {
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
        assertThat(violations).as("imports outside the Campaign assembly boundary").isEmpty();
    }

    @Test
    void theAssemblyPackageCarriesNoLifecycleApplyPublishRuntimeProviderOrStorageCode() {
        List<String> violations = new ArrayList<>();
        for (Path file : assemblyFiles()) {
            String source = read(file);
            for (String forbidden : FORBIDDEN_CODE) {
                if (source.contains(forbidden)) {
                    violations.add(file.getFileName() + " -> " + forbidden);
                }
            }
        }
        assertThat(violations)
                .as("the Campaign assembly layer must produce a Proposal and never Apply, Publish, Save or Runtime")
                .isEmpty();
    }

    @Test
    void theAssemblyPackageExistsWithoutAStoreControllerEntityOrSecondCompiler() {
        List<String> names = fileNames();
        assertThat(names).contains(
                "CampaignToolCatalog.java", "CampaignToolDescriptor.java", "CampaignToolInvoker.java",
                "CampaignToolIds.java", "CampaignSkillIds.java", "CampaignAssemblyWorkspace.java",
                "CampaignAssemblySnapshot.java", "CampaignAssemblyInspection.java", "CampaignComponentView.java",
                "CampaignSkillArtifact.java", "CampaignArtifactAdapter.java",
                "CampaignSegmentAdapter.java", "CampaignDependencyInspector.java");
        assertThat(names).noneMatch(name -> name.endsWith("Repository.java") || name.endsWith("Entity.java")
                || name.endsWith("Controller.java") || name.endsWith("ControllerTest")
                || name.endsWith("Service.java") || name.endsWith("Dao.java") || name.endsWith("Table.java")
                || name.endsWith("Schema.java") || name.contains("Draft"));
    }

    @Test
    void theModelCallableVocabularyCannotNameApplyPublishRuntimeProviderCredentialOrStorage() {
        for (String forbidden : new String[]{
                "apply", "publish", "runtime", "save", "storage", "provider", "credential", "secret", "sql",
                "jdbc", "repository", "inventory", "reserve", "execute"}) {
            assertThat(CampaignToolIds.attemptsForbiddenCapability(forbidden + ".thing"))
                    .as(forbidden)
                    .isTrue();
        }
        for (String toolId : CampaignToolIds.ordered()) {
            assertThat(CampaignToolIds.attemptsForbiddenCapability(toolId)).as(toolId).isFalse();
        }
        for (String toolId : CampaignToolIds.implemented()) {
            String normalized = toolId.toLowerCase().replace(".", "");
            assertThat(normalized).doesNotContain("apply").doesNotContain("publish").doesNotContain("runtime")
                    .doesNotContain("storage").doesNotContain("provider").doesNotContain("credential");
        }
    }

    @Test
    void theOnlySegmentIntegrationIsANarrowAdapterThatCannotRecompile() {
        String adapter = read(ASSEMBLY_ROOT.resolve("CampaignSegmentAdapter.java"));
        assertThat(adapter)
                .contains("SegmentAuthoringIntegration")
                .contains("SegmentAuthoringSource")
                .doesNotContain("new CampaignCompiler")
                .doesNotContain("SegmentAgentAdapter")
                .doesNotContain("SegmentSkillDefinition");
    }

    @Test
    void proposalEmissionIsSideEffectFreeAndNeverCallsTheCompiler() {
        String workspace = read(ASSEMBLY_ROOT.resolve("CampaignAssemblyWorkspace.java"));
        assertThat(workspace)
                .contains("CampaignProposalPatch")
                .doesNotContain("CampaignCompiler")
                .doesNotContain("compileAndStore")
                .doesNotContain("CampaignPlan")
                .doesNotContain("ResolvedCampaignManifest")
                .doesNotContain("new ChangeSet");
    }

    private static List<Path> assemblyFiles() {
        try (Stream<Path> walk = Files.walk(ASSEMBLY_ROOT)) {
            return walk.filter(path -> path.toString().endsWith(".java")).toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static List<String> fileNames() {
        try (Stream<Path> walk = Files.walk(ASSEMBLY_ROOT)) {
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