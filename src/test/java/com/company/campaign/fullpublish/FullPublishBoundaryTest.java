package com.company.campaign.fullpublish;

import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T21 section 7 and 8: the full-publication and scheduling layer is deterministic, framework-independent and
 * side-effect free, it exposes no model Tool, and no external command can carry a forged trusted fact.
 */
class FullPublishBoundaryTest {

    private static final Path FULL_PUBLISH_ROOT =
            Path.of("src", "main", "java", "com", "company", "campaign", "fullpublish");

    private static final List<String> FORBIDDEN_TEXT = List.of(
            "Instant.now", "LocalDate.now", "LocalDateTime.now", "OffsetDateTime.now", "ZonedDateTime.now",
            "Clock.system", "ZoneId.systemDefault", "Locale.getDefault", "UUID.randomUUID", "Math.random",
            "new Random(", "ThreadLocal", "System.getenv", "System.getProperty", "System.currentTimeMillis",
            "System.nanoTime", "System.out", "System.err", "Class.forName", "getDeclaredMethod",
            "getDeclaredField", "setAccessible", "Method.invoke", "ProcessBuilder", "Runtime.getRuntime",
            "DriverManager", "jdbc:", "DataSource", "ScriptEngine", "Thread.sleep", "Files.write", "Files.read");

    private static final List<String> FORBIDDEN_TYPE_NAMES = List.of(
            "Controller", "Repository", "Entity", "Dao", "Table", "Schema", "Migration", "Scheduler",
            "Worker", "Client", "Tool", "Agent", "Skill");

    // ------------------------------------------------------------------ determinism and purity

