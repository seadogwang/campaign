package com.company.campaign.runtime;

import com.company.campaign.journey.graph.JourneyNodeKind;
import com.company.campaign.runtime.testsupport.RuntimeJourneyScenario;
import com.company.campaign.runtime.testsupport.RuntimeJourneys;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused T22 phase-B boundary tests: the worker reaches no Provider, ActionExecution, budget or reconciliation
 * contract, and no new record leaks a member, tenant, Campaign or Execution value into its diagnostics.
 */
class RuntimeWorkerBoundaryTest {

    private static final Path REPOSITORY_ROOT = Path.of("").toAbsolutePath();
    private static final Path RUNTIME_SOURCES =
            REPOSITORY_ROOT.resolve("src/main/java/com/company/campaign/runtime");
    private static final String TENANT = "tenant-leak-secret";
    private static final String MEMBER = "member-9f3a-secret";
    private static final Instant AT = Instant.parse("2026-10-03T00:00:00Z");
    private static final ContentHash HASH = RuntimeSupport.seedHash("boundary-evidence");

    @Test
    void runtimeSourcesImportNoProviderActionExecutionBudgetOrReconciliationContract() throws IOException {
        List<String> forbidden = List.of(
                "provider", "actionexecution", "budget", "reconcil", "settlement");
        List<String> violations = new ArrayList<>();
        for (Path source : runtimeSources()) {
            for (String line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
                String trimmed = line.trim().toLowerCase(java.util.Locale.ROOT);
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

        assertThat(violations).as("forbidden phase-B Runtime imports").isEmpty();
    }

    @Test
    void theActionReadyHandoffIsNeverLocallyClaimable() {
        assertThat(RuntimeTaskKind.ACTION_READY.locallyClaimable()).isFalse();
        assertThat(RuntimeTaskKind.START.locallyClaimable()).isTrue();
        assertThat(RuntimeTaskKind.NODE.locallyClaimable()).isTrue();
        assertThat(RuntimeTaskKind.WAIT_WAKE.locallyClaimable()).isTrue();
        assertThat(RuntimeTaskKind.ACTION_READY.reasonCode()).isEqualTo("ACTION_READY");
    }

    @Test
    void everyPhaseBRecordPrintsNoMemberTenantCampaignOrExecutionValue() {
        RuntimeTask leased = RuntimeTask.due(
                        TENANT, "exec-leak-secret", "participant-leak-secret", "node-start",
                        RuntimeTaskKind.NODE, AT)
                .leased("worker-leak-secret", "lease-leak-secret", AT.plusSeconds(30), AT);
        ActionHandoff handoff = ActionHandoff.of(
                TENANT, "exec-leak-secret", "participant-leak-secret", "node-message",
                JourneyNodeKind.MESSAGE, HASH, AT);
        RuntimeCampaignState state = RuntimeCampaignState.of(
                TENANT, "program-leak-secret", "campaign-leak-secret", HASH,
                RuntimeCampaignStatus.PAUSED, 2L, AT);
        RuntimeFactRequest request = RuntimeFactRequest.of(
                TENANT, "campaign-leak-secret", "exec-leak-secret", "participant-leak-secret", "node-c",
                "member.tier", "STRING", 500L);
        RuntimeFactResult fact = RuntimeFactResult.known("member.tier", "STRING", "GOLD", AT);
        NodeAttempt attempt = NodeAttempt.of(
                TENANT, "exec-leak-secret", "participant-leak-secret", "task-leak-secret",
                "node-execution-1", 1, "NODE_COMPLETED", AT, AT);
        ExecutionCounters counters = ExecutionCounters.aggregated(
                TENANT, "exec-leak-secret", 2, 2, 1, 0, 0, 0, 1, 0, 3L);

        for (Object record : List.of(
                leased, handoff, state, request, fact, attempt, counters,
                new RuntimeClaimResult(RuntimeClaimOutcome.CLAIMED, leased),
                new RuntimeClaimResult(RuntimeClaimOutcome.PAUSED, null))) {
            assertThat(record.toString())
                    .as("%s diagnostics", record.getClass().getSimpleName())
                    .doesNotContain("leak-secret")
                    .doesNotContain(MEMBER)
                    .doesNotContain("member.tier");
        }
    }

    @Test
    void theWorkerHoldsNoProviderBudgetOrActionOutcomeState() throws IOException {
        List<String> forbidden = List.of(
                "ProviderPort",
                "ProviderResult",
                "ActionExecutionPort",
                "ActionOutcome",
                "BudgetPort",
                "reserveBudget",
                "finalizeBudget",
                "grantReward(",
                "sendMessage(",
                "reconcile(");
        List<String> violations = new ArrayList<>();
        for (Path source : runtimeSources()) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            for (String code : forbidden) {
                if (text.contains(code)) {
                    violations.add(source.getFileName() + ": " + code);
                }
            }
        }

        assertThat(violations).as("T23 behaviour in the phase-B Runtime layer").isEmpty();
    }

    @Test
    void aWorkerNeverCompletesAnExecutionOverAnUnresolvedMessageOrRewardParticipant() {
        RuntimeJourneyScenario scenario = RuntimeJourneyScenario.of(RuntimeJourneys.message(), MEMBER);
        RuntimeWorker worker = scenario.worker();
        worker.process(worker.claimNext("worker-1").task());
        RuntimeNodeResult outcome = worker.process(worker.claimNext("worker-1").task());

        assertThat(outcome.execution().status()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(outcome.counters().completedCount()).isZero();
        assertThat(outcome.counters().runningCount()).isEqualTo(1);
        assertThat(scenario.store.findActionHandoffs(RuntimeJourneyScenario.TENANT, scenario.executionId))
                .singleElement()
                .satisfies(handoff -> assertThat(handoff.reasonCode())
                        .isEqualTo(ActionHandoff.REASON_ACTION_READY));
    }

    private static List<Path> runtimeSources() throws IOException {
        try (Stream<Path> paths = Files.walk(RUNTIME_SOURCES)) {
            return paths.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
    }
}
