package com.company.campaign.runtime;

import com.company.campaign.testsupport.OptInDatabaseDependencies;
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
 * Focused T22 boundary tests: the Runtime layer depends on no Builder Session, Proposal, Plan, ChangeSet,
 * Prompt, Skill, Tool, Agent or LLM contract, no mutable Metadata/Catalog pointer, no JPA/Flyway persistence and
 * no web controller, and its production logic reads no ambient clock, randomness or security context.
 */
class RuntimeBoundaryTest {

    private static final Path REPOSITORY_ROOT = Path.of("").toAbsolutePath();
    private static final Path RUNTIME_SOURCES = REPOSITORY_ROOT.resolve("src/main/java/com/company/campaign/runtime");

    /** Import prefixes a Runtime production source must never reach for. */
    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "com.company.campaign.builder.proposal.",
            "com.company.campaign.builder.skill.",
            "com.company.campaign.builder.tool.",
            "com.company.campaign.builder.agent.",
            "com.company.campaign.builder.refine.",
            "com.company.campaign.builder.segment.",
            "com.company.campaign.builder.authoring.",
            "com.company.campaign.builder.capability.composition.",
            "com.company.campaign.builder.capability.catalog.",
            "com.company.campaign.metadata.",
            "com.company.campaign.preview.",
            "com.company.campaign.query.",
            "com.company.campaign.journey.simulation.",
            "com.company.campaign.evaluation.",
            "com.company.campaign.builder.compiler.CampaignPlan",
            "com.company.campaign.builder.compiler.ChangeSet",
            "com.company.campaign.builder.compiler.TrustedCurrentCampaign",
            "com.company.campaign.builder.compiler.FrozenCapabilityCatalog",
            "com.company.campaign.builder.compiler.FrozenAuthoringPolicy",
            "com.company.campaign.lifecycle.SavedCampaignDraft",
            "com.company.campaign.lifecycle.PublishPreparation",
            "com.company.campaign.lifecycle.PublishCommand",
            "com.company.campaign.lifecycle.LifecycleStore",
            "com.company.campaign.lifecycle.PublishService",
            "jakarta.persistence.",
            "javax.persistence.",
            "org.flywaydb.",
            "org.springframework.web.",
            "org.springframework.stereotype.",
            "org.springframework.context.annotation.",
            "org.hibernate.");

    /** Substrings a Runtime production source must never contain, because they would be ambient behaviour. */
    private static final List<String> FORBIDDEN_AMBIENT = List.of(
            "Instant.now(",
            "System.currentTimeMillis(",
            "Math.random(",
            "new Random(",
            "UUID.randomUUID(",
            "SecurityContext",
            "ThreadLocal",
            "@Entity",
            "@Table",
            "@Repository",
            "@RestController",
            "@Controller",
            "InMemoryFullPublicationStore",
            "outboxEvents(");

    @Test
    void runtimeSourcesImportNoBuilderSessionProposalPlanChangeSetPromptSkillToolOrLlmContract()
            throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path source : runtimeSources()) {
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

        assertThat(violations).as("forbidden Runtime imports").isEmpty();
    }

    @Test
    void runtimeSourcesUseNoAmbientClockRandomnessSecurityContextOrTestGetter() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path source : runtimeSources()) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            for (String forbidden : FORBIDDEN_AMBIENT) {
                if (text.contains(forbidden)) {
                    violations.add(source.getFileName() + ": " + forbidden);
                }
            }
        }

        assertThat(violations).as("forbidden ambient Runtime behaviour").isEmpty();
    }

    @Test
    void runtimeDefinesNoControllerNoJpaAdapterAndNoFlywayMigration() throws IOException {
        // T25 later adds the readiness slice's own ordered, expand-only SQL assets under
        // src/main/resources/db/migration. This assertion is therefore scoped to what T22 actually required:
        // the Runtime layer owns no migration asset and no JPA/Flyway wiring, and the build still carries no
        // Flyway, JPA or Hibernate dependency. A Runtime migration would still fail here.
        assertThat(runtimeSources()).noneSatisfy(source ->
                assertThat(source.getFileName().toString()).endsWith(".sql"));
        assertThat(runtimeSources()).noneSatisfy(source ->
                assertThat(source.getFileName().toString()).contains("Migration"));
        assertThat(runtimeSources()).noneSatisfy(source ->
                assertThat(source.getFileName().toString()).contains("Flyway"));
        // T26 adds BOM-pinned Flyway and PostgreSQL JDBC dependencies for the opt-in readiness storage
        // configuration, so the blanket "no flyway in the pom" string check is replaced by the same
        // adapter-independent contract the readiness slice asserts: no embedded engine, no JPA, no
        // Hibernate, and only BOM-pinned opt-in PostgreSQL artifacts. A Runtime Flyway/JPA wiring would
        // still fail the source assertions above.
        OptInDatabaseDependencies.assertPinnedOptInDatabaseDependencies(REPOSITORY_ROOT.resolve("pom.xml"));
        assertThat(Files.readString(REPOSITORY_ROOT.resolve("pom.xml"), StandardCharsets.UTF_8))
                .doesNotContain("spring-boot-starter-data-jpa")
                .doesNotContain("hibernate");
        assertThat(runtimeSources()).noneSatisfy(source ->
                assertThat(source.getFileName().toString()).contains("Controller"));
    }

    @Test
    void runtimeConsumesTheAcceptedT21WorkThroughItsOwnNarrowPortsOnly() throws IOException {
        List<String> imports = new ArrayList<>();
        for (Path source : runtimeSources()) {
            for (String line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("import com.company.campaign.fullpublish.")) {
                    imports.add(source.getFileName() + ": " + trimmed);
                }
            }
        }

        assertThat(imports).isNotEmpty();
        assertThat(imports).allSatisfy(entry -> assertThat(entry).containsAnyOf(
                "ScheduleRegistrationOutboxEvent",
                "ScheduledOccurrenceRecord",
                "OccurrenceDecision",
                "FullPublishedDefinition",
                "FullPublishVersions",
                "RuntimeWorkDeliveryPort"));
    }

    @Test
    void runtimeNeverClaimsProductionGateEvidence() {
        assertThat(RuntimeVersions.PRODUCTION_GATE_EVIDENCE).isFalse();
    }

    private static List<Path> runtimeSources() throws IOException {
        try (Stream<Path> paths = Files.walk(RUNTIME_SOURCES)) {
            return paths.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
    }
}