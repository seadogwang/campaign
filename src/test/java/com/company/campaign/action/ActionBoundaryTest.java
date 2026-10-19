package com.company.campaign.action;

import com.company.campaign.action.testsupport.ActionScenarios;
import com.company.campaign.runtime.RuntimeTaskKind;
import com.company.campaign.runtime.testsupport.RuntimeJourneyScenario;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Focused T23 boundary tests: the action layer reaches no Builder, Prompt, Skill, Tool, Agent or model state and
 * no Journey Runtime internal, the T22 worker never calls a Provider, and a Provider that lacks either remote
 * guarantee fails closed before it is ever submitted to.
 */
class ActionBoundaryTest {

    private static final Path REPOSITORY_ROOT = Path.of("").toAbsolutePath();
    private static final Path ACTION_SOURCES =
            REPOSITORY_ROOT.resolve("src/main/java/com/company/campaign/action");
    private static final Path RUNTIME_SOURCES =
            REPOSITORY_ROOT.resolve("src/main/java/com/company/campaign/runtime");
    private static final String WORKER = "action-worker";

    @Test
    void theActionLayerReachesNoBuilderLlmOrAgentState() throws IOException {
        List<String> forbidden = List.of(
                "builder.session", "builder.proposal", "builder.agent", "builder.skill", "builder.tool",
                "builder.refine", "modelclient", "model.client", "anthropic", "openai", "langchain",
                "com.company.campaign.dev.");
        List<String> violations = new ArrayList<>();
        for (Path source : sources(ACTION_SOURCES)) {
            for (String line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
                String trimmed = line.trim().toLowerCase(Locale.ROOT);
                if (!trimmed.startsWith("import ")) {
                    continue;
                }
                for (String code : forbidden) {
                    if (trimmed.contains(code)) {
                        violations.add(source.getFileName() + ": " + line.trim());
                    }
                }
            }
        }
        assertThat(violations).as("forbidden T23 external-action imports").isEmpty();
    }

