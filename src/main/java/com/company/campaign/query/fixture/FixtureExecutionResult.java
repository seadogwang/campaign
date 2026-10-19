package com.company.campaign.query.fixture;

import com.company.campaign.evaluation.segment.contract.MemberIdSet;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, deterministic result of one fixture execution.
 *
 * <p>It carries the exact canonical sorted {@link MemberIdSet} — not a count standing in for a set — the
 * deterministic matched count, the source plan hash, every fixture/mapping/scope/context pin the evaluation
 * depended on, and per-structural-path condition statistics. {@link #canonicalBytes()} and
 * {@link #resultHash()} are computed from that material with the accepted canonical JSON rules, so two
 * executions of identical inputs produce byte-identical canonical material and an identical hash.
 *
 * <p>It has no public constructor: {@link FixtureExecutor} is the only producer, and it re-proves its own
 * ordering and hash invariants before a result can be observed.
 */
public final class FixtureExecutionResult {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private final MemberIdSet memberIdSet;
    private final int matchedCount;
    private final ContentHash planHash;
    private final String fixtureId;
    private final String fixtureVersion;
    private final ContentHash payloadHash;
    private final ContentHash datasetHash;
    private final String mappingVersion;
    private final ContentHash mappingHash;
    private final String scopeBindingVersion;
    private final ContentHash scopeBindingHash;
    private final String metadataSnapshotId;
    private final String dataScopeId;
    private final Instant asOf;
    private final String timezone;
    private final Instant dataWatermark;
    private final String fxSnapshotId;
    private final ContentHash fxHash;
    private final List<PathStatistic> conditionStatistics;
    private final byte[] canonicalBytes;
    private final ContentHash resultHash;

    FixtureExecutionResult(
            MemberIdSet memberIdSet,
            ContentHash planHash,
            String fixtureId,
            String fixtureVersion,
            ContentHash payloadHash,
            ContentHash datasetHash,
            String mappingVersion,
            ContentHash mappingHash,
            String scopeBindingVersion,
            ContentHash scopeBindingHash,
            String metadataSnapshotId,
            String dataScopeId,
            Instant asOf,
            String timezone,
            Instant dataWatermark,
            String fxSnapshotId,
            ContentHash fxHash,
            List<PathStatistic> conditionStatistics) {
        this.memberIdSet = Objects.requireNonNull(memberIdSet, "memberIdSet");
        this.planHash = Objects.requireNonNull(planHash, "planHash");
        this.fixtureId = Objects.requireNonNull(fixtureId, "fixtureId");
        this.fixtureVersion = Objects.requireNonNull(fixtureVersion, "fixtureVersion");
        this.payloadHash = Objects.requireNonNull(payloadHash, "payloadHash");
        this.datasetHash = Objects.requireNonNull(datasetHash, "datasetHash");
        this.mappingVersion = Objects.requireNonNull(mappingVersion, "mappingVersion");
        this.mappingHash = Objects.requireNonNull(mappingHash, "mappingHash");
        this.scopeBindingVersion = Objects.requireNonNull(scopeBindingVersion, "scopeBindingVersion");
        this.scopeBindingHash = Objects.requireNonNull(scopeBindingHash, "scopeBindingHash");
        this.metadataSnapshotId = Objects.requireNonNull(metadataSnapshotId, "metadataSnapshotId");
        this.dataScopeId = Objects.requireNonNull(dataScopeId, "dataScopeId");
        this.asOf = Objects.requireNonNull(asOf, "asOf");
        this.timezone = Objects.requireNonNull(timezone, "timezone");
        this.dataWatermark = Objects.requireNonNull(dataWatermark, "dataWatermark");
        this.fxSnapshotId = fxSnapshotId;
        this.fxHash = fxHash;
        this.conditionStatistics = List.copyOf(Objects.requireNonNull(conditionStatistics, "conditionStatistics"));
        this.matchedCount = this.memberIdSet.size();
        this.canonicalBytes = CANONICAL_JSON.write(material());
        this.resultHash = ContentHash.sha256(this.canonicalBytes);
    }

    /** The exact canonical sorted member set this plan matches. */
    public MemberIdSet memberIdSet() {
        return memberIdSet;
    }

    /** The deterministic matched count; always equal to {@code memberIdSet().size()}. */
    public int matchedCount() {
        return matchedCount;
    }

    /** The exact plan hash this result was produced from. */
    public ContentHash planHash() {
        return planHash;
    }

    /** The fixture id pin. */
    public String fixtureId() {
        return fixtureId;
    }

    /** The fixture version pin. */
    public String fixtureVersion() {
        return fixtureVersion;
    }

    /** The SHA-256 of the exact payload bytes the dataset was parsed from. */
    public ContentHash payloadHash() {
        return payloadHash;
    }

    /** The canonical hash of every pinned dataset fact. */
    public ContentHash datasetHash() {
        return datasetHash;
    }

    /** The server-owned mapping version pin. */
    public String mappingVersion() {
        return mappingVersion;
    }

    /** The canonical hash of the server-owned mapping. */
    public ContentHash mappingHash() {
        return mappingHash;
    }

    /** The server-owned DataScope binding version pin. */
    public String scopeBindingVersion() {
        return scopeBindingVersion;
    }

    /** The canonical hash of the server-owned DataScope binding. */
    public ContentHash scopeBindingHash() {
        return scopeBindingHash;
    }

    /** The pinned Metadata Snapshot id. */
    public String metadataSnapshotId() {
        return metadataSnapshotId;
    }

    /** The pinned host-resolved DataScope id. */
    public String dataScopeId() {
        return dataScopeId;
    }

    /** The pinned evaluation instant. */
    public Instant asOf() {
        return asOf;
    }

    /** The pinned timezone id. */
    public String timezone() {
        return timezone;
    }

    /** The pinned data watermark. */
    public Instant dataWatermark() {
        return dataWatermark;
    }

    /** The FX-rate snapshot id when a conversion was required, otherwise {@code null}. */
    public String fxSnapshotId() {
        return fxSnapshotId;
    }

    /** The FX-rate snapshot hash when a conversion was required, otherwise {@code null}. */
    public ContentHash fxHash() {
        return fxHash;
    }

    /** Per-structural-path condition statistics in ascending path order. */
    public List<PathStatistic> conditionStatistics() {
        return conditionStatistics;
    }

    /** The exact canonical UTF-8 bytes of this result; a defensive copy. */
    public byte[] canonicalBytes() {
        return Arrays.copyOf(canonicalBytes, canonicalBytes.length);
    }

    /** The SHA-256 of {@link #canonicalBytes()}. */
    public ContentHash resultHash() {
        return resultHash;
    }

    private Material material() {
        return new Material(
                memberIdSet.memberIds(),
                matchedCount,
                planHash.value(),
                fixtureId,
                fixtureVersion,
                payloadHash.value(),
                datasetHash.value(),
                mappingVersion,
                mappingHash.value(),
                scopeBindingVersion,
                scopeBindingHash.value(),
                metadataSnapshotId,
                dataScopeId,
                asOf.toString(),
                timezone,
                dataWatermark.toString(),
                fxSnapshotId,
                fxHash == null ? null : fxHash.value(),
                conditionStatistics);
    }

    /** The exact material the canonical bytes and result hash cover. */
    private record Material(
            List<String> memberIds,
            int matchedCount,
            String planHash,
            String fixtureId,
            String fixtureVersion,
            String payloadHash,
            String datasetHash,
            String mappingVersion,
            String mappingHash,
            String scopeBindingVersion,
            String scopeBindingHash,
            String metadataSnapshotId,
            String dataScopeId,
            String asOf,
            String timezone,
            String dataWatermark,
            String fxSnapshotId,
            String fxHash,
            List<PathStatistic> conditionStatistics) {
    }

    /** How one structural AST path behaved across the candidate members. */
    public record PathStatistic(String path, int trueCount, int falseCount, int unknownCount) {

        public PathStatistic {
            Objects.requireNonNull(path, "path");
            if (trueCount < 0 || falseCount < 0 || unknownCount < 0) {
                throw new FixtureException(FixtureErrorCodes.FIXTURE_RESULT_INVALID, "pathStatistic",
                        "A condition statistic must not carry a negative count.");
            }
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FixtureExecutionResult result && Objects.equals(resultHash, result.resultHash);
    }

    @Override
    public int hashCode() {
        return resultHash.hashCode();
    }

    @Override
    public String toString() {
        return "FixtureExecutionResult[matchedCount=" + matchedCount + ", planHash=" + planHash
                + ", resultHash=" + resultHash + ']';
    }
}