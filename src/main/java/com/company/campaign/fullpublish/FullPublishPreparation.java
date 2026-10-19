package com.company.campaign.fullpublish;

import com.company.campaign.builder.compiler.PublishedIdentity;
import com.company.campaign.journey.compiler.JourneyManifest;
import com.company.campaign.lifecycle.PublishPreparation;
import com.company.campaign.lifecycle.PublishPreparationRef;
import com.company.campaign.lifecycle.PublishPreparationState;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The expiring, server-stored authority one explicit host Prepare Full Publish produces.
 *
 * <p>It extends the accepted T17 lifecycle without weakening it: it names the exact accepted
 * {@link PublishPreparation} it extends, and it re-binds that preparation by its own hash rather than by
 * copying its fields, so the whole accepted preparation material — its Draft, Plan, Manifest and Draft hashes,
 * its catalog, policy, capability, DataScope, readiness and reference evidence — is covered exactly once and
 * cannot drift.
 *
 * <p>On top of that it freezes the exact T20 {@link JourneyManifest} and its content, topology and source
 * Manifest hashes with its compiler and runtime schema versions; the exact T21 {@link CompiledSchedule} and
 * its hash; and the exact host {@link RuntimeAvailabilityPin} the publication rests on. Every heavyweight
 * object is bound by hash in {@link #preparationHash()}'s material, so a stored preparation whose bytes do not
 * match its own hash is not representable.
 *
 * <p>It is not a second editable truth and not a cache of the Draft: it carries no raw Manifest bytes, no
 * ChangeSet, no caller-supplied payload, no occurrence and no readiness fact a caller could declare.
 * {@link #toString()} prints no tenant, program, user, DataScope, Campaign or Draft value.
 */
public record FullPublishPreparation(
        String formatVersion,
        String preparationId,
        PublishPreparation basePreparation,
        JourneyManifest journey,
        CompiledSchedule schedule,
        RuntimeAvailabilityPin runtimeAvailability,
        List<String> requiredRiskCodes,
        Instant createdAt,
        Instant expiresAt,
        ContentHash preparationHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public FullPublishPreparation {
        if (!FullPublishVersions.FULL_PREPARATION_FORMAT_VERSION.equals(formatVersion)) {
            throw FullPublishSupport.invalid(
                    FullPublishErrorCodes.FULL_PREPARATION_INVALID, "formatVersion");
        }
        FullPublishSupport.requireIdentifier(preparationId, "preparationId");
        Objects.requireNonNull(basePreparation, "basePreparation");
        Objects.requireNonNull(journey, "journey");
        Objects.requireNonNull(schedule, "schedule");
        Objects.requireNonNull(runtimeAvailability, "runtimeAvailability");
        requiredRiskCodes = FullPublishSupport.canonicalRiskCodes(requiredRiskCodes, "requiredRiskCodes");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(preparationHash, "preparationHash");
        FullPublishSupport.requireBoundedLifetime(createdAt, expiresAt, "preparationLifetime");

        // The Journey must descend from exactly the Manifest the accepted T17 preparation froze, and must
        // declare the exact supported versions. Anything else means the two artifacts describe different
        // Campaigns, and the full preparation would misstate what it authorizes.
        if (!journey.sourceManifestHash().equals(basePreparation.manifestHash())
                || !FullPublishVersions.JOURNEY_FORMAT_VERSION.equals(journey.journeyFormatVersion())
                || !FullPublishVersions.JOURNEY_COMPILER_VERSION.equals(journey.compilerVersion())
                || !FullPublishVersions.RUNTIME_SCHEMA_VERSION.equals(journey.runtimeSchemaVersion())) {
            throw FullPublishSupport.invalid(
                    FullPublishErrorCodes.FULL_JOURNEY_MISMATCH, "journeyBinding");
        }
        // The Schedule must descend from the same Manifest and the same compiled Journey.
        if (!schedule.sourceManifestHash().equals(basePreparation.manifestHash())
                || !schedule.journeyHash().equals(journey.contentHash())
                || !FullPublishVersions.SCHEDULE_FORMAT_VERSION.equals(schedule.scheduleFormatVersion())
                || !FullPublishVersions.SCHEDULE_COMPILER_VERSION.equals(schedule.compilerVersion())
                || !FullPublishVersions.RUNTIME_SCHEMA_VERSION.equals(schedule.runtimeSchemaVersion())) {
            throw FullPublishSupport.invalid(
                    FullPublishErrorCodes.FULL_SCHEDULE_MISMATCH, "scheduleBinding");
        }
        // The runtime-availability pin must answer for exactly the runtime the Journey and Schedule declare.
        if (!runtimeAvailability.available()
                || !journey.runtimeSchemaVersion().equals(runtimeAvailability.runtimeSchemaVersion())) {
            throw FullPublishSupport.invalid(
                    FullPublishErrorCodes.FULL_RUNTIME_UNAVAILABLE, "runtimeAvailabilityBinding");
        }
        if (!preparationHash.equals(CANONICAL_JSON.hash(material(
                formatVersion, preparationId, basePreparation.preparationId(),
                basePreparation.preparationHash(), journey.contentHash(), journey.journeyFormatVersion(),
                journey.compilerVersion(), journey.runtimeSchemaVersion(), journey.sourceManifestHash(),
                journey.topologyHash(), schedule.scheduleHash(), schedule.scheduleFormatVersion(),
                schedule.compilerVersion(), runtimeAvailability.formatVersion(),
                runtimeAvailability.runtimeSchemaVersion(), runtimeAvailability.pinHash(), requiredRiskCodes,
                createdAt, expiresAt)))) {
            throw FullPublishSupport.invalid(
                    FullPublishErrorCodes.FULL_PREPARATION_INVALID, "preparationHash");
        }
    }

    /** Builds a full preparation and stamps it with its own canonical hash. */
    public static FullPublishPreparation of(
            String preparationId,
            PublishPreparation basePreparation,
            JourneyManifest journey,
            CompiledSchedule schedule,
            RuntimeAvailabilityPin runtimeAvailability,
            List<String> requiredRiskCodes,
            Instant createdAt,
            Instant expiresAt) {
        Objects.requireNonNull(basePreparation, "basePreparation");
        Objects.requireNonNull(journey, "journey");
        Objects.requireNonNull(schedule, "schedule");
        Objects.requireNonNull(runtimeAvailability, "runtimeAvailability");
        List<String> risks = FullPublishSupport.canonicalRiskCodes(requiredRiskCodes, "requiredRiskCodes");
        return new FullPublishPreparation(
                FullPublishVersions.FULL_PREPARATION_FORMAT_VERSION,
                preparationId, basePreparation, journey, schedule, runtimeAvailability, risks, createdAt,
                expiresAt,
                CANONICAL_JSON.hash(material(
                        FullPublishVersions.FULL_PREPARATION_FORMAT_VERSION,
                        preparationId, basePreparation.preparationId(), basePreparation.preparationHash(),
                        journey.contentHash(), journey.journeyFormatVersion(), journey.compilerVersion(),
                        journey.runtimeSchemaVersion(), journey.sourceManifestHash(), journey.topologyHash(),
                        schedule.scheduleHash(), schedule.scheduleFormatVersion(), schedule.compilerVersion(),
                        runtimeAvailability.formatVersion(), runtimeAvailability.runtimeSchemaVersion(),
                        runtimeAvailability.pinHash(), risks, createdAt, expiresAt)));
    }

    /** The host-only reference a full publication confirmation presents for this preparation. */
    public PublishPreparationRef ref() {
        return new PublishPreparationRef(preparationId, preparationHash);
    }

    /** The accepted T17 preparation identifier this full preparation extends. */
    public String basePreparationId() {
        return basePreparation.preparationId();
    }

    /** The accepted T17 preparation hash this full preparation extends. */
    public ContentHash basePreparationHash() {
        return basePreparation.preparationHash();
    }

    /** The exact compiled Journey content hash this preparation froze. */
    public ContentHash journeyHash() {
        return journey.contentHash();
    }

    /** The exact compiled schedule hash this preparation froze. */
    public ContentHash scheduleHash() {
        return schedule.scheduleHash();
    }

    /** The tenant the frozen base preparation belongs to. */
    public String tenantId() {
        return basePreparation.tenantId();
    }

    /** The program the frozen base preparation belongs to. */
    public String programId() {
        return basePreparation.programId();
    }

    /** The user the frozen base preparation belongs to. */
    public String userId() {
        return basePreparation.userId();
    }

    /** The DataScope the frozen base preparation belongs to. */
    public String dataScopeId() {
        return basePreparation.dataScopeId();
    }

    /** The Campaign the frozen base preparation addresses. */
    public String campaignId() {
        return basePreparation.campaignId();
    }

    /** The Campaign revision the frozen base preparation addresses. */
    public long campaignRevision() {
        return basePreparation.campaignRevision();
    }

    /** The saved Draft identifier the frozen base preparation addresses. */
    public String draftId() {
        return basePreparation.draftId();
    }

    /** The saved Draft revision the frozen base preparation addresses. */
    public long draftRevision() {
        return basePreparation.draftRevision();
    }

    /** The immutable fork base the frozen base preparation recorded. */
    public PublishedIdentity forkBase() {
        return basePreparation.forkBase();
    }

    /** The declared lifetime of this full preparation. */
    public Duration lifetime() {
        return Duration.between(createdAt, expiresAt);
    }

    /**
     * The state decidable from the full preparation's own durable facts and expiry alone.
     *
     * <p>A consumed preparation is consumed forever, so consumption outranks expiry; a preparation at or past
     * its expiry instant is expired. Anything else is {@link PublishPreparationState#PREPARED}, and a
     * confirmation may still downgrade it to {@link PublishPreparationState#STALE} once it has re-read the host
     * state this preparation froze.
     */
    public PublishPreparationState durableState(Instant now, boolean consumed) {
        Objects.requireNonNull(now, "now");
        if (consumed) {
            return PublishPreparationState.CONSUMED;
        }
        return expiresAt.isAfter(now) ? PublishPreparationState.PREPARED : PublishPreparationState.EXPIRED;
    }

    /** True when the two risk-code collections are exactly equal as sets, order and repetition aside. */
    public boolean requiresExactly(Set<String> confirmations) {
        Objects.requireNonNull(confirmations, "confirmations");
        return confirmations.size() == requiredRiskCodes.size()
                && new LinkedHashSet<>(requiredRiskCodes).equals(new LinkedHashSet<>(confirmations));
    }

    /** The exact source Plan the confirmation must re-load and re-prove. */
    public String planId() {
        return basePreparation.planId();
    }

    /**
     * The hash material without the hash itself.
     *
     * <p>It is a static form because a record's compact constructor runs before its fields are assigned.
     */
    private static Material material(
            String formatVersion,
            String preparationId,
            String basePreparationId,
            ContentHash basePreparationHash,
            ContentHash journeyHash,
            String journeyFormatVersion,
            String journeyCompilerVersion,
            String journeyRuntimeSchemaVersion,
            ContentHash journeySourceManifestHash,
            ContentHash journeyTopologyHash,
            ContentHash scheduleHash,
            String scheduleFormatVersion,
            String scheduleCompilerVersion,
            String runtimeAvailabilityFormatVersion,
            String runtimeAvailabilityRuntimeSchemaVersion,
            ContentHash runtimeAvailabilityPinHash,
            List<String> requiredRiskCodes,
            Instant createdAt,
            Instant expiresAt) {
        return new Material(formatVersion, preparationId, basePreparationId, basePreparationHash, journeyHash,
                journeyFormatVersion, journeyCompilerVersion, journeyRuntimeSchemaVersion,
                journeySourceManifestHash, journeyTopologyHash, scheduleHash, scheduleFormatVersion,
                scheduleCompilerVersion, runtimeAvailabilityFormatVersion,
                runtimeAvailabilityRuntimeSchemaVersion, runtimeAvailabilityPinHash, requiredRiskCodes,
                createdAt, expiresAt);
    }

    /** Redacted diagnostics: no tenant, program, user, DataScope, Campaign or Draft value is printed. */
    @Override
    public String toString() {
        return "FullPublishPreparation[preparationId=" + preparationId
                + ", basePreparationId=" + basePreparationId()
                + ", draftRevision=" + draftRevision()
                + ", journeyHash=" + journeyHash()
                + ", scheduleHash=" + scheduleHash()
                + ", risks=" + requiredRiskCodes.size()
                + ", expiresAt=" + expiresAt
                + ", preparationHash=" + preparationHash + ']';
    }

    /** Package-private hash material; the heavyweight frozen objects are bound by their own hashes. */
    record Material(
            String formatVersion,
            String preparationId,
            String basePreparationId,
            ContentHash basePreparationHash,
            ContentHash journeyHash,
            String journeyFormatVersion,
            String journeyCompilerVersion,
            String journeyRuntimeSchemaVersion,
            ContentHash journeySourceManifestHash,
            ContentHash journeyTopologyHash,
            ContentHash scheduleHash,
            String scheduleFormatVersion,
            String scheduleCompilerVersion,
            String runtimeAvailabilityFormatVersion,
            String runtimeAvailabilityRuntimeSchemaVersion,
            ContentHash runtimeAvailabilityPinHash,
            List<String> requiredRiskCodes,
            Instant createdAt,
            Instant expiresAt) {
    }
}