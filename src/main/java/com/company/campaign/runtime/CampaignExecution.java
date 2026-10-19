package com.company.campaign.runtime;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.Objects;

/**
 * One revisioned, hash-bound Campaign Execution: exactly one occurrence's worth of runtime work.
 *
 * <p>Its identity is the stable T21 execution key inside its tenant, so a redelivered occurrence can never
 * create a second Execution for the same {@code (tenantId, executionKey)}. It pins the exact frozen full
 * definition, publication revision, Journey and Schedule hashes it runs against, and it freezes
 * {@link #asOf()} to the occurrence's {@code nominalInstant} — never to the instant delivery happened — so a
 * delayed delivery cannot widen the audience by evaluating at a later time. Both instants are kept, because the
 * nominal instant is the evaluation anchor and the due instant is the scheduling evidence.
 *
 * <p>Its {@link #revision()} is the compare-and-set generation of the mutable status and counters; its
 * {@link #contentHash()} covers the whole record including that revision, so a stored Execution always
 * witnesses exactly the state it was read at. It carries no member value and {@link #toString()} prints neither
 * the tenant nor the Campaign identifier.
 */
public record CampaignExecution(
        String formatVersion,
        String executionId,
        String executionKey,
        String tenantId,
        String programId,
        String campaignId,
        String fullDefinitionId,
        ContentHash fullDefinitionHash,
        long publicationRevision,
        ContentHash journeyHash,
        ContentHash scheduleHash,
        Instant nominalInstant,
        Instant dueInstant,
        Instant asOf,
        ExecutionStatus status,
        int candidateCount,
        int enrolledCount,
        long revision,
        String failureCode,
        String failureField,
        Instant createdAt,
        Instant updatedAt,
        ContentHash contentHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public CampaignExecution {
        if (!RuntimeVersions.EXECUTION_FORMAT_VERSION.equals(formatVersion)) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_VERSION_UNSUPPORTED, "formatVersion");
        }
        RuntimeSupport.requireIdentifier(executionId, "executionId");
        RuntimeSupport.requireIdentifier(executionKey, "executionKey");
        RuntimeSupport.requireIdentifier(tenantId, "tenantId");
        RuntimeSupport.requireIdentifier(programId, "programId");
        RuntimeSupport.requireIdentifier(campaignId, "campaignId");
        RuntimeSupport.requireIdentifier(fullDefinitionId, "fullDefinitionId");
        Objects.requireNonNull(fullDefinitionHash, "fullDefinitionHash");
        RuntimeSupport.requirePositive(publicationRevision, "publicationRevision");
        Objects.requireNonNull(journeyHash, "journeyHash");
        Objects.requireNonNull(scheduleHash, "scheduleHash");
        Objects.requireNonNull(nominalInstant, "nominalInstant");
        Objects.requireNonNull(dueInstant, "dueInstant");
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(status, "status");
        if (!asOf.equals(nominalInstant)) {
            // The frozen evaluation instant is the nominal instant, never the delivery instant.
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "asOf");
        }
        RuntimeSupport.requireCount(candidateCount, RuntimeVersions.MAX_AUDIENCE_MEMBERS, "candidateCount");
        RuntimeSupport.requireCount(enrolledCount, RuntimeVersions.MAX_MATERIALIZED_PARTICIPANTS,
                "enrolledCount");
        RuntimeSupport.requireNonNegative(revision, "revision");
        if (failureCode == null) {
            if (failureField != null) {
                throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "failureField");
            }
        } else {
            RuntimeSupport.requireMachineCode(failureCode, "failureCode");
            RuntimeSupport.requireIdentifier(failureField, "failureField");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(contentHash, "contentHash");
        if (!contentHash.equals(CANONICAL_JSON.hash(material(
                formatVersion, executionId, executionKey, tenantId, programId, campaignId, fullDefinitionId,
                fullDefinitionHash, publicationRevision, journeyHash, scheduleHash, nominalInstant, dueInstant,
                asOf, status, candidateCount, enrolledCount, revision, failureCode, failureField, createdAt,
                updatedAt)))) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "contentHash");
        }
    }

    /**
     * Creates the one Execution a {@code DUE} occurrence delivery produces: revision zero, {@code CREATED}, no
     * candidate and no enrolled Participant yet, and {@code asOf} frozen to the occurrence's nominal instant.
     */
    public static CampaignExecution created(
            String executionKey,
            String tenantId,
            String programId,
            String campaignId,
            String fullDefinitionId,
            ContentHash fullDefinitionHash,
            long publicationRevision,
            ContentHash journeyHash,
            ContentHash scheduleHash,
            Instant nominalInstant,
            Instant dueInstant,
            Instant receivedAt) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(executionKey, "executionKey");
        return build(
                RuntimeSupport.derivedId("exec",
                        java.util.Arrays.asList(RuntimeVersions.EXECUTION_FORMAT_VERSION, tenantId,
                                executionKey)),
                executionKey, tenantId, programId, campaignId, fullDefinitionId, fullDefinitionHash,
                publicationRevision, journeyHash, scheduleHash, nominalInstant, dueInstant, nominalInstant,
                ExecutionStatus.CREATED, 0, 0, 0L, null, null, receivedAt, receivedAt);
    }

    /** One explicit Execution state; used by a store transition and by a bounded failure commit. */
    public static CampaignExecution of(
            String executionId,
            String executionKey,
            String tenantId,
            String programId,
            String campaignId,
            String fullDefinitionId,
            ContentHash fullDefinitionHash,
            long publicationRevision,
            ContentHash journeyHash,
            ContentHash scheduleHash,
            Instant nominalInstant,
            Instant dueInstant,
            ExecutionStatus status,
            int candidateCount,
            int enrolledCount,
            long revision,
            String failureCode,
            String failureField,
            Instant createdAt,
            Instant updatedAt) {
        return build(executionId, executionKey, tenantId, programId, campaignId, fullDefinitionId,
                fullDefinitionHash, publicationRevision, journeyHash, scheduleHash, nominalInstant, dueInstant,
                nominalInstant, status, candidateCount, enrolledCount, revision, failureCode, failureField,
                createdAt, updatedAt);
    }

    /** The same Execution at a new revisioned state, preserving every frozen pin and both instants. */
    public CampaignExecution transitioned(
            ExecutionStatus target,
            int newCandidateCount,
            int newEnrolledCount,
            String newFailureCode,
            String newFailureField,
            Instant at) {
        Objects.requireNonNull(target, "target");
        if (!status.reaches(target)) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_TRANSITION_INVALID, "executionStatus",
                    "An Execution transition is not one the frozen state machine allows.");
        }
        return build(executionId, executionKey, tenantId, programId, campaignId, fullDefinitionId,
                fullDefinitionHash, publicationRevision, journeyHash, scheduleHash, nominalInstant, dueInstant,
                asOf, target, newCandidateCount, newEnrolledCount, revision + 1L, newFailureCode,
                newFailureField, createdAt, at);
    }

    /** True when this Execution failed closed. */
    public boolean hasFailed() {
        return failureCode != null;
    }

    /** True when this Execution may still be materialized by a phase-A materialization. */
    public boolean isMaterializable() {
        return status.isMaterializable();
    }

    private static CampaignExecution build(
            String executionId,
            String executionKey,
            String tenantId,
            String programId,
            String campaignId,
            String fullDefinitionId,
            ContentHash fullDefinitionHash,
            long publicationRevision,
            ContentHash journeyHash,
            ContentHash scheduleHash,
            Instant nominalInstant,
            Instant dueInstant,
            Instant asOf,
            ExecutionStatus status,
            int candidateCount,
            int enrolledCount,
            long revision,
            String failureCode,
            String failureField,
            Instant createdAt,
            Instant updatedAt) {
        return new CampaignExecution(
                RuntimeVersions.EXECUTION_FORMAT_VERSION,
                executionId, executionKey, tenantId, programId, campaignId, fullDefinitionId,
                fullDefinitionHash, publicationRevision, journeyHash, scheduleHash, nominalInstant, dueInstant,
                asOf, status, candidateCount, enrolledCount, revision, failureCode, failureField, createdAt,
                updatedAt,
                CANONICAL_JSON.hash(material(
                        RuntimeVersions.EXECUTION_FORMAT_VERSION, executionId, executionKey, tenantId, programId,
                        campaignId, fullDefinitionId, fullDefinitionHash, publicationRevision, journeyHash,
                        scheduleHash, nominalInstant, dueInstant, asOf, status, candidateCount, enrolledCount,
                        revision, failureCode, failureField, createdAt, updatedAt)));
    }

    private static Material material(
            String formatVersion,
            String executionId,
            String executionKey,
            String tenantId,
            String programId,
            String campaignId,
            String fullDefinitionId,
            ContentHash fullDefinitionHash,
            long publicationRevision,
            ContentHash journeyHash,
            ContentHash scheduleHash,
            Instant nominalInstant,
            Instant dueInstant,
            Instant asOf,
            ExecutionStatus status,
            int candidateCount,
            int enrolledCount,
            long revision,
            String failureCode,
            String failureField,
            Instant createdAt,
            Instant updatedAt) {
        return new Material(formatVersion, executionId, executionKey, tenantId, programId, campaignId,
                fullDefinitionId, fullDefinitionHash, publicationRevision, journeyHash, scheduleHash,
                nominalInstant, dueInstant, asOf, status, candidateCount, enrolledCount, revision, failureCode,
                failureField, createdAt, updatedAt);
    }

    /** Redacted diagnostics: no tenant, program or Campaign identifier is printed. */
    @Override
    public String toString() {
        return "CampaignExecution[executionId=" + executionId
                + ", status=" + status
                + ", revision=" + revision
                + ", asOf=" + asOf
                + ", candidates=" + candidateCount
                + ", enrolled=" + enrolledCount
                + ", failureCode=" + failureCode
                + ", contentHash=" + contentHash + ']';
    }

    /** Package-private hash material. */
    record Material(
            String formatVersion,
            String executionId,
            String executionKey,
            String tenantId,
            String programId,
            String campaignId,
            String fullDefinitionId,
            ContentHash fullDefinitionHash,
            long publicationRevision,
            ContentHash journeyHash,
            ContentHash scheduleHash,
            Instant nominalInstant,
            Instant dueInstant,
            Instant asOf,
            ExecutionStatus status,
            int candidateCount,
            int enrolledCount,
            long revision,
            String failureCode,
            String failureField,
            Instant createdAt,
            Instant updatedAt) {
    }
}