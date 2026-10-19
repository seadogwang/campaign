package com.company.campaign.fullpublish;

import com.company.campaign.lifecycle.PublishPreparationRef;
import com.company.campaign.builder.capability.schedule.ScheduleBasicForm;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The safe outward result of one accepted host Prepare Full Publish.
 *
 * <p>It carries exactly what a later confirmation must present and acknowledge — the full preparation
 * reference, its expiry, the exact accepted T17 preparation hash it extends, the exact Journey and Schedule
 * hashes, the already-safe schedule form and canonical zone, the runtime-availability state and the exact risk
 * codes to acknowledge — and nothing else. It carries no Manifest, Journey node collection, Schedule payload,
 * source Plan content or trusted identifier: the full preparation itself stays server side and is reachable
 * only by hash.
 */
public record PrepareFullPublishResult(
        PublishPreparationRef preparationRef,
        ContentHash basePreparationHash,
        ContentHash journeyHash,
        ContentHash scheduleHash,
        String scheduleForm,
        String scheduleZoneId,
        String runtimeAvailabilityState,
        List<String> requiredRiskCodes,
        Instant createdAt,
        Instant expiresAt
) {

    public PrepareFullPublishResult {
        Objects.requireNonNull(preparationRef, "preparationRef");
        Objects.requireNonNull(basePreparationHash, "basePreparationHash");
        Objects.requireNonNull(journeyHash, "journeyHash");
        Objects.requireNonNull(scheduleHash, "scheduleHash");
        if (ScheduleBasicForm.parse(scheduleForm).isEmpty()) {
            throw new IllegalArgumentException("a full prepare result requires a bounded schedule form");
        }
        if (scheduleZoneId == null || !java.time.ZoneId.of(scheduleZoneId).getId().equals(scheduleZoneId)) {
            throw new IllegalArgumentException("a full prepare result requires a canonical zone");
        }
        if (runtimeAvailabilityState == null || runtimeAvailabilityState.isBlank()) {
            throw new IllegalArgumentException("a full prepare result requires a bounded availability state");
        }
        requiredRiskCodes = List.copyOf(Objects.requireNonNull(requiredRiskCodes, "requiredRiskCodes"));
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    /** The server-stored full preparation identifier a confirmation presents. */
    public String preparationId() {
        return preparationRef.preparationId();
    }

    /** The exact full preparation hash a confirmation must re-present. */
    public ContentHash preparationHash() {
        return preparationRef.preparationHash();
    }

    /** Redacted diagnostics: no owner value is printed. */
    @Override
    public String toString() {
        return "PrepareFullPublishResult[preparationId=" + preparationId()
                + ", form=" + scheduleForm
                + ", zone=" + scheduleZoneId
                + ", risks=" + requiredRiskCodes.size()
                + ", expiresAt=" + expiresAt + ']';
    }
}