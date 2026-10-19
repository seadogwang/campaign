package com.company.campaign.lifecycle;

import com.company.campaign.builder.compiler.CapabilityReadiness;
import com.company.campaign.builder.compiler.ChangeSet;
import com.company.campaign.builder.compiler.FrozenAuthoringPolicy;
import com.company.campaign.builder.compiler.FrozenCapabilityCatalog;
import com.company.campaign.builder.compiler.ResolvedCampaignManifest;
import com.company.campaign.builder.compiler.ResolvedComponent;
import com.company.campaign.builder.tool.SegmentToolIds;
import com.company.campaign.contracts.AgentEnvelope;
import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.contracts.SkillEnvelope;
import com.company.campaign.contracts.ToolEnvelope;
import com.company.campaign.contracts.ToolInput;
import com.company.campaign.evaluation.segment.gate.SegmentReadinessProjection;
import com.company.campaign.security.authorization.DataScopeDecision;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T17 requirement 3 and 5, restated for the whole of T17: a forged client ChangeSet or Manifest has no API
 * path, and Apply, Prepare Publish, Publish and Fork remain absent from every model Tool boundary, so a
 * prompt injection or a forged Tool name cannot reach a lifecycle service.
 *
 * <p>These assertions cover the <em>full</em> T17 boundary. Any Phase-A-only claim that the lifecycle
 * package contains no preparation, publish or fork type is retired: the publish-phase entry points are
 * listed here alongside the Apply ones and are held to exactly the same rules.
 */
class LifecycleToolBoundaryTest {

    private static final Path LIFECYCLE_ROOT =
            Path.of("src", "main", "java", "com", "company", "campaign", "lifecycle");

    @Test
    void theApplyCommandCarriesExactlyAPlanIdAndItsHash() {
        assertThat(ApplyCommand.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("planId", "planHash");
    }

    @Test
    void thePreparePublishCommandCarriesOnlyASavedDraftIdentity() {
        assertThat(PreparePublishCommand.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("campaignId", "draftId", "draftRevision");
    }

    @Test
    void thePublishCommandCarriesOnlyAPreparationItsHashAKeyAndItsConfirmations() {
        assertThat(PublishCommand.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("preparationId", "preparationHash", "idempotencyKey", "confirmations");
    }

    @Test
    void theForkCommandCarriesOnlyAPublishedIdentity() {
        assertThat(ForkPublishedCommand.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("publishedId", "publishedRevision");
    }

    /**
     * The full T17 boundary: no lifecycle entry point — service, command, commit or store port — may accept a
     * mutation document, a model envelope or a trusted readiness/version fact.
     */
    @Test
    void noLifecycleEntryPointAcceptsAChangeSetManifestEnvelopeOrTrustedFact() {
        Set<String> forbidden = Set.of(
                ChangeSet.class.getName(),
                ResolvedCampaignManifest.class.getName(),
                ResolvedComponent.class.getName(),
                ToolEnvelope.class.getName(),
                ToolInput.class.getName(),
                AgentEnvelope.class.getName(),
                SkillEnvelope.class.getName(),
                SegmentReadinessProjection.class.getName(),
                CapabilityReadiness.class.getName(),
                DataScopeDecision.class.getName(),
                FrozenCapabilityCatalog.class.getName(),
                FrozenAuthoringPolicy.class.getName(),
                SchemaVersion.class.getName());

        List<Class<?>> entryPoints = List.of(
                ApplyService.class, PublishPreparationService.class, PublishService.class,
                PublishedForkService.class, LifecycleStore.class,
                ApplyCommand.class, PreparePublishCommand.class, PublishCommand.class,
                ForkPublishedCommand.class,
                ApplyCommit.class, PublishPreparationCommit.class, PublishCommit.class, ForkCommit.class);

        // Only parameters are checked: a lifecycle artifact may hand back the compiler-produced, non-forgeable
        // Manifest it froze, but nothing a caller — or a model — supplies may carry one in.
        List<String> violations = new ArrayList<>();
        for (Class<?> type : entryPoints) {
            for (Method method : type.getMethods()) {
                for (Class<?> parameter : method.getParameterTypes()) {
                    if (forbidden.contains(parameter.getName())) {
                        violations.add(type.getSimpleName() + '#' + method.getName() + " accepts a trusted fact");
                    }
                }
            }
        }
        assertThat(violations)
                .as("no lifecycle entry point may accept a forged mutation document or a trusted fact")
                .isEmpty();
    }

    /** No lifecycle type registers, describes or dispatches a model Tool. */
    @Test
    void theLifecyclePackageRegistersNoModelTool() {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(LIFECYCLE_ROOT)) {
            String source = read(file);
            for (String marker : List.of(
                    "ToolCatalog", "ToolDescriptor", "ToolRegistry", "registerTool", "toolCall",
                    "SegmentToolCatalog", "SegmentToolIds", "ToolSchema", "ToolEnvelope", "ToolInput")) {
                if (source.contains(marker)) {
                    violations.add(file + " -> " + marker);
                }
            }
        }
        assertThat(violations).as("the lifecycle core exposes no model Tool surface").isEmpty();
    }

    @Test
    void noExternalLifecycleCommandFieldCanCarryAForgedPayload() {
        Set<Class<?>> allowed = Set.of(
                String.class, long.class, ContentHash.class, List.class, PublishPreparationRef.class);
        List<Class<?>> commands = List.of(
                ApplyCommand.class, PreparePublishCommand.class, PublishCommand.class,
                ForkPublishedCommand.class);

        List<String> violations = new ArrayList<>();
        for (Class<?> command : commands) {
            for (RecordComponent component : command.getRecordComponents()) {
                if (!allowed.contains(component.getType())) {
                    violations.add(command.getSimpleName() + '.' + component.getName()
                            + " is a " + component.getType().getSimpleName());
                }
            }
        }
        assertThat(violations)
                .as("an external lifecycle command may carry only bounded scalars and hashes")
                .isEmpty();
    }

    @Test
    void everyLifecycleActionNameIsRefusedAsAToolIdentifier() {
        List<String> forged = List.of(
                "apply", "Apply", "cAmPaInG.appLy", "campaign.apply", "save", "save.draft", "SAVE_DRAFT",
                "publish", "campaign.publish", "prepare.publish", "publishPreparation", "confirm.publish",
                "preparePublish", "forkPublished", "fork.published", "campaign.fork", "CONFIRM_PUBLISH",
                "confirmPublish", "publish.confirm", "unpublish", "campaign.unpublish");

        for (String toolId : forged) {
            assertThat(SegmentToolIds.isKnown(toolId))
                    .as("%s must not be a known Tool", toolId).isFalse();
            assertThat(SegmentToolIds.attemptsForbiddenCapability(toolId))
                    .as("%s must be refused as a host-only capability", toolId).isTrue();
        }
    }

    @Test
    void theToolAgentAndSkillBoundariesNeverReachTheLifecyclePackage() {
        List<String> violations = new ArrayList<>();
        Path builder = Path.of("src", "main", "java", "com", "company", "campaign", "builder");
        for (Path root : List.of(builder.resolve("tool"), builder.resolve("agent"), builder.resolve("skill"))) {
            for (Path file : javaFiles(root)) {
                if (read(file).contains("com.company.campaign.lifecycle")) {
                    violations.add(file.toString());
                }
            }
        }
        assertThat(violations).as("no Tool, Agent or Skill may depend on the host-only lifecycle boundary").isEmpty();
    }

    private static List<Path> javaFiles(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(path -> path.toString().endsWith(".java")).toList();
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