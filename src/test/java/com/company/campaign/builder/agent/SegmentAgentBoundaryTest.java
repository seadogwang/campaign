package com.company.campaign.builder.agent;

import com.company.campaign.builder.segment.DevelopmentAiToSqlService;
import com.company.campaign.builder.tool.SegmentToolCatalog;
import com.company.campaign.query.sql.SqlCompiler;
import com.company.campaign.query.sql.StorageCatalog;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
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
 * Requirement 13: the formal Skill, Tool and Agent adapter depend on no SQL compiler, storage catalog, JDBC,
 * JPA, Flyway, Campaign mutation, Save/Apply/Publish, Runtime, T12+ or archived asset, and reads no wall clock,
 * random source or global context.
 */
class SegmentAgentBoundaryTest {

    private static final Path BUILDER = Path.of("src", "main", "java", "com", "company", "campaign", "builder");
    private static final List<Path> ROOTS = List.of(
            BUILDER.resolve("agent"), BUILDER.resolve("skill"), BUILDER.resolve("tool"));

    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "java.sql", "javax.sql", "jakarta.persistence", "javax.persistence", "org.hibernate",
            "org.springframework.", "org.springframework.data", "org.flywaydb", "com.zaxxer.hikari",
            "java.net", "javax.script", "jdk.internal", "org.springframework.ai", "com.openai", "dev.langchain4j",
            "com.company.campaign.query.sql", "com.company.campaign.query.fixture",
            "com.company.campaign.runtime", "com.company.campaign.evaluation",
            "com.company.campaign.builder.compiler", "com.company.campaign.builder.proposal",
            "com.company.campaign.repository");

    private static final List<String> FORBIDDEN_TEXT = List.of(
            "Instant.now", "LocalDate.now", "LocalDateTime.now", "OffsetDateTime.now", "ZonedDateTime.now",
            "Clock.system", "ZoneId.systemDefault", "Locale.getDefault", "UUID.randomUUID", "Math.random",
            "new Random(", "ThreadLocal", "System.getenv", "System.getProperty", "System.getProperties",
            "System.currentTimeMillis", "System.nanoTime", "System.out", "System.err",
            "Class.forName", "getDeclaredMethod", "getDeclaredField", "setAccessible", "Method.invoke",
            "ProcessBuilder", "Runtime.getRuntime", "DriverManager", "jdbc:", "ScriptEngine", "Thread.sleep",
            "FileWriter", "Files.write", "DevelopmentAiToSqlService", "ExecutionBackend", "docs/archive",
            "WAITING_USER_SAVE", "SqlCompiler", "StorageCatalog", "JdbcTemplate");

    private static final Pattern IMPORT =
            Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([A-Za-z0-9_.]+)\\s*;");

    @Test
    void theFormalSkillAndAgentSourcesExist() {
        assertThat(fileNames(BUILDER.resolve("skill"))).contains(
                "SegmentSkillDefinition.java", "SegmentSkillLoader.java", "SegmentSkillResource.java");
        assertThat(fileNames(BUILDER.resolve("tool"))).contains(
                "SegmentToolCatalog.java", "SegmentToolInvoker.java", "StandardSegmentToolHandlers.java",
                "SegmentPreviewPort.java");
        assertThat(fileNames(BUILDER.resolve("agent"))).contains(
                "SegmentAgentAdapter.java", "SegmentAgentState.java", "SegmentAgentStateMachine.java",
                "SegmentAgentRequest.java", "SegmentAgentResult.java", "SegmentPreviewCard.java",
                "GroundedExplanation.java", "ExplanationClaim.java", "SegmentAuditRecord.java",
                "SegmentAuditSink.java");
    }

    @Test
    void theFormalSourcesStayInsideTheAcceptedBoundary() {
        List<String> violations = new ArrayList<>();
        for (Path file : files()) {
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
        assertThat(violations).as("forbidden imports in the formal Skill/Tool/Agent boundary").isEmpty();
    }

    @Test
    void theFormalSourcesContainNoSqlPersistenceRuntimeClockRandomOrGlobalContextText() {
        List<String> violations = new ArrayList<>();
        for (Path file : files()) {
            String source = read(file);
            for (String forbidden : FORBIDDEN_TEXT) {
                if (source.contains(forbidden)) {
                    violations.add(file + " -> " + forbidden);
                }
            }
        }
        assertThat(violations).as("forbidden text in the formal Skill/Tool/Agent boundary").isEmpty();
    }

    @Test
    void theProtectedStagesAreInvokedOnlyFromTheAdmittedToolHandlers() {
        // The corrective task's central invariant: no Resolver, Validator, Planner, Preview or explanation
        // implementation may be reached from the Agent orchestration itself. The accepted components may be
        // constructed to be injected into the run, but every genuine invocation lives in the handler that the
        // closed invoker admits.
        List<String> protectedInvocations = List.of(
                "resolver.resolve(", ".validate(document", "planner().plan(", ".preview(document",
                "GroundedExplanationBuilder.build(");
        List<String> violations = new ArrayList<>();
        for (Path file : files()) {
            if (file.getParent().getFileName().toString().equals("tool")
                    && file.getFileName().toString().equals("StandardSegmentToolHandlers.java")) {
                continue;
            }
            String source = read(file);
            for (String invocation : protectedInvocations) {
                if (source.contains(invocation)) {
                    violations.add(file + " -> " + invocation);
                }
            }
        }
        assertThat(violations).as("protected stages outside the admitted Tool handlers").isEmpty();

        String handlers = read(BUILDER.resolve("tool").resolve("StandardSegmentToolHandlers.java"));
        for (String invocation : protectedInvocations) {
            assertThat(handlers).as("the Tool boundary really executes the protected stage").contains(invocation);
        }
    }

    @Test
    void theAdapterHasNoSqlCompilerStorageCatalogOrSqlDependency() {
        for (Field field : SegmentAgentAdapter.class.getDeclaredFields()) {
            assertThat(SqlCompiler.class.isAssignableFrom(field.getType())).isFalse();
            assertThat(StorageCatalog.class.isAssignableFrom(field.getType())).isFalse();
        }
        assertThat(SegmentAgentAdapter.class.getDeclaredMethods())
                .extracting(Method::getReturnType)
                .doesNotContain(SqlCompiler.class, StorageCatalog.class);
        assertThat(DevelopmentAiToSqlService.class.getSimpleName()).isEqualTo("DevelopmentAiToSqlService");
    }

    @Test
    void theToolCatalogCannotBeWidenedAtRuntime() {
        assertThat(SegmentToolCatalog.class.getDeclaredMethods())
                .extracting(Method::getName)
                .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("register")
                        || name.toLowerCase(Locale.ROOT).contains("put")
                        || name.toLowerCase(Locale.ROOT).contains("install")
                        || name.toLowerCase(Locale.ROOT).contains("define")
                        || name.toLowerCase(Locale.ROOT).contains("override"));
        assertThat(SegmentToolCatalog.class.getDeclaredConstructors()).hasSize(1);
        assertThat(java.lang.reflect.Modifier.isPrivate(
                SegmentToolCatalog.class.getDeclaredConstructors()[0].getModifiers())).isTrue();
    }

    @Test
    void theAgentRequestCannotCarryATrustedFactOverrideBackendTimeOrArtifact() {
        for (RecordComponent component : SegmentAgentRequest.class.getRecordComponents()) {
            String name = component.getName().toLowerCase(Locale.ROOT);
            assertThat(name)
                    .doesNotContain("tenant").doesNotContain("program").doesNotContain("user")
                    .doesNotContain("role").doesNotContain("permission").doesNotContain("scope")
                    .doesNotContain("metadata").doesNotContain("skill").doesNotContain("version")
                    .doesNotContain("model").doesNotContain("provider").doesNotContain("time")
                    .doesNotContain("backend").doesNotContain("sql").doesNotContain("save")
                    .doesNotContain("publish").doesNotContain("sample").doesNotContain("resolution")
                    .doesNotContain("dsl").doesNotContain("plan").doesNotContain("preview");
        }
        assertThat(SegmentAgentRequest.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("utterance", "continuation");
    }

    @Test
    void theAgentRequestCarriesNoConversationPresentationTurns() {
        // The first implementation accepted presentation turns and then ignored them, so a caller could pass
        // semantically inert input. This Skill version's formal request carries the utterance alone.
        assertThat(SegmentAgentRequest.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .doesNotContain("turns", "history", "messages");
        assertThat(SegmentAgentRequest.class.getDeclaredClasses()).isEmpty();
    }

    @Test
    void theEnvironmentCarriesNoSampleFlagThatCouldOverrideTheSkill() {
        assertThat(SegmentAgentEnvironment.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .doesNotContain("sampleRequested", "sampleOutputPermitted", "includeSample");
        assertThat(SegmentAgentEnvironment.class.getDeclaredMethods())
                .extracting(Method::getName)
                .doesNotContain("withSampleRequested");
    }

    @Test
    void theToolOutcomeCanCarryNoRestrictedArtifactOrRawModelValue() {
        assertThat(com.company.campaign.builder.tool.SegmentToolOutcome.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("toolId", "outcomeCode", "itemCount", "evidenceHash", "stableReferences");
        assertThat(com.company.campaign.builder.tool.SegmentToolArtifact.class.isSealed()).isTrue();
        // The union is closed by name rather than by count, so adding a variant is a deliberate, reviewable
        // act instead of a size that happens to match. T18 adds exactly one: the bounded read-only answer of
        // the Existing Audience Reference discovery/resolve Tools.
        assertThat(com.company.campaign.builder.tool.SegmentToolArtifact.class.getPermittedSubclasses())
                .extracting(Class::getSimpleName)
                .containsExactlyInAnyOrder(
                        "MetadataDiscovery", "Resolution", "Validation", "Plan", "Preview", "Explanation",
                        "ReferencedAudiences");
        // The new variant carries only already-bounded, non-disclosing answer shapes: never a raw member set,
        // a model value, an open map or a payload fragment.
        assertThat(com.company.campaign.builder.tool.SegmentToolArtifact.ReferencedAudiences.class
                .getRecordComponents())
                .extracting(RecordComponent::getType)
                .containsExactlyInAnyOrder(
                        String.class,
                        com.company.campaign.builder.tool.reference.ReferenceDiscoveryToolOutput.class,
                        com.company.campaign.builder.tool.reference.ReferenceResolutionToolOutput.class,
                        ContentHash.class);
    }

    @Test
    void theModelIsNeverAClarificationSelectionPort() {
        for (RecordComponent component : SegmentAgentRequest.class.getRecordComponents()) {
            assertThat(com.company.campaign.segment.resolution.ClarificationSelectionPort.class
                    .isAssignableFrom(component.getType())).isFalse();
        }
        for (RecordComponent component : SegmentAgentEnvironment.class.getRecordComponents()) {
            assertThat(com.company.campaign.segment.resolution.ClarificationSelectionPort.class
                    .isAssignableFrom(component.getType())).isFalse();
        }
    }

    @Test
    void theEnvironmentServicesAreInjectedAndNeverReadFromAPayload() {
        for (RecordComponent component : SegmentAgentEnvironment.class.getRecordComponents()) {
            String name = component.getName().toLowerCase(Locale.ROOT);
            assertThat(name).doesNotContain("payload").doesNotContain("requestbody");
            assertThat(SegmentAgentRequest.class.isAssignableFrom(component.getType())).isFalse();
        }
        assertThat(SegmentAgentEnvironment.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .contains("skill", "catalog", "trustedContextProvider", "snapshotSource", "authorizationService",
                        "modelPort", "previewPort", "auditSink", "timings");
    }

    @Test
    void theAuditProjectionCannotCarryRawUtteranceSamplesOrTrustedIdentifiers() {
        for (RecordComponent component : SegmentAuditRecord.class.getRecordComponents()) {
            String name = component.getName().toLowerCase(Locale.ROOT);
            assertThat(name)
                    .doesNotContain("rawutterance").doesNotContain("sampletext").doesNotContain("memberid")
                    .doesNotContain("tenant").doesNotContain("program").doesNotContain("userid")
                    .doesNotContain("credential").doesNotContain("sqltext");
        }
    }

    private static List<Path> files() {
        List<Path> files = new ArrayList<>();
        for (Path root : ROOTS) {
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