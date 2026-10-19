package com.company.campaign.fullpublish;

import com.company.campaign.lifecycle.LifecycleAction;
import com.company.campaign.lifecycle.LifecycleAuditEvent;
import com.company.campaign.lifecycle.LifecycleDisposition;
import com.company.campaign.lifecycle.LifecycleErrorCodes;
import com.company.campaign.lifecycle.LifecycleException;
import com.company.campaign.lifecycle.LifecycleSupport;
import com.company.campaign.security.audit.AuditResourceFingerprint;
import com.company.campaign.security.authorization.PermissionDeniedException;
import com.company.campaign.security.authorization.ResourceNotFoundException;
import com.company.campaign.security.context.CampaignPermission;
import com.company.campaign.security.context.TrustedExecutionContext;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The host-only durable scheduling boundary: the operations a host applies to one Campaign's active published
 * schedule.
 *
 * <p>It provides exactly four outcomes and nothing else:
 *
 * <ul>
 *   <li>plan and commit the due occurrences of the active schedule, idempotently by execution key, using an
 *       explicit evaluation instant and no system clock. Every committed decision — {@code DUE} and
 *       {@code SKIPPED} alike — is emitted history the planner is given, so an already-committed date is never
 *       planned, reported or audited a second time, and a plan that produces nothing writes nothing;</li>
 *   <li>pause the active schedule so no new {@code DUE} occurrence work is committed;</li>
 *   <li>resume it without replaying every missed {@code DAILY} date — the accepted most-recent-due catch-up
 *       applies instead;</li>
 *   <li>leave already-committed occurrence work exactly as it is: it stays bound to the frozen version it was
 *       planned from and is never rewritten, reassigned or re-planned onto a newer version.</li>
 * </ul>
 *
 * <p>Every operation requires the exact {@code campaign.operate} permission and the exact tenant, program,
 * user and DataScope ownership of the server-resolved active projection. An absent projection and one outside
 * the caller's boundary share the identical non-disclosing not-found failure, so neither can be enumerated.
 *
 * <p>It materializes no Execution, no Participant, no enrollment, no audience row, no runtime task and no
 * provider call: it commits bounded occurrence decisions and one safe audit event, and stops there.
 */
public final class ScheduleOperationService {

    /** The audit reason code recorded for one committed due-occurrence plan. */
    public static final String PLANNED_REASON = "SCHEDULE_OCCURRENCES_PLANNED";

    /** The audit reason code recorded for one accepted pause. */
    public static final String PAUSED_REASON = "SCHEDULE_PAUSED";

    /** The audit reason code recorded for one accepted resume. */
    public static final String RESUMED_REASON = "SCHEDULE_RESUMED";

    private final FullPublicationStore publicationStore;
    private final ScheduleStore scheduleStore;
    private final Clock clock;
    private final ScheduleOccurrencePlanner planner = new ScheduleOccurrencePlanner();

