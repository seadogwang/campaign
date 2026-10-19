package com.company.campaign.runtime;

import com.company.campaign.builder.capability.governance.GovernanceLimitVersions;
import com.company.campaign.builder.compiler.ResolvedCampaignManifest;
import com.company.campaign.builder.compiler.ResolvedComponent;
import com.company.campaign.fullpublish.FullPublishedDefinition;
import com.company.campaign.lifecycle.PublishedCampaignDefinition;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The Runtime materialization boundary: it turns one accepted Execution into an atomic, fixed-{@code asOf}
 * audience with Participants and their first task.
 *
 * <p>The frozen evaluation instant is the Execution's own {@code asOf}, which is the occurrence's nominal
 * instant. A delayed delivery therefore materializes the audience the occurrence was <em>due</em> for, never
 * the audience that exists at processing time.
 *
 * <p>Every pin is re-proved from frozen material: the full definition is resolved by name and verified against
 * the Execution's own hash, its base published definition is resolved and verified against the checksum the full
 * definition pinned, the audience Segment component and the exact {@code governance.limit/1.0} declaration are
 * resolved by stable identity from the frozen base Manifest, and the recipient limit comes only from that exact
 * declaration. Nothing is re-resolved from a mutable catalog, a Prompt, a Skill, a Tool or an LLM.
 *
 * <p>One transaction commits everything or nothing. A stale, timed-out, refused, malformed, incomplete or
 * over-limit answer produces a {@code FAILED} Execution and zero snapshot, enrollment, Participant and task
 * writes; the member set is never truncated. A zero-candidate audience and an audience whose every candidate is
 * already enrolled both complete successfully with no Participant and no task.
 *
 * <p>{@link #toString()} prints no owner value.
 */
public final class RuntimeMaterializationService {

    /** The bounded field name a failed materialization records for an audience-evidence failure. */
    public static final String FIELD_AUDIENCE_EVIDENCE = "AUDIENCE_EVIDENCE";

    /** The bounded field name a failed materialization records for a recipient-limit failure. */
    public static final String FIELD_RECIPIENT_LIMIT = "RECIPIENT_LIMIT";

    private final RuntimeDefinitionLookupPort definitions;
    private final AudienceMaterializationPort audience;
    private final RuntimeStore store;
    private final Clock clock;
    private final Duration freshnessPolicy;

    public RuntimeMaterializationService(
            RuntimeDefinitionLookupPort definitions,
            AudienceMaterializationPort audience,
            RuntimeStore store,
            Clock clock,
            Duration freshnessPolicy) {
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.audience = Objects.requireNonNull(audience, "audience");
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(freshnessPolicy, "freshnessPolicy");
        if (freshnessPolicy.compareTo(RuntimeVersions.MIN_FRESHNESS_AGE) < 0
                || freshnessPolicy.compareTo(RuntimeVersions.MAX_FRESHNESS_AGE) > 0) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "freshnessPolicy");
        }
        this.freshnessPolicy = freshnessPolicy;
    }

    // ------------------------------------------------------------------ entry point

    /**
     * Materializes the frozen audience of one accepted Execution.
     *
     * <p>The tenant and execution key are lookup keys, never authority: the stored Execution is the authority
     * for whose work this is, and every related resource is re-proved to belong to the same tenant before it is
     * read. An Execution that was already materialized is refused rather than re-materialized.
     */
    public MaterializationResult materialize(String tenantId, String executionKey) {
        RuntimeSupport.requireIdentifier(tenantId, "tenantId");
        RuntimeSupport.requireIdentifier(executionKey, "executionKey");
        CampaignExecution execution = store.findExecution(tenantId, executionKey)
                .orElseThrow(() -> new RuntimeBoundaryException(
                        RuntimeErrorCodes.RUNTIME_SOURCE_NOT_FOUND, "execution",
                        "No accepted Execution is stored under this tenant and execution key."));
        if (!execution.isMaterializable()) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_EXECUTION_NOT_MATERIALIZABLE, "executionStatus",
                    "This Execution is not in a state phase A may materialize.");
        }
        Instant at = clock.instant();
        FrozenPins pins;
        try {
            pins = loadFrozenPins(execution);
        } catch (RuntimeBoundaryException frozenPinFailure) {
            // A frozen configuration the Execution can never satisfy fails it closed permanently, with a
            // bounded reason, instead of leaving it retryable forever.
            return commitFailure(execution, 0L, frozenPinFailure.code(), at);
        }
        AudienceMaterializationRequest request = request(execution, pins);

        AudienceMaterializationResult evidence;
        try {
            evidence = Objects.requireNonNull(audience.materialize(request), "audienceResult");
        } catch (java.lang.RuntimeException hostFailure) {
            // A host that cannot answer at all is a bounded query failure, never an unrestricted message.
            return commitFailure(execution, 0L, RuntimeErrorCodes.RUNTIME_AUDIENCE_QUERY_FAILED, at);
        }

        String violation = validateEvidence(request, evidence, tenantId, execution.asOf());
        if (violation == null && evidence.candidateCount() > request.effectiveLimit()) {
            violation = RuntimeErrorCodes.RUNTIME_AUDIENCE_OVER_LIMIT;
        }
        if (violation == null && !evidence.outcome().isComplete()) {
            violation = evidence.outcome().failure().code();
        }
        if (violation != null) {
            return commitFailure(execution, evidence.candidateCount(), violation, at);
        }
        return commitSuccess(execution, pins, evidence, at);
    }

    // ------------------------------------------------------------------ frozen pins

    private FrozenPins loadFrozenPins(CampaignExecution execution) {
        FullPublishedDefinition definition = definitions.findFullDefinition(execution.fullDefinitionId())
                .orElseThrow(() -> new RuntimeBoundaryException(
                        RuntimeErrorCodes.RUNTIME_DEFINITION_NOT_FOUND, "fullDefinitionId",
                        "The frozen full definition this Execution names is not held by the host."));
        if (!definition.contentHash().equals(execution.fullDefinitionHash())
                || !definition.tenantId().equals(execution.tenantId())
                || !definition.campaignId().equals(execution.campaignId())
                || !definition.journeyHash().equals(execution.journeyHash())
                || !definition.scheduleHash().equals(execution.scheduleHash())) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_DEFINITION_HASH_MISMATCH, "fullDefinitionBinding",
                    "The frozen full definition does not witness exactly this Execution's pins.");
        }
        PublishedCampaignDefinition base = definitions.findBaseDefinition(definition.basePublishedId())
                .orElseThrow(() -> new RuntimeBoundaryException(
                        RuntimeErrorCodes.RUNTIME_BASE_DEFINITION_NOT_FOUND, "basePublishedId",
                        "The frozen base definition this full definition binds is not held by the host."));
        if (!base.contentChecksum().equals(definition.baseDefinitionChecksum())
                || !base.identity().equals(definition.baseIdentity())
                || !base.tenantId().equals(definition.tenantId())
                || !base.campaignId().equals(definition.campaignId())) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_BASE_DEFINITION_HASH_MISMATCH, "baseDefinitionBinding",
                    "The frozen base definition does not witness exactly the checksum the full definition "
                            + "pinned.");
        }
        ResolvedCampaignManifest manifest = base.manifest();
        if (!manifest.manifestHash().equals(definition.manifestHash())) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_BINDING_MISMATCH, "manifestHash",
                    "The frozen base Manifest is not the Manifest the full definition froze.");
        }
        ResolvedComponent segment = requireSingleComponent(
                manifest, RuntimeVersions.AUDIENCE_SLOT_CODE, RuntimeErrorCodes.RUNTIME_SEGMENT_PIN_MISSING,
                "audienceSegment");
        ResolvedComponent limit = requireSingleComponent(
                manifest, RuntimeVersions.GOVERNANCE_LIMIT_CAPABILITY, RuntimeErrorCodes.RUNTIME_LIMIT_MISSING,
                "governanceLimit");
        if (!RuntimeVersions.GOVERNANCE_LIMIT_CONTRACT_VERSION.equals(limit.contractVersion())) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_LIMIT_MISSING, "governanceLimitContract",
                    "The frozen Manifest declares no exact governance.limit/1.0 component.");
        }
        return new FrozenPins(definition, segment, resolveMaxRecipients(limit));
    }

    /**
     * The one frozen component matching a slot code or a capability code, read by stable identity.
     *
     * <p>It never selects by array position, never falls back to the first match among several, and never
     * re-resolves a mutable pointer: a missing or ambiguous component fails closed.
     */
    private static ResolvedComponent requireSingleComponent(
            ResolvedCampaignManifest manifest, String selector, String code, String field) {
        ResolvedComponent match = null;
        for (ResolvedComponent component : manifest.components()) {
            boolean matches = selector.equals(component.slot().code())
                    || selector.equals(component.capabilityCode());
            if (!matches) {
                continue;
            }
            if (match != null) {
                throw new RuntimeBoundaryException(
                        code, field, "The frozen Manifest declares at most one component of this kind.");
            }
            match = component;
        }
        if (match == null) {
            throw new RuntimeBoundaryException(
                    code, field, "The frozen Manifest declares no component of this kind.");
        }
        return match;
    }

    /**
     * Resolves {@code maxRecipients} only from the exact frozen {@code governance.limit/1.0} declaration.
     *
     * <p>A missing, non-numeric, fractional, non-positive or out-of-policy-bound value fails closed. No default,
     * no inherited setting and no caller-supplied limit is ever substituted.
     */
    private static long resolveMaxRecipients(ResolvedComponent limit) {
        Object declared = limit.value().fields().get(RuntimeVersions.GOVERNANCE_LIMIT_MAX_RECIPIENTS);
        if (!(declared instanceof Number number)) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_LIMIT_INVALID, "maxRecipients",
                    "The frozen governance limit declares no bounded maxRecipients value.");
        }
        long resolved;
        try {
            resolved = new BigDecimal(number.toString()).longValueExact();
        } catch (NumberFormatException | ArithmeticException malformed) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_LIMIT_INVALID, "maxRecipients",
                    "The frozen governance limit declares a value that is not an exact whole number.");
        }
        if (resolved < 1 || resolved > GovernanceLimitVersions.MAX_RECIPIENTS_POLICY_BOUND) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_LIMIT_INVALID, "maxRecipients",
                    "The frozen governance limit declares a value outside its own policy bound.");
        }
        return resolved;
    }

    // ------------------------------------------------------------------ request

    private AudienceMaterializationRequest request(CampaignExecution execution, FrozenPins pins) {
        ResolvedComponent segment = pins.segment();
        return AudienceMaterializationRequest.of(
                execution.tenantId(), pins.definition().dataScopeId(), execution.campaignId(),
                execution.fullDefinitionId(), execution.fullDefinitionHash(), pins.definition().manifestHash(),
                segment.logicalId(), segment.capabilityCode(), segment.contractVersion(),
                segment.value().contentHash(), execution.asOf(), freshnessPolicy.toSeconds(),
                pins.maxRecipients());
    }

    // ------------------------------------------------------------------ evidence validation

    /**
     * Re-proves that one host audience answer agrees with the request and with itself.
     *
     * <p>Returns the bounded failure code a violation maps to, or {@code null} when the answer is either a
     * consistent complete set or a well-formed failure. Cross-tenant evidence, a watermark after its own
     * observation, a policy the host did not answer under, a duplicate member identity and a claimed count that
     * disagrees with the returned set all fail closed; nothing is repaired and nothing is dropped. The frozen
     * freshness policy is enforced here rather than merely echoed, so a host that calls stale data complete
     * still fails the Execution closed.
     */
    private static String validateEvidence(
            AudienceMaterializationRequest request,
            AudienceMaterializationResult evidence,
            String tenantId,
            Instant asOf) {
        if (!evidence.evidenceTenantId().equals(tenantId)
                || evidence.freshnessMaxAgeSeconds() != request.freshnessMaxAgeSeconds()
                || evidence.watermark().isAfter(evidence.observedAt())) {
            return RuntimeErrorCodes.RUNTIME_AUDIENCE_EVIDENCE_INVALID;
        }
        if (!evidence.outcome().isComplete()) {
            return null;
        }
        Set<String> distinct = new LinkedHashSet<>(evidence.members());
        if (distinct.size() != evidence.members().size()
                || evidence.members().size() != evidence.candidateCount()) {
            return RuntimeErrorCodes.RUNTIME_AUDIENCE_EVIDENCE_INVALID;
        }
        Instant freshnessDeadline = evidence.watermark().plusSeconds(request.freshnessMaxAgeSeconds());
        if (asOf.isAfter(freshnessDeadline)) {
            return RuntimeErrorCodes.RUNTIME_AUDIENCE_STALE;
        }
        return null;
    }

    // ------------------------------------------------------------------ failure commit

    /**
     * Commits a bounded failure: the Execution becomes {@code FAILED} with the evidence it observed and the
     * transaction writes no snapshot member, no enrollment, no Participant and no task.
     */
    private MaterializationResult commitFailure(
            CampaignExecution execution, long candidateCount, String failureCode, Instant at) {
        int boundedCandidates = (int) Math.min(Math.max(candidateCount, 0L),
                RuntimeVersions.MAX_AUDIENCE_MEMBERS);
        String failureField = RuntimeErrorCodes.RUNTIME_AUDIENCE_OVER_LIMIT.equals(failureCode)
                ? FIELD_RECIPIENT_LIMIT
                : FIELD_AUDIENCE_EVIDENCE;
        CampaignExecution failed = execution.transitioned(
                ExecutionStatus.FAILED, boundedCandidates, 0, failureCode, failureField, at);
        ExecutionCounters counters =
                ExecutionCounters.failed(execution.tenantId(), execution.executionId(), boundedCandidates);
        RuntimeAuditRecord audit = audit(
                execution, RuntimeAuditRecord.REASON_MATERIALIZATION_FAILED, failed.revision(), at);
        MaterializationCommit commit = new MaterializationCommit(
                execution.tenantId(), execution.executionKey(), execution.revision(), failed, List.of(),
                List.of(), List.of(), List.of(), counters, List.of(audit));
        RuntimeStore.MaterializationCommitResult stored = store.commitMaterialization(commit);
        return new MaterializationResult(stored.execution(), stored.counters(), stored.snapshotMemberCount(),
                stored.enrollmentCount(), stored.participantCount(), stored.taskCount());
    }

    // ------------------------------------------------------------------ success commit

    /**
     * Applies MVP re-entry and commits the accepted audience atomically.
     *
     * <p>Snapshot members record the <em>whole</em> accepted audience, so the Execution's evidence stays complete
     * even when most candidates were already enrolled. Enrollments, Participants and START tasks exist only for
     * the members this materialization newly enrolled, so re-entry is a deterministic exclusion rather than an
     * error and never a second Participant.
     */
    private MaterializationResult commitSuccess(
            CampaignExecution execution,
            FrozenPins pins,
            AudienceMaterializationResult evidence,
            Instant at) {
        List<String> members = new ArrayList<>(new LinkedHashSet<>(evidence.members()));
        members.sort(String::compareTo);

        List<AudienceSnapshotMember> snapshotMembers = new ArrayList<>(members.size());
        for (String member : members) {
            snapshotMembers.add(AudienceSnapshotMember.of(
                    execution.tenantId(), execution.executionId(), member, evidence.evidenceHash(),
                    evidence.observedAt()));
        }

        String startNodeLogicalId = pins.definition().journey().startNodeLogicalId();
        List<CampaignMemberEnrollment> enrollments = new ArrayList<>();
        List<JourneyParticipant> participants = new ArrayList<>();
        List<RuntimeTask> tasks = new ArrayList<>();
        int excluded = 0;
        for (String member : members) {
            if (store.findEnrollment(execution.tenantId(), execution.campaignId(), member).isPresent()) {
                excluded++;
                continue;
            }
            enrollments.add(CampaignMemberEnrollment.of(
                    execution.tenantId(), execution.campaignId(), member, execution.executionId(),
                    execution.fullDefinitionId(), execution.publicationRevision(), at));
            JourneyParticipant participant = JourneyParticipant.created(
                    execution.tenantId(), execution.executionId(), execution.campaignId(),
                    execution.fullDefinitionId(), execution.fullDefinitionHash(), execution.journeyHash(),
                    member, startNodeLogicalId, at);
            participants.add(participant);
            tasks.add(RuntimeTask.due(
                    execution.tenantId(), execution.executionId(), participant.participantId(),
                    startNodeLogicalId, RuntimeTaskKind.START, at));
        }

        int enrolled = participants.size();
        List<RuntimeAuditRecord> audits = new ArrayList<>();
        if (enrolled > 0) {
            audits.add(audit(execution, RuntimeAuditRecord.REASON_MATERIALIZATION_COMPLETED, 1L, at));
        } else if (evidence.candidateCount() == 0) {
            audits.add(audit(execution, RuntimeAuditRecord.REASON_MATERIALIZATION_EMPTY, 1L, at));
        } else {
            audits.add(audit(execution, RuntimeAuditRecord.REASON_MATERIALIZATION_ENROLLED, 1L, at));
        }
        if (excluded > 0) {
            audits.add(audit(execution, RuntimeAuditRecord.REASON_ENROLLMENT_EXCLUDED, 1L, at));
        }

        // A zero-Participant materialization is complete immediately; any unresolved Participant keeps the
        // Execution running, because an Execution must never complete while a Participant is runnable.
        ExecutionStatus target = enrolled == 0 ? ExecutionStatus.COMPLETED : ExecutionStatus.RUNNING;
        CampaignExecution materialized = execution.transitioned(
                target, evidence.members().size(), enrolled, null, null, at);
        ExecutionCounters counters = ExecutionCounters.materialized(
                execution.tenantId(), execution.executionId(), evidence.members().size(), enrolled, 0L);
        MaterializationCommit commit = new MaterializationCommit(
                execution.tenantId(), execution.executionKey(), execution.revision(), materialized,
                snapshotMembers, enrollments, participants, tasks, counters, audits);
        RuntimeStore.MaterializationCommitResult stored = store.commitMaterialization(commit);
        return new MaterializationResult(stored.execution(), stored.counters(), stored.snapshotMemberCount(),
                stored.enrollmentCount(), stored.participantCount(), stored.taskCount());
    }

    private static RuntimeAuditRecord audit(
            CampaignExecution execution, String reasonCode, long afterRevision, Instant at) {
        return RuntimeAuditRecord.of(
                RuntimeSupport.derivedId("audit", List.of(
                        reasonCode, execution.tenantId(), execution.executionId())),
                execution.tenantId(), reasonCode, RuntimeIngressService.SUBJECT_EXECUTION,
                execution.executionId(), execution.executionKey(), execution.revision(), afterRevision, at);
    }

    /** The frozen pins one materialization rests on. */
    private record FrozenPins(
            FullPublishedDefinition definition,
            ResolvedComponent segment,
            long maxRecipients) {
    }

    /** Redacted diagnostics: the service holds no owner value. */
    @Override
    public String toString() {
        return "RuntimeMaterializationService[]";
    }
}