    @Test
    void theRuntimeLayerStillReachesNoProviderBudgetOrActionOutcomeContract() throws IOException {
        List<String> forbidden = List.of(
                "com.company.campaign.action", "providerport", "providerresult", "actionexecutionport",
                "actionoutcome", "budgetport", "reservebudget", "finalizebudget", "grantreward(",
                "sendmessage(", "reconcile(");
        List<String> violations = new ArrayList<>();
        for (Path source : sources(RUNTIME_SOURCES)) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            for (String code : forbidden) {
                if (text.toLowerCase(Locale.ROOT).contains(code)) {
                    violations.add(source.getFileName() + ": " + code);
                }
            }
        }
        assertThat(violations).as("T23 behaviour inside the T22 Runtime layer").isEmpty();
    }

    @Test
    void aRuntimeWorkerStillNeverClaimsTheExternalActionHandoff() {
        assertThat(RuntimeTaskKind.ACTION_READY.locallyClaimable()).isFalse();
        ActionScenarios.Scenario scenario = ActionScenarios.message();
        // The T22 worker has already produced the handoff and can claim nothing further.
        assertThat(scenario.runtime.worker().claimNext("t22-worker").claimed()).isFalse();
        assertThat(scenario.store.actionCount()).isZero();
        // Only the external-action layer consumes it, and only through its own services.
        assertThat(scenario.worker.ingest(RuntimeJourneyScenario.TENANT, WORKER)).isEqualTo(1);
        assertThat(scenario.store.actionCount()).isEqualTo(1);
    }

    @Test
    void aProviderWithoutBusinessKeyIdempotencyFailsClosedBeforeAnySubmission() {
        ActionScenarios.Scenario scenario = ActionScenarios.message();
        scenario.messages.withoutBusinessKeyIdempotency();
        ActionOutcome outcome = scenario.step(WORKER);

        assertThat(outcome.disposition()).isEqualTo(ActionDisposition.DEFINITELY_FAILED);
        assertThat(outcome.action().status()).isEqualTo(ActionStatus.DEFINITELY_FAILED);
        assertThat(scenario.messages.submissionCount(scenario.idempotencyKey())).isZero();
        assertThat(scenario.messages.effectCount(scenario.idempotencyKey())).isZero();
    }

    @Test
    void aProviderWithoutReliableReconciliationFailsClosedBeforeAnySubmission() {
        ActionScenarios.Scenario scenario = ActionScenarios.message();
        scenario.messages.withoutReliableQueryByKey();
        ActionOutcome outcome = scenario.step(WORKER);

        assertThat(outcome.disposition()).isEqualTo(ActionDisposition.DEFINITELY_FAILED);
        assertThat(scenario.messages.submissionCount(scenario.idempotencyKey())).isZero();
    }

    @Test
    void aProviderWhoseCapabilityEvidenceThrowsOrNamesAnotherOwnerFailsClosed() {
        ActionScenarios.Scenario throwing = ActionScenarios.message();
        throwing.messages.withThrowingCapability();
        assertThat(throwing.step(WORKER).disposition()).isEqualTo(ActionDisposition.DEFINITELY_FAILED);
        assertThat(throwing.messages.submissionCount(throwing.idempotencyKey())).isZero();

        ActionScenarios.Scenario crossOwner = ActionScenarios.message();
        crossOwner.messages.withCapabilityOwner("tenant-somewhere-else");
        assertThat(crossOwner.step(WORKER).disposition()).isEqualTo(ActionDisposition.DEFINITELY_FAILED);
        assertThat(crossOwner.messages.submissionCount(crossOwner.idempotencyKey())).isZero();
    }

    @Test
    void aSimulatedProviderIsExplicitNonProductionEvidenceAndCanNeverSatisfyLiveReady() {
        ActionScenarios.Scenario scenario = ActionScenarios.message();
        ProviderCapability capability = scenario.messages.capability(RuntimeJourneyScenario.TENANT);
        assertThat(capability.production()).isFalse();
        assertThat(capability.fixtureEvidence()).isTrue();
        assertThat(capability.satisfiesLiveReady()).isFalse();
        assertThat(ActionVersions.PRODUCTION_GATE_EVIDENCE).isFalse();
        assertThat(scenario.messages.toString()).doesNotContain("credential").doesNotContain("token");
    }

    @Test
    void anUnresolvableRecipientFailsClosedBeforeAnythingIsReserved() {
        ActionScenarios.Scenario scenario = ActionScenarios.messageWithUnresolvableRecipient();
        assertThatThrownBy(() -> scenario.service.prepare(scenario.handoff()))
                .isInstanceOf(ActionBoundaryException.class)
                .hasFieldOrPropertyWithValue("code", ActionErrorCodes.ACTION_RECIPIENT_UNRESOLVED);
        assertThat(scenario.store.actionCount()).isZero();
    }

    @Test
    void aThrowingRecipientResolverFailsClosedWithTheSameBoundedCode() {
        ActionScenarios.Scenario scenario = ActionScenarios.message();
        scenario.recipients.throwing();
        assertThatThrownBy(() -> scenario.service.prepare(scenario.handoff()))
                .isInstanceOf(ActionBoundaryException.class)
                .hasFieldOrPropertyWithValue("code", ActionErrorCodes.ACTION_RECIPIENT_UNRESOLVED);
    }

    @Test
    void noT23MutationIsRegisteredAsAModelCallableTool() throws IOException {
        for (Path source : sources(ACTION_SOURCES)) {
            String text = Files.readString(source, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            assertThat(text)
                    .as("%s must not register a Tool", source.getFileName())
                    .doesNotContain("toolregistry")
                    .doesNotContain("tooldefinition")
                    .doesNotContain("registertool");
        }
    }

    private static List<Path> sources(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
    }
}