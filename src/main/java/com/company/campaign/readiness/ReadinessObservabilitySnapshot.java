package com.company.campaign.readiness;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * The bounded internal readiness status snapshot.
 *
 * <p>Its labels are deliberately low-cardinality: the level, the environment and blocker <em>codes</em> from
 * the closed catalogs. A tenant, program, actor, evidence identifier or evidence reference is never a label —
 * the scope is a parameter of the read, not an attribute of the metric — so readiness cannot become a
 * high-cardinality metrics source.
 *
 * <p>The snapshot carries counts and a canonical hash over them, never the blockers themselves and never an
 * evidence payload.
 */
public record ReadinessObservabilitySnapshot(
        String formatVersion,
        ReadinessLevel level,
        ReadinessStatus status,
        ReadinessEnvironment environment,
        int evidenceCount,
        int satisfiedCount,
        int blockedCount,
        int invalidCount,
        int blockerCount,
        Map<String, Integer> blockerCountsByCode,
        long revision,
        ContentHash snapshotHash) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public ReadinessObservabilitySnapshot {
        if (!ReadinessVersions.OBSERVABILITY_FORMAT_VERSION.equals(formatVersion)) {
            throw new ReadinessException(
                    ReadinessErrorCodes.READINESS_ASSESSMENT_INVALID,
                    "The readiness observability format version is not the accepted one.");
        }
        ReadinessSupport.requireNonNull(level, "level");
        ReadinessSupport.requireNonNull(status, "status");
        ReadinessSupport.requireNonNull(environment, "environment");
        ReadinessSupport.requireNonNull(snapshotHash, "snapshotHash");
        if (evidenceCount < 0 || satisfiedCount < 0 || blockedCount < 0 || invalidCount < 0
                || blockerCount < 0 || revision < 0
                || satisfiedCount + blockedCount + invalidCount != evidenceCount) {
            throw new ReadinessException(
                    ReadinessErrorCodes.READINESS_ASSESSMENT_INVALID,
                    "A readiness observability snapshot carries inconsistent counts.");
        }
        if (blockerCountsByCode == null || blockerCountsByCode.size() > ReadinessLimits.MAX_BLOCKER_CODES) {
            throw new ReadinessException(
                    ReadinessErrorCodes.READINESS_ASSESSMENT_INVALID,
                    "A readiness observability snapshot carries an unbounded blocker label set.");
        }
        Map<String, Integer> sorted = new TreeMap<>();
        int total = 0;
        for (Map.Entry<String, Integer> entry : blockerCountsByCode.entrySet()) {
            String code = entry.getKey();
            Integer count = entry.getValue();
            if (!ReadinessBlockerCodes.isKnown(code) && !ReadinessReasonCodes.isKnown(code)) {
                throw new ReadinessException(
                        ReadinessErrorCodes.READINESS_REASON_UNKNOWN,
                        "A readiness observability label is not part of a closed catalog.");
            }
            if (count == null || count < 1) {
                throw new ReadinessException(
                        ReadinessErrorCodes.READINESS_ASSESSMENT_INVALID,
                        "A readiness observability label count must be positive.");
            }
            sorted.put(code, count);
            total += count;
        }
        if (total != blockerCount) {
            throw new ReadinessException(
                    ReadinessErrorCodes.READINESS_ASSESSMENT_INVALID,
                    "The readiness observability blocker counts do not add up.");
        }
        blockerCountsByCode = Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
        if (!snapshotHash.equals(CANONICAL_JSON.hash(new SnapshotMaterial(
                formatVersion, level.name(), status.name(), environment.name(),
                evidenceCount, satisfiedCount, blockedCount, invalidCount,
                blockerCount, new LinkedHashMap<>(blockerCountsByCode), revision)))) {
            throw new ReadinessException(
                    ReadinessErrorCodes.READINESS_EVIDENCE_HASH_MISMATCH,
                    "The readiness observability snapshot hash does not match its own canonical bytes.");
        }
    }

    /** Stamps one snapshot with its own canonical hash. */
    public static ReadinessObservabilitySnapshot of(
            ReadinessLevel level,
            ReadinessStatus status,
            ReadinessEnvironment environment,
            int evidenceCount,
            int satisfiedCount,
            int blockedCount,
            int invalidCount,
            int blockerCount,
            Map<String, Integer> blockerCountsByCode,
            long revision) {
        Map<String, Integer> sorted = new TreeMap<>(blockerCountsByCode);
        ContentHash hash = CANONICAL_JSON.hash(new SnapshotMaterial(
                ReadinessVersions.OBSERVABILITY_FORMAT_VERSION,
                level.name(), status.name(), environment.name(),
                evidenceCount, satisfiedCount, blockedCount, invalidCount,
                blockerCount, new LinkedHashMap<>(sorted), revision));
        return new ReadinessObservabilitySnapshot(
                ReadinessVersions.OBSERVABILITY_FORMAT_VERSION,
                level, status, environment,
                evidenceCount, satisfiedCount, blockedCount, invalidCount,
                blockerCount, sorted, revision, hash);
    }

    /** The bounded blocker count for one stable code, or zero when it did not occur. */
    public int blockerCount(String code) {
        return blockerCountsByCode.getOrDefault(code, 0);
    }

    /** Redacted diagnostics: no scope identifier and no evidence reference is printed. */
    @Override
    public String toString() {
        return "ReadinessObservabilitySnapshot[level=" + level + ", status=" + status
                + ", environment=" + environment + ", blockers=" + blockerCount
                + ", revision=" + revision + ']';
    }

    /** Package-private snapshot hash material. */
    record SnapshotMaterial(
            String formatVersion,
            String level,
            String status,
            String environment,
            int evidenceCount,
            int satisfiedCount,
            int blockedCount,
            int invalidCount,
            int blockerCount,
            Map<String, Integer> blockerCountsByCode,
            long revision) {
    }
}