    @Test
    void theNewBoundariesReadNoClockRandomGlobalContextReflectionOrFile() {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(FULL_PUBLISH_ROOT)) {
            String source = read(file);
            for (String forbidden : FORBIDDEN_TEXT) {
                if (source.contains(forbidden)) {
                    violations.add(file + " -> " + forbidden);
                }
            }
        }
        assertThat(violations).as("no ambient or non-deterministic source may reach T21").isEmpty();
    }

    // ------------------------------------------------------------------ side-effect boundary

    @Test
    void theNewBoundariesReachNoExecutionParticipantProviderOrRuntimeSideEffect() {
        List<String> violations = new ArrayList<>();
        // The forbidden markers are real dependency symptoms, not words a comment might legitimately mention.
        List<String> markers = List.of(
                "@RestController", "@Controller", "@Service", "@Component", "@Repository", "@Entity", "@Table",
                "@Transactional", "@Scheduled", "EntityManager", "JdbcTemplate", "PersistenceContext",
                "SessionFactory", "CREATE TABLE", "create table",
                "ToolCatalog", "ToolDescriptor", "ToolRegistry", "registerTool", "toolCall", "SegmentToolIds");
        for (Path file : javaFiles(FULL_PUBLISH_ROOT)) {
            String source = read(file);
            for (String marker : markers) {
                if (source.contains(marker)) {
                    violations.add(file + " -> " + marker);
                }
            }
        }
        assertThat(violations).as("T21 commits bounded records and stops").isEmpty();
    }

    @Test
    void theNewPackagesShipNoControllerRepositoryEntityMigrationWorkerOrProviderType() {
        List<String> names = javaFiles(FULL_PUBLISH_ROOT).stream()
                .map(path -> path.getFileName().toString())
                .toList();
        assertThat(names).noneMatch(name -> FORBIDDEN_TYPE_NAMES.stream().anyMatch(name::contains));
        assertThat(names)
                .as("the schedule and full-publication layers ship only their own Java sources")
                .allMatch(name -> name.endsWith(".java"));
    }

    @Test
    void noModelToolAgentOrSkillReachesTheNewBoundaries() {
        List<String> violations = new ArrayList<>();
        Path builder = Path.of("src", "main", "java", "com", "company", "campaign", "builder");
        for (Path root : List.of(builder.resolve("tool"), builder.resolve("agent"), builder.resolve("skill"))) {
            for (Path file : javaFiles(root)) {
                String source = read(file);
                if (source.contains("com.company.campaign.fullpublish")
                        || source.contains("com.company.campaign.schedule")) {
                    violations.add(file.toString());
                }
            }
        }
        assertThat(violations).as("no Tool, Agent or Skill may reach a host-only mutation").isEmpty();
    }

    @Test
    void noFullPublicationOrSchedulingMutationIsRegisteredAsATool() {
        List<String> names = javaFiles(FULL_PUBLISH_ROOT).stream()
                .map(path -> path.getFileName().toString())
                .toList();
        assertThat(names).noneMatch(name -> name.contains("Tool") || name.contains("Catalog"));
    }

    // ------------------------------------------------------------------ external commands

    @Test
    void everyExternalCommandCarriesOnlyBoundedScalarsHashesAndTimeValues() {
        Set<Class<?>> allowed = Set.of(
                String.class, long.class, Instant.class, ContentHash.class, List.class);

        assertCommandCarriesOnly(FullPublishCommand.class, allowed);
        assertCommandCarriesOnly(PrepareFullPublishCommand.class, allowed);
        assertCommandCarriesOnly(ScheduleDueCommand.class, allowed);
        assertCommandCarriesOnly(ScheduleStateCommand.class, allowed);
    }

    @Test
    void theFullPublicationCommandCarriesOnlyAPreparationItsHashAKeyAndItsConfirmations() {
        assertThat(java.util.Arrays.stream(FullPublishCommand.class.getRecordComponents())
                .map(RecordComponent::getName).toList())
                .containsExactly("preparationId", "preparationHash", "idempotencyKey", "confirmations");
        assertThat(java.util.Arrays.stream(PrepareFullPublishCommand.class.getRecordComponents())
                .map(RecordComponent::getName).toList())
                .containsExactly("basePreparationId", "basePreparationHash");
    }

    @Test
    void noCommandAcceptsAReadinessDecisionRuntimePinRouteOrVersionFact() {
        Set<String> forbidden = Set.of(
                "com.company.campaign.journey.compiler.JourneyManifest",
                "com.company.campaign.journey.graph.JourneyTopology",
                "com.company.campaign.builder.capability.composition.T19PackIndex",
                "CompiledSchedule",
                "com.company.campaign.fullpublish.RuntimeAvailabilityPin",
                "com.company.campaign.evaluation.segment.gate.SegmentReadinessProjection",
                "com.company.campaign.builder.compiler.CampaignPlan",
                "com.company.campaign.builder.compiler.ResolvedCampaignManifest");

        List<Class<?>> commands = List.of(
                FullPublishCommand.class, PrepareFullPublishCommand.class, ScheduleDueCommand.class,
                ScheduleStateCommand.class);
        List<String> violations = new ArrayList<>();
        for (Class<?> command : commands) {
            for (RecordComponent component : command.getRecordComponents()) {
                if (forbidden.contains(component.getType().getName())) {
                    violations.add(command.getSimpleName() + '.' + component.getName());
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    // ------------------------------------------------------------------ internal requests

    @Test
    void thePureScheduleRequestsCarryNoRepositoryOrHostHandle() {
        Set<String> allowed = Set.of(
                String.class.getName(), long.class.getName(), Instant.class.getName(),
                ContentHash.class.getName(), List.class.getName(),
                com.company.campaign.builder.compiler.CapabilityPayload.class.getName(),
                CompiledSchedule.class.getName());

        assertRecordCarriesOnly(ScheduleCompileRequest.class, allowed);
        assertRecordCarriesOnly(OccurrencePlanningRequest.class, allowed);
    }

    // ------------------------------------------------------------------ helpers

    private static void assertCommandCarriesOnly(Class<?> command, Set<Class<?>> allowed) {
        Set<String> names = new java.util.LinkedHashSet<>();
        for (Class<?> type : allowed) {
            names.add(type.getName());
        }
        assertRecordCarriesOnly(command, names);
    }

    private static void assertRecordCarriesOnly(Class<?> record, Set<String> allowed) {
        for (RecordComponent component : record.getRecordComponents()) {
            assertThat(allowed)
                    .as("%s.%s must be a bounded scalar, hash, time or frozen value",
                            record.getSimpleName(), component.getName())
                    .contains(component.getType().getName());
        }
    }

    private static List<Path> javaFiles(Path... roots) {
        List<Path> files = new ArrayList<>();
        for (Path root : roots) {
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