    /** Creates the one host-only scheduling boundary that holds every collaborator. */
    public ScheduleOperationService(
            FullPublicationStore publicationStore, ScheduleStore scheduleStore, Clock clock) {
        this.publicationStore = Objects.requireNonNull(publicationStore, "publicationStore");
        this.scheduleStore = Objects.requireNonNull(scheduleStore, "scheduleStore");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Plans and commits the due occurrences of the Campaign's active schedule, idempotently by execution key.
     *
     * <p>An exact repeat of a plan whose occurrences are all already committed is a no-op: it writes no
     * occurrence and no audit and returns the stored state, while still re-proving the caller's expected
     * schedule-state revision.
     *
     * @throws LifecycleException for a bounded scheduling rejection
     * @throws ResourceNotFoundException when the Campaign has no active schedule inside the caller's boundary
     * @throws PermissionDeniedException when the caller is inside the boundary but lacks {@code campaign.operate}
     */
    public ScheduleDueResult planDue(ScheduleDueCommand command, TrustedExecutionContext context) {
        requireContext(context);
        if (command == null || FullPublishSupport.isBlank(command.campaignId())
                || command.expectedStateRevision() < 0 || command.asOf() == null) {
            throw invalidCommand();
        }
        CampaignScheduleState state = requireOwnedState(command.campaignId(), context);
        if (!state.planningAllowed()) {
            throw new LifecycleException(
                    LifecycleErrorCodes.LIFECYCLE_SCHEDULE_PAUSED,
                    "schedulePaused",
                    "The Campaign's active schedule is paused and commits no new due occurrence work.");
        }
        Instant now = clock.instant();
        if (command.asOf().isAfter(now)) {
            // A caller cannot bring work forward by naming an evaluation instant in the future.
            throw invalidCommand();
        }

        FullPublishedDefinition definition = publicationStore
                .findFullDefinition(state.fullDefinitionId())
                .orElseThrow(() -> stale("fullDefinition"));
        if (!definition.contentHash().equals(state.fullDefinitionHash())
                || !definition.journeyHash().equals(state.journeyHash())
                || !definition.scheduleHash().equals(state.scheduleHash())) {
            throw stale("fullDefinitionBinding");
        }

        // Every committed occurrence decision is emitted history, both DUE and SKIPPED. Passing only the DUE keys
        // would let an already-committed SKIPPED date be planned — and reported — again on every invocation,
        // even though the store would deduplicate the write.
        List<String> emitted = new ArrayList<>();
        for (ScheduledOccurrenceRecord record : scheduleStore.findCommittedOccurrences(command.campaignId())) {
            emitted.add(record.executionKey());
        }

        OccurrencePlan plan = planner.plan(new OccurrencePlanningRequest(
                definition.fullDefinitionId(), definition.publicationRevision(), definition.contentHash(),
                state.schedule(), definition.publishedAt(), command.asOf(), emitted));

        if (plan.occurrences().isEmpty()) {
            // An exact no-op plan is not an operation: it writes no occurrence and no audit, and returns the
            // state that is already stored. The compare-and-set is still re-proved against the current
            // projection, so a caller that named a stale revision is refused rather than told it succeeded.
            requireCurrentStateRevision(command.campaignId(), command.expectedStateRevision());
            return new ScheduleDueResult(
                    command.campaignId(), state.stateRevision(), command.asOf(), List.copyOf(emitted),
                    List.of(), List.of());
        }

        List<ScheduledOccurrenceRecord> records = new ArrayList<>(plan.occurrences().size());
        List<String> dueKeys = new ArrayList<>();
        List<String> skippedKeys = new ArrayList<>();
        for (ScheduledOccurrence occurrence : plan.occurrences()) {
            records.add(ScheduledOccurrenceRecord.of(
                    occurrence, command.campaignId(), definition.fullDefinitionId(), now));
            if (occurrence.decision().due()) {
                dueKeys.add(occurrence.executionKey());
            } else {
                skippedKeys.add(occurrence.executionKey());
            }
        }

        LifecycleAuditEvent audit = LifecycleAuditEvent.of(
                LifecycleAction.SCHEDULE_OPERATE, LifecycleDisposition.APPLIED, PLANNED_REASON, now, context,
                fingerprintOf(state), null, definition.contentHash());

        scheduleStore.commitScheduleOperation(
                command.campaignId(), command.expectedStateRevision(), null, records, audit);

        return new ScheduleDueResult(
                command.campaignId(), state.stateRevision(), command.asOf(), List.copyOf(emitted),
                List.copyOf(dueKeys), List.copyOf(skippedKeys));
    }

    /**
     * Pauses the Campaign's active schedule so no new {@code DUE} occurrence work is committed.
     *
     * <p>Pausing an already-paused schedule is idempotent: the stored projection is returned unchanged.
     */
    public ScheduleStateResult pause(ScheduleStateCommand command, TrustedExecutionContext context) {
        return transition(command, context, ScheduleStatus.PAUSED, PAUSED_REASON);
    }

    /**
     * Resumes the Campaign's active schedule.
     *
     * <p>It deliberately does not replay every missed {@code DAILY} date: the accepted most-recent-due catch-up
     * applies on the next due-occurrence plan, so a resumed Campaign does not flood the schedule with a backlog.
     */
    public ScheduleStateResult resume(ScheduleStateCommand command, TrustedExecutionContext context) {
        return transition(command, context, ScheduleStatus.ACTIVE, RESUMED_REASON);
    }

    private ScheduleStateResult transition(
            ScheduleStateCommand command,
            TrustedExecutionContext context,
            ScheduleStatus target,
            String reason) {
        requireContext(context);
        if (command == null || FullPublishSupport.isBlank(command.campaignId())
                || command.expectedStateRevision() < 0) {
            throw invalidCommand();
        }
        CampaignScheduleState state = requireOwnedState(command.campaignId(), context);
        Instant now = clock.instant();
        if (state.status() == target) {
            // An already-satisfied transition writes nothing rather than advancing a revision nobody changed.
            return new ScheduleStateResult(
                    state.campaignId(), state.stateRevision(), state.status().name(), state.updatedAt());
        }
        CampaignScheduleState replacement = state.withStatus(target, now);
        LifecycleAuditEvent audit = LifecycleAuditEvent.of(
                LifecycleAction.SCHEDULE_OPERATE, LifecycleDisposition.APPLIED, reason, now, context,
                fingerprintOf(state), null, state.scheduleHash());
        CampaignScheduleState stored = scheduleStore.commitScheduleOperation(
                        command.campaignId(), command.expectedStateRevision(), replacement, List.of(), audit)
                .orElseThrow(() -> stale("scheduleState"));
        return new ScheduleStateResult(
                stored.campaignId(), stored.stateRevision(), stored.status().name(), stored.updatedAt());
    }

    // ------------------------------------------------------------------ evaluation steps

    private static void requireContext(TrustedExecutionContext context) {
        if (context == null) {
            throw LifecycleSupport.missingTrustedContext();
        }
        if (!context.hasPermission(CampaignPermission.CAMPAIGN_OPERATE)) {
            // The accepted authorization model has no resource kind for scheduling, so the permission is
            // proved directly from the trusted membership — the same trusted source the authorization core
            // reads — and a denial names no misleading resource kind.
            throw new PermissionDeniedException(CampaignPermission.CAMPAIGN_OPERATE, null);
        }
    }

    /**
     * Re-loads the active projection and proves it belongs to exactly this caller.
     *
     * <p>An absent projection, one owned by another tenant, program or user and one outside the caller's
     * DataScope all share the identical non-disclosing not-found failure.
     */
    private CampaignScheduleState requireOwnedState(
            String campaignId, TrustedExecutionContext context) {
        CampaignScheduleState state = scheduleStore.findScheduleState(campaignId)
                .orElseThrow(ResourceNotFoundException::new);
        if (!state.tenantId().equals(context.tenantId())
                || !state.programId().equals(context.programId())
                || !state.userId().equals(context.userId())
                || !state.dataScopeId().equals(context.dataScopeId())) {
            throw new ResourceNotFoundException();
        }
        return state;
    }

    private static AuditResourceFingerprint fingerprintOf(CampaignScheduleState state) {
        return LifecycleSupport.fingerprintOf(state.campaignId(), state.tenantId(), state.programId());
    }

    /**
     * Re-proves the compare-and-set for a plan that writes nothing, exactly as the store would for one that does.
     *
     * <p>A no-op plan skips the commit, so without this a caller naming a stale revision would be told its
     * operation succeeded against state it never read. The failure is the same stable schedule-state conflict
     * the transactional write raises.
     */
    private void requireCurrentStateRevision(String campaignId, long expectedStateRevision) {
        CampaignScheduleState current = scheduleStore.findScheduleState(campaignId).orElse(null);
        long currentRevision = current == null ? 0L : current.stateRevision();
        if (currentRevision != expectedStateRevision) {
            throw stale("stateRevision");
        }
    }

    private static LifecycleException invalidCommand() {
        return new LifecycleException(
                FullPublishErrorCodes.FULL_COMMAND_INVALID,
                "command",
                "A scheduling operation requires a Campaign, its exact state revision and a bounded instant.");
    }

    private static LifecycleException stale(String invariant) {
        return new LifecycleException(
                LifecycleErrorCodes.LIFECYCLE_SCHEDULE_STATE_CONFLICT,
                invariant,
                "The Campaign's active schedule no longer matches the state this operation read.");
    }
}