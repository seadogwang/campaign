package com.company.campaign.action;

import com.company.campaign.action.testsupport.ActionScenarios;
import com.company.campaign.runtime.RuntimeAuditRecord;
import com.company.campaign.runtime.testsupport.RuntimeJourneyScenario;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Focused T23 security tests: identical identities in another tenant can never cross-read, cross-claim,
 * reconcile, retry or reserve, absent and cross-owner resources answer with one non-disclosing not-found, and
 * no diagnostic ever exposes a member, recipient or Provider value.
 */
class ActionTenancyAndRedactionTest {

    private static final String OTHER_TENANT = "tenant-other";
    private static final Instant AT = Instant.parse("2026-10-08T00:00:00Z");

    @Test
    void anotherTenantCanNeverReadClaimOrReconcileAnAction() {
        ActionScenarios.Scenario scenario = ActionScenarios.message();
        ActionPreparation preparation = scenario.service.prepare(scenario.handoff());
        String actionId = preparation.action().actionId();

        assertThat(scenario.store.findActionById(OTHER_TENANT, actionId)).isEmpty();
        assertThat(scenario.store.findActionById(RuntimeJourneyScenario.TENANT, actionId)).isPresent();
        assertThat(scenario.store.findActionByKey(OTHER_TENANT, scenario.idempotencyKey())).isEmpty();
        assertThat(scenario.store.findActionTask(
                OTHER_TENANT, preparation.submissionTask().taskId())).isEmpty();
        assertThat(scenario.store.findReservation(OTHER_TENANT, actionId)).isEmpty();
        assertThat(scenario.store.findActionAttempts(OTHER_TENANT, actionId)).isEmpty();
        assertThat(scenario.store.findAudits(OTHER_TENANT)).isEmpty();

        ActionClaimResult claim = scenario.service.claimNext(OTHER_TENANT, "worker-other");
        assertThat(claim.outcome()).isEqualTo(ActionClaimOutcome.NONE_DUE);
        assertThat(scenario.messages.submissionCount(scenario.idempotencyKey())).isZero();
    }

    @Test
    void aCrossOwnerActionAndAnAbsentActionAnswerWithTheSameNonDisclosingFailure() {
        ActionScenarios.Scenario scenario = ActionScenarios.message();
        ActionPreparation preparation = scenario.service.prepare(scenario.handoff());
        String actionId = preparation.action().actionId();
        long revision = preparation.action().revision();

        ActionBoundaryException crossOwner = (ActionBoundaryException) org.assertj.core.api.Assertions
                .catchThrowable(() -> scenario.service.reconcile(ActionOperatorCommand.of(
                        OTHER_TENANT, actionId, "operator-other", true, revision, "RECONCILE")));
        ActionBoundaryException absent = (ActionBoundaryException) org.assertj.core.api.Assertions
                .catchThrowable(() -> scenario.service.reconcile(ActionOperatorCommand.of(
                        RuntimeJourneyScenario.TENANT, "act-absent-identity", "operator-1", true, revision,
                        "RECONCILE")));

        assertThat(crossOwner.code()).isEqualTo(ActionErrorCodes.ACTION_NOT_FOUND);
        assertThat(absent.code()).isEqualTo(ActionErrorCodes.ACTION_NOT_FOUND);
        assertThat(crossOwner.getMessage()).isEqualTo(absent.getMessage());
        assertThat(crossOwner.toString()).doesNotContain(actionId);
    }

    @Test
    void aHostOnlyOperationRequiresTheOperateAuthorization() {
        ActionScenarios.Scenario scenario = ActionScenarios.message();
        ActionPreparation preparation = scenario.service.prepare(scenario.handoff());
        assertThatThrownBy(() -> scenario.service.reconcile(ActionOperatorCommand.of(
                RuntimeJourneyScenario.TENANT, preparation.action().actionId(), "operator-1", false,
                preparation.action().revision(), "RECONCILE")))
                .isInstanceOf(ActionBoundaryException.class)
                .hasFieldOrPropertyWithValue("code", ActionErrorCodes.ACTION_OPERATION_FORBIDDEN);
        assertThatThrownBy(() -> scenario.service.safeRetry(ActionOperatorCommand.of(
                RuntimeJourneyScenario.TENANT, preparation.action().actionId(), "operator-1", false,
                preparation.action().revision(), "RETRY")))
                .isInstanceOf(ActionBoundaryException.class)
                .hasFieldOrPropertyWithValue("code", ActionErrorCodes.ACTION_OPERATION_FORBIDDEN);
    }

    @Test
    void everyActionRecordPrintsNoMemberTenantRecipientOrProviderValue() {
        String member = "member-secret-9f3a";
        String tenant = "tenant-secret-77";
        String recipient = "recipient-secret-ref";
        String providerReference = "prvref-secret-value";

        ActionExecution action = ActionExecution.reserved(
                tenant, "exec-secret", "ptp-secret", "node-m", ActionKind.MESSAGE,
                "tenant-secret-77:ptp-secret:node-m:MESSAGE", ActionSupport.seedHash("request"), AT)
                .submitted(AT);
        ActionTask task = ActionTask.due(
                tenant, action.actionId(), action.idempotencyKey(), ActionTaskKind.SUBMISSION, 0, AT)
                .leased("worker-secret", "lease-secret", AT.plusSeconds(30), AT);
        ActionAttempt attempt = ActionAttempt.of(
                tenant, action.actionId(), action.idempotencyKey(), ActionAttemptKind.SUBMISSION, 1,
                ProviderResult.succeeded(action.idempotencyKey(), providerReference, AT, "PROVIDER_APPLIED"),
                "PROVIDER_APPLIED", AT, AT);
        BudgetReservation reservation = BudgetReservation.held(
                tenant, "exec-secret", action.actionId(), action.idempotencyKey(), 1, "CNY",
                new java.math.BigDecimal("1.00"), AT);
        ActionRecipientTarget target = new ActionRecipientTarget(recipient, ActionSupport.seedHash("evidence"));
        ActionOutcome outcome = new ActionOutcome(
                ActionDisposition.SUCCEEDED, action, task, attempt, reservation, null,
                ProviderOutcome.SUCCEEDED, null, false);

        for (Object record : List.of(
                action, task, attempt, reservation, target, outcome,
                ActionIdempotencyRecord.of(tenant, action.idempotencyKey(), action.actionId(),
                        ActionSupport.seedHash("frozen"), ActionSupport.seedHash("request"), AT),
                ActionResultCounters.empty(tenant, "exec-secret"),
                ActionAuditRecord.of("actaudit-1", tenant, ActionAuditRecord.REASON_ACTION_RESERVED, "ACTION",
                        action.actionId(), 0L, 0L, AT))) {
            assertThat(record.toString())
                    .as("%s diagnostics", record.getClass().getSimpleName())
                    .doesNotContain(member)
                    .doesNotContain(tenant)
                    .doesNotContain(recipient)
                    .doesNotContain(providerReference)
                    .doesNotContain("lease-secret");
        }
        assertThat(RuntimeAuditRecord.of(
                "audit-1", tenant, RuntimeAuditRecord.REASON_ACTION_NODE_COMPLETED, "TASK", "task-1",
                "exec-key-1", 1L, 2L, AT).toString())
                .doesNotContain(tenant);
    }
}