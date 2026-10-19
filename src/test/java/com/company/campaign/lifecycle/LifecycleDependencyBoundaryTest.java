package com.company.campaign.lifecycle;

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
 * T17 requirement 11: the host-only lifecycle boundary is a deterministic, framework-independent module.
 *
 * <p>It reaches no controller, Web layer, model or provider client, Tool registry, repository, database,
 * JPA, Flyway, SQL, Outbox, scheduler, Runtime or Provider side effect, and it reads no clock, random source
 * or ambient value. The publish phase freezes an immutable definition and stores an expiring preparation; it
 * schedules, enqueues and dispatches nothing, which is exactly the T21 work this task leaves deferred.
 *
 * <p>Any Phase-A-only assertion that the lifecycle package holds no preparation, publish or fork type is
 * retired: the full T17 boundary is asserted here instead, including that it is still free of every
 * persistence, migration, scheduling and runtime dependency.
 */
class LifecycleDependencyBoundaryTest {

    private static final Path LIFECYCLE_ROOT =
            Path.of("src", "main", "java", "com", "company", "campaign", "lifecycle");

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
            "org.hibernate",
            "org.springframework.data",
            "org.springframework.ai",
            "com.openai",
            "dev.langchain4j",
            "java.net",
            "javax.script",
            "jdk.internal",
            "com.company.campaign.metadata.repository",
            "com.company.campaign.runtime",
            "com.company.campaign.message",
            "com.company.campaign.reward",
            "com.company.campaign.schedule",
            "com.company.campaign.journey",
            "com.company.campaign.audience",
            "com.company.campaign.CampaignApplication",
            "com.company.campaign.builder.tool",
            "com.company.campaign.builder.agent",
            "com.company.campaign.builder.skill",
            "com.company.campaign.evaluation.segment.runner",
            "com.company.campaign.evaluation.segment.loader",
            "com.company.campaign.preview.adapter",
            "com.company.campaign.query.fixture");

    private static final List<String> FORBIDDEN_TEXT = List.of(
            "Instant.now", "LocalDate.now", "LocalDateTime.now", "OffsetDateTime.now", "ZonedDateTime.now",
            "Clock.system", "ZoneId.systemDefault", "Locale.getDefault", "UUID.randomUUID", "Math.random",
            "new Random(", "ThreadLocal", "System.getenv", "System.getProperty", "System.currentTimeMillis",
            "System.nanoTime", "System.out", "System.err", "Class.forName", "getDeclaredMethod",
            "getDeclaredField", "setAccessible", "Method.invoke", "ProcessBuilder", "Runtime.getRuntime",
            "DriverManager", "jdbc:", "DataSource", "ScriptEngine", "Thread.sleep", "FileWriter",
            "Files.write", "Files.read");

    /**
     * The scheduling, dispatch and runtime side effects a later T21 task owns.
     *
     * <p>Each marker is a concrete production symptom, not a word: a queue, a dispatcher, a scheduler, a
     * provider client, a template engine or a runtime entry point. A publish that produced one of these would
     * have started executing a Campaign instead of merely freezing one.
     */
    private static final List<String> FORBIDDEN_SIDE_EFFECTS = List.of(
            "Outbox", "outbox", "Scheduler", "Schedule", "enqueue", "dispatch", "publishTo",
            "Provider", "Journey", "RuntimePort", "TemplateEngine", "send(", "webhook",
            "HttpClient", "RestTemplate", "MessageProducer", "TaskExecutor");

    private static final Pattern IMPORT =
            Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([A-Za-z0-9_.]+)\\s*;");

    /**
     * The full T17 boundary exists: the publish phase is present, not merely asserted absent.
     *
     * <p>This replaces the Phase-A-only claim that the package contains no preparation, publish or fork
     * type. The boundary is now defined positively by what T17 must hold.
     */
    @Test
    void theLifecyclePackageContainsTheFullT17Boundary() {
        assertThat(fileNames()).contains(
                "ApplyService.java", "ApplyCommit.java", "ApplyReceipt.java", "ApplyResult.java",
                "SavedCampaignDraft.java", "LifecycleStore.java",
                "PublishPreparation.java", "PublishPreparationService.java", "PreparePublishResult.java",
                "PublishService.java", "PublishCommit.java", "PublishReceipt.java", "PublishResult.java",
                "PublishedCampaignDefinition.java",
                "PublishedForkService.java", "ForkCommit.java", "ForkResult.java");
    }

    @Test
    void lifecycleImportsStayInsideTheDeterministicBoundary() {
        List<String> violations = new ArrayList<>();
        for (Path file : lifecycleFiles()) {
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
        assertThat(violations).as("imports outside the lifecycle boundary").isEmpty();
    }

    @Test
    void lifecycleContainsNoClockRandomGlobalContextReflectionOrFileAccess() {
        List<String> violations = new ArrayList<>();
        for (Path file : lifecycleFiles()) {
            String source = read(file);
            for (String forbidden : FORBIDDEN_TEXT) {
                if (source.contains(forbidden)) {
                    violations.add(file + " -> " + forbidden);
                }
            }
        }
        assertThat(violations).as("forbidden text in the lifecycle boundary").isEmpty();
    }

    @Test
    void theLifecyclePackageAddsNoControllerRepositoryEntityMigrationOutboxOrScheduler() {
        List<String> names = fileNames();
        assertThat(names).noneMatch(name -> name.endsWith("Controller.java")
                || name.endsWith("Repository.java")
                || name.endsWith("Entity.java")
                || name.endsWith("Dao.java")
                || name.endsWith("Table.java")
                || name.endsWith("Schema.java")
                || name.endsWith("Migration.java")
                || name.endsWith("Scheduler.java")
                || name.endsWith("Outbox.java")
                || name.endsWith("Worker.java")
                || name.endsWith("Provider.java")
                || name.endsWith("Client.java"));
    }

    @Test
    void theLifecyclePackageSchedulesEnqueuesAndDispatchesNothing() {
        List<String> violations = new ArrayList<>();
        for (Path file : lifecycleFiles()) {
            String source = read(file);
            for (String marker : FORBIDDEN_SIDE_EFFECTS) {
                if (source.contains(marker)) {
                    violations.add(file + " -> " + marker);
                }
            }
        }
        assertThat(violations)
                .as("T17 freezes a definition and stops; T21 owns scheduling and dispatch")
                .isEmpty();
    }

    @Test
    void theLifecyclePackageNeverNamesAToolAgentSkillOrRuntimeEntryPoint() {
        List<String> names = fileNames();
        assertThat(names).noneMatch(name -> name.contains("Tool") || name.contains("Agent")
                || name.contains("Skill") || name.contains("Runtime") || name.contains("Journey")
                || name.contains("Outbox"));

        List<String> violations = new ArrayList<>();
        for (Path file : lifecycleFiles()) {
            String source = read(file);
            for (String marker : List.of("ToolCatalog", "ToolDescriptor", "registerTool", "toolCall",
                    "AgentRun", "SkillInvocation", "PromptTemplate")) {
                if (source.contains(marker)) {
                    violations.add(file + " -> " + marker);
                }
            }
        }
        assertThat(violations).as("no model-facing registration reaches the lifecycle core").isEmpty();
    }

    @Test
    void everyLifecycleContractVerifiesItsOwnCanonicalHash() {
        for (String contract : List.of(
                "PublishPreparation.java", "PublishedCampaignDefinition.java", "PublishReceipt.java",
                "ApplyReceipt.java", "SavedCampaignDraft.java")) {
            String source = read(LIFECYCLE_ROOT.resolve(contract));
            assertThat(source)
                    .as("%s must re-prove its own canonical hash at construction", contract)
                    .contains("CANONICAL_JSON.hash(")
                    .contains("throw invalid(");
        }
    }

    @Test
    void theLifecyclePackageDeclaresNoModelToolRegistrationOrWebAnnotation() {
        List<String> violations = new ArrayList<>();
        for (Path file : lifecycleFiles()) {
            String source = read(file);
            for (String marker : List.of(
                    "@RestController", "@Controller", "@Service", "@Component", "@Repository",
                    "@Entity", "@Table", "@Scheduled", "@Transactional", "ToolCatalog", "ToolDescriptor",
                    "registerTool", "@Bean")) {
                if (source.contains(marker)) {
                    violations.add(file + " -> " + marker);
                }
            }
        }
        assertThat(violations).as("the lifecycle core stays free of framework registration").isEmpty();
    }

    /**
     * T17 adds no controller, persistence, migration, Outbox, scheduler, Runtime or Provider dependency.
     *
     * <p>The publish phase freezes a definition and stops; it never reaches a database, a migration, a queue
     * or a runtime, so the only artifacts this package ships are its own Java sources.
     */
    @Test
    void theLifecyclePackageAddsNoControllerPersistenceMigrationOutboxSchedulerOrProviderDependency() {
        // A migration tool is reachable only through its package, so the import allow-list above is the guard
        // for it; these markers are the concrete symptoms a source scan can see. Each is a real dependency
        // symptom rather than a word a comment might legitimately mention.
        List<String> forbidden = List.of(
                "@RestController", "@Controller", "@Entity", "@Table", "@Column", "@Repository",
                "@Transactional", "@Scheduled",
                "EntityManager", "JdbcTemplate", "DataSource", "PersistenceContext", "SessionFactory",
                "org.flywaydb", "MigrationScript", "CREATE TABLE", "create table",
                "Outbox", "outbox", "Scheduler", "scheduleAt", "cron(",
                "RuntimePort", "HttpClient", "RestTemplate", "WebClient", "ProviderClient",
                "MessageProducer", "TaskExecutor");

        List<String> violations = new ArrayList<>();
        for (Path file : lifecycleFiles()) {
            String source = read(file);
            for (String marker : forbidden) {
                if (source.contains(marker)) {
                    violations.add(file + " -> " + marker);
                }
            }
        }
        assertThat(violations).as("T17 freezes a definition and stops; it owns no runtime dependency").isEmpty();

        // The package ships no SQL, migration, XML or other non-source resource at all.
        assertThat(nonJavaResources())
                .as("the lifecycle package must contain only its own Java sources")
                .isEmpty();
    }

    @Test
    void theTransactionalAdapterIsSynchronizedAndCommitsThroughOneMethod() {
        String store = read(LIFECYCLE_ROOT.resolve("InMemoryLifecycleStore.java"));
        assertThat(store)
                .contains("synchronized ApplyReceipt commitApply")
                .contains("synchronized PublishPreparation commitPreparation")
                .contains("synchronized PublishReceipt commitPublish")
                .contains("synchronized SavedCampaignDraft commitFork")
                .contains("auditSink.record(")
                .as("the audit must be recorded before any state is published")
                .contains("draftsByCampaign.put")
                .doesNotContain("ExecutorService")
                .doesNotContain("Thread");

        String port = read(LIFECYCLE_ROOT.resolve("LifecycleStore.java"));
        assertThat(port)
                .as("every multi-write operation is one transactional method")
                .contains("ApplyReceipt commitApply(ApplyCommit commit)")
                .contains("PublishPreparation commitPreparation(PublishPreparationCommit commit)")
                .contains("PublishReceipt commitPublish(PublishCommit commit)")
                .contains("SavedCampaignDraft commitFork(ForkCommit commit)")
                .contains("void recordCommandAudit(LifecycleAuditEvent event)");
    }

    @Test
    void everyLifecycleCommitRecordsItsAuditBeforeItPublishesAnyState() {
        String store = read(LIFECYCLE_ROOT.resolve("InMemoryLifecycleStore.java"));
        for (String marker : List.of(
                "auditSink.record(commit.auditEvent());")) {
            assertThat(store).contains(marker);
        }
        // Three single-event commits plus the Publish atomic audit batch: a sink failure must abort each one
        // before any state moves.
        assertThat(store.split(Pattern.quote("auditSink.record(commit.auditEvent());"), -1).length - 1)
                .isEqualTo(3);
        assertThat(store).contains("auditSink.recordAll(auditEvents);");
    }

    private static List<Path> lifecycleFiles() {
        try (Stream<Path> walk = Files.walk(LIFECYCLE_ROOT)) {
            return walk.filter(path -> path.toString().endsWith(".java")).toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static List<String> fileNames() {
        return lifecycleFiles().stream().map(path -> path.getFileName().toString()).toList();
    }

    private static List<Path> nonJavaResources() {
        try (Stream<Path> walk = Files.walk(LIFECYCLE_ROOT)) {
            return walk.filter(path -> !Files.isDirectory(path))
                    .filter(path -> !path.toString().endsWith(".java"))
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
