package com.company.campaign.fullpublish;

import com.company.campaign.builder.compiler.PublishedIdentity;
import com.company.campaign.journey.compiler.JourneyManifest;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.Objects;

/**
 * The immutable full publication definition one accepted host full publication freezes.
 *
 * <p>It contains or exactly binds everything the publication committed: the accepted T17
 * {@link PublishedIdentity} — the published identifier, revision and content checksum of the base definition —
 * the exact Manifest hash, the exact T20 {@link JourneyManifest} and its content hash, the exact T21
 * {@link CompiledSchedule} and its hash, the exact host runtime-availability pin, the owner and Campaign pins,
 * the publication revision and instant, and its own complete canonical content hash.
 *
 * <p>Published bytes never change. There is no setter, no builder, no merge and no in-place edit: pausing or
 * resuming a Campaign and replacing its active version with a newer publication are separate projections and
 * events, never mutations of this definition.
 *
 * <p>Construction re-proves that the Journey and the Schedule both descend from exactly {@link #manifestHash()},
 * that they descend from each other, that the runtime-availability pin answers for exactly their runtime
 * schema, and that {@link #contentHash()} is the canonical hash of the definition material, so a definition
 * whose bytes do not match its own hash is not representable. {@link #toString()} prints no owner value.
 */
public record FullPublishedDefinition(
        String formatVersion,
        String fullDefinitionId,
        long publicationRevision,
        String tenantId,
        String programId,
        String userId,
        String dataScopeId,
        String campaignId,
        long campaignRevision,
        PublishedIdentity baseIdentity,
        ContentHash manifestHash,
        JourneyManifest journey,
        CompiledSchedule schedule,
        RuntimeAvailabilityPin runtimeAvailability,
        Instant publishedAt,
        ContentHash contentHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public FullPublishedDefinition {
        if (!FullPublishVersions.FULL_DEFINITION_FORMAT_VERSION.equals(formatVersion)) {
            throw FullPublishSupport.invalid(FullPublishErrorCodes.FULL_DEFINITION_INVALID, "formatVersion");
        }
        FullPublishSupport.requireIdentifier(fullDefinitionId, "fullDefinitionId");
        if (publicationRevision < 1) {
            throw FullPublishSupport.invalid(FullPublishErrorCodes.FULL_DEFINITION_INVALID, "publicationRevision");
        }
        FullPublishSupport.requireIdentifier(tenantId, "tenantId");
        FullPublishSupport.requireIdentifier(programId, "programId");
        FullPublishSupport.requireIdentifier(userId, "userId");
        FullPublishSupport.requireIdentifier(dataScopeId, "dataScopeId");
        FullPublishSupport.requireIdentifier(campaignId, "campaignId");
        if (campaignRevision < 1) {
            throw FullPublishSupport.invalid(FullPublishErrorCodes.FULL_DEFINITION_INVALID, "campaignRevision");
        }
        Objects.requireNonNull(baseIdentity, "baseIdentity");
        Objects.requireNonNull(manifestHash, "manifestHash");
        Objects.requireNonNull(journey, "journey");
        Objects.requireNonNull(schedule, "schedule");
        Objects.requireNonNull(runtimeAvailability, "runtimeAvailability");
        Objects.requireNonNull(publishedAt, "publishedAt");
        Objects.requireNonNull(contentHash, "contentHash");

        // The T20 Journey source Manifest hash must exactly equal the definition Manifest hash, and the
        // Schedule must descend from that same Manifest and that same compiled Journey. A definition that
        // bound two different Campaigns would be unverifiable, so it is not representable.
        if (!journey.sourceManifestHash().equals(manifestHash)
                || !FullPublishVersions.JOURNEY_FORMAT_VERSION.equals(journey.journeyFormatVersion())
                || !FullPublishVersions.JOURNEY_COMPILER_VERSION.equals(journey.compilerVersion())
                || !FullPublishVersions.RUNTIME_SCHEMA_VERSION.equals(journey.runtimeSchemaVersion())
                || !schedule.sourceManifestHash().equals(manifestHash)
                || !schedule.journeyHash().equals(journey.contentHash())
                || !FullPublishVersions.SCHEDULE_FORMAT_VERSION.equals(schedule.scheduleFormatVersion())
                || !FullPublishVersions.SCHEDULE_COMPILER_VERSION.equals(schedule.compilerVersion())
                || !runtimeAvailability.available()
                || !runtimeAvailability.runtimeSchemaVersion().equals(journey.runtimeSchemaVersion())) {
            throw FullPublishSupport.invalid(FullPublishErrorCodes.FULL_DEFINITION_INVALID, "versionOrSourceBinding");
        }
        if (!contentHash.equals(CANONICAL_JSON.hash(material(
                formatVersion, fullDefinitionId, publicationRevision, tenantId, programId, userId, dataScopeId,
                campaignId, campaignRevision, baseIdentity, manifestHash, journey.contentHash(),
                journey.journeyFormatVersion(),
                journey.compilerVersion(), journey.runtimeSchemaVersion(), journey.topologyHash(),
                schedule.scheduleHash(), runtimeAvailability.pinHash(), publishedAt)))) {
            throw FullPublishSupport.invalid(FullPublishErrorCodes.FULL_DEFINITION_INVALID, "contentHash");
        }
    }

    /** Builds a full publication definition and stamps it with its own canonical content hash. */
    public static FullPublishedDefinition of(
            String fullDefinitionId,
            long publicationRevision,
            String tenantId,
            String programId,
            String userId,
            String dataScopeId,
            String campaignId,
            long campaignRevision,
            PublishedIdentity baseIdentity,
            ContentHash manifestHash,
            JourneyManifest journey,
            CompiledSchedule schedule,
            RuntimeAvailabilityPin runtimeAvailability,
            Instant publishedAt) {
        Objects.requireNonNull(journey, "journey");
        Objects.requireNonNull(schedule, "schedule");
        Objects.requireNonNull(runtimeAvailability, "runtimeAvailability");
        return new FullPublishedDefinition(
                FullPublishVersions.FULL_DEFINITION_FORMAT_VERSION,
                fullDefinitionId, publicationRevision, tenantId, programId, userId, dataScopeId, campaignId,
                campaignRevision, baseIdentity, manifestHash, journey, schedule, runtimeAvailability,
                publishedAt,
                CANONICAL_JSON.hash(material(
                        FullPublishVersions.FULL_DEFINITION_FORMAT_VERSION,
                        fullDefinitionId, publicationRevision, tenantId, programId, userId, dataScopeId,
                        campaignId, campaignRevision, baseIdentity, manifestHash, journey.contentHash(),
                        journey.journeyFormatVersion(), journey.compilerVersion(), journey.runtimeSchemaVersion(),
                        journey.topologyHash(), schedule.scheduleHash(), runtimeAvailability.pinHash(),
                        publishedAt)));
    }

    /** The published identifier of the accepted T17 definition this full definition binds. */
    public String basePublishedId() {
        return baseIdentity.publishedId();
    }

    /** The published revision of the accepted T17 definition this full definition binds. */
    public long basePublishedRevision() {
        return baseIdentity.revision();
    }

    /** The content checksum of the accepted T17 definition this full definition binds. */
    public ContentHash baseDefinitionChecksum() {
        return baseIdentity.contentHash();
    }

    /** The exact compiled Journey content hash this definition froze. */
    public ContentHash journeyHash() {
        return journey.contentHash();
    }

    /** The exact compiled schedule hash this definition froze. */
    public ContentHash scheduleHash() {
        return schedule.scheduleHash();
    }

    /** The exact host runtime-availability pin this definition rests on. */
    public ContentHash runtimeAvailabilityHash() {
        return runtimeAvailability.pinHash();
    }

    /** The immutable identity and content hash of this full published version. */
    public PublishedIdentity identity() {
        return new PublishedIdentity(fullDefinitionId, publicationRevision, contentHash);
    }

    /**
     * The hash material without the hash itself.
     *
     * <p>It is a static form because a record's compact constructor runs before its fields are assigned.
     */
    private static Material material(
            String formatVersion,
            String fullDefinitionId,
            long publicationRevision,
            String tenantId,
            String programId,
            String userId,
            String dataScopeId,
            String campaignId,
            long campaignRevision,
            PublishedIdentity baseIdentity,
            ContentHash manifestHash,
            ContentHash journeyHash,
            String journeyFormatVersion,
            String journeyCompilerVersion,
            String journeyRuntimeSchemaVersion,
            ContentHash journeyTopologyHash,
            ContentHash scheduleHash,
            ContentHash runtimeAvailabilityPinHash,
            Instant publishedAt) {
        return new Material(formatVersion, fullDefinitionId, publicationRevision, tenantId, programId, userId,
                dataScopeId, campaignId, campaignRevision, baseIdentity, manifestHash, journeyHash,
                journeyFormatVersion, journeyCompilerVersion, journeyRuntimeSchemaVersion, journeyTopologyHash,
                scheduleHash, runtimeAvailabilityPinHash, publishedAt);
    }

    /** Redacted diagnostics: no owner value is printed. */
    @Override
    public String toString() {
        return "FullPublishedDefinition[fullDefinitionId=" + fullDefinitionId
                + ", publicationRevision=" + publicationRevision
                + ", basePublishedId=" + basePublishedId()
                + ", journeyHash=" + journeyHash()
                + ", scheduleHash=" + scheduleHash()
                + ", contentHash=" + contentHash + ']';
    }

    /** Package-private hash material; the heavyweight frozen objects are bound by their own hashes. */
    record Material(
            String formatVersion,
            String fullDefinitionId,
            long publicationRevision,
            String tenantId,
            String programId,
            String userId,
            String dataScopeId,
            String campaignId,
            long campaignRevision,
            PublishedIdentity baseIdentity,
            ContentHash manifestHash,
            ContentHash journeyHash,
            String journeyFormatVersion,
            String journeyCompilerVersion,
            String journeyRuntimeSchemaVersion,
            ContentHash journeyTopologyHash,
            ContentHash scheduleHash,
            ContentHash runtimeAvailabilityPinHash,
            Instant publishedAt) {
    }
}