package com.company.campaign.preview;

import com.company.campaign.evaluation.segment.contract.MemberIdSet;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, hash-bound result of one read-only Preview execution.
 *
 * <p>It carries at least the exact canonical sorted {@link MemberIdSet} — never a count standing in for a set —
 * the deterministic condition statistics, the source plan hash, the Metadata Snapshot identifier, the
 * DataScope identifier, the trusted {@code asOf}, timezone and data watermark, and a backend identity/version.
 * {@link #resultHash()} covers every one of those facts through strict canonical bytes, so two executions of
 * identical inputs produce byte-identical material and an identical hash, and no fact can be edited without
 * invalidating the hash.
 *
 * <p>It has no public constructor: {@link #publish} is the only producer, and it re-proves ordering, duplicate
 * and hash invariants before a result can be observed. The application service still re-proves every pin
 * against the genuine plan and trusted context before projecting anything.
 *
 * <p>The raw member ids exist only inside this internal read boundary and its hash material; they are never
 * projected into a Preview response. {@link #toString()} deliberately prints counts and pins, never member
 * identifiers.
 */
public final class PreviewBackendResult {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private final MemberIdSet memberIdSet;
    private final List<PreviewConditionStatistic> conditionStatistics;
    private final ContentHash planHash;
    private final String metadataSnapshotId;
    private final String dataScopeId;
    private final Instant asOf;
    private final String timezone;
    private final Instant dataWatermark;
    private final String backendId;
    private final String backendVersion;
    private final byte[] canonicalBytes;
    private final ContentHash resultHash;

    private PreviewBackendResult(
            MemberIdSet memberIdSet,
            List<PreviewConditionStatistic> conditionStatistics,
            ContentHash planHash,
            String metadataSnapshotId,
            String dataScopeId,
            Instant asOf,
            String timezone,
            Instant dataWatermark,
            String backendId,
            String backendVersion) {
        this.memberIdSet = Objects.requireNonNull(memberIdSet, "memberIdSet");
        this.conditionStatistics = List.copyOf(conditionStatistics);
        this.planHash = Objects.requireNonNull(planHash, "planHash");
        this.metadataSnapshotId = requireText(metadataSnapshotId, "metadataSnapshotId");
        this.dataScopeId = requireText(dataScopeId, "dataScopeId");
        this.asOf = Objects.requireNonNull(asOf, "asOf");
        this.timezone = requireText(timezone, "timezone");
        this.dataWatermark = Objects.requireNonNull(dataWatermark, "dataWatermark");
        this.backendId = requireText(backendId, "backendId");
        this.backendVersion = requireText(backendVersion, "backendVersion");
        if (dataWatermark.isAfter(asOf)) {
            throw PreviewException.of(PreviewErrorCodes.EXECUTION_RESULT_INCOHERENT, "dataWatermark");
        }
        this.canonicalBytes = CANONICAL_JSON.write(material());
        this.resultHash = ContentHash.sha256(canonicalBytes);
    }

    /** Publishes a validated, hash-bound result. */
    public static PreviewBackendResult publish(
            MemberIdSet memberIdSet,
            List<PreviewConditionStatistic> conditionStatistics,
            ContentHash planHash,
            String metadataSnapshotId,
            String dataScopeId,
            Instant asOf,
            String timezone,
            Instant dataWatermark,
            String backendId,
            String backendVersion) {
        if (memberIdSet == null || planHash == null || asOf == null || dataWatermark == null
                || conditionStatistics == null) {
            throw PreviewException.of(PreviewErrorCodes.EXECUTION_RESULT_INCOHERENT, "backendResult");
        }
        List<PreviewConditionStatistic> sorted = new ArrayList<>(conditionStatistics.size());
        String previousPath = null;
        for (PreviewConditionStatistic statistic : conditionStatistics) {
            if (statistic == null) {
                throw PreviewException.of(PreviewErrorCodes.EXECUTION_RESULT_INCOHERENT, "conditionStatistic");
            }
            if (previousPath != null && previousPath.compareTo(statistic.path()) >= 0) {
                // Statistics must be strictly ascending by structural path: no duplicate, no arbitrary order.
                throw PreviewException.of(PreviewErrorCodes.EXECUTION_RESULT_INCOHERENT, "conditionStatisticOrder");
            }
            previousPath = statistic.path();
            sorted.add(statistic);
        }
        return new PreviewBackendResult(
                memberIdSet, sorted, planHash, metadataSnapshotId, dataScopeId, asOf, timezone,
                dataWatermark, backendId, backendVersion);
    }

    /** The exact canonical sorted member set this execution matched. Never projected into a response. */
    public MemberIdSet memberIdSet() {
        return memberIdSet;
    }

    /** The deterministic matched count; always equal to {@code memberIdSet().size()}. */
    public int matchedCount() {
        return memberIdSet.size();
    }

    /** Per-structural-path condition statistics in strictly ascending path order. */
    public List<PreviewConditionStatistic> conditionStatistics() {
        return conditionStatistics;
    }

    /** The exact source plan hash this result was produced from. */
    public ContentHash planHash() {
        return planHash;
    }

    /** The pinned Metadata Snapshot identifier. */
    public String metadataSnapshotId() {
        return metadataSnapshotId;
    }

    /** The pinned host-resolved DataScope identifier. */
    public String dataScopeId() {
        return dataScopeId;
    }

    /** The pinned evaluation instant. */
    public Instant asOf() {
        return asOf;
    }

    /** The pinned timezone identifier. */
    public String timezone() {
        return timezone;
    }

    /** The pinned data watermark. */
    public Instant dataWatermark() {
        return dataWatermark;
    }

    /** The stable server-owned backend identity that produced this result. */
    public String backendId() {
        return backendId;
    }

    /** The backend version discriminator bound into execution. */
    public String backendVersion() {
        return backendVersion;
    }

    /** The SHA-256 of {@link #canonicalBytes()}; covers every result fact. */
    public ContentHash resultHash() {
        return resultHash;
    }

    /** The exact canonical UTF-8 bytes of this result; a defensive copy. */
    public byte[] canonicalBytes() {
        return Arrays.copyOf(canonicalBytes, canonicalBytes.length);
    }

    private static String requireText(String value, String invariant) {
        if (value == null || value.isBlank()) {
            throw PreviewException.of(PreviewErrorCodes.EXECUTION_RESULT_INCOHERENT, invariant);
        }
        return value;
    }

    private Material material() {
        return new Material(
                memberIdSet.memberIds(),
                conditionStatistics,
                planHash.value(),
                metadataSnapshotId,
                dataScopeId,
                asOf.toString(),
                timezone,
                dataWatermark.toString(),
                backendId,
                backendVersion);
    }

    /** The exact material the canonical bytes and result hash cover. */
    private record Material(
            List<String> memberIds,
            List<PreviewConditionStatistic> conditionStatistics,
            String planHash,
            String metadataSnapshotId,
            String dataScopeId,
            String asOf,
            String timezone,
            String dataWatermark,
            String backendId,
            String backendVersion) {
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PreviewBackendResult result && Objects.equals(resultHash, result.resultHash);
    }

    @Override
    public int hashCode() {
        return resultHash.hashCode();
    }

    @Override
    public String toString() {
        return "PreviewBackendResult[matchedCount=" + matchedCount()
                + ", metadataSnapshotId=" + metadataSnapshotId
                + ", backendId=" + backendId
                + ", resultHash=" + resultHash + ']';
    }
}