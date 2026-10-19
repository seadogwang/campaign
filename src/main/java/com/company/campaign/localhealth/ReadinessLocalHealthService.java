package com.company.campaign.localhealth;

import com.company.campaign.readiness.ReadinessLevel;
import com.company.campaign.readiness.ReadinessPostgresVerification;
import com.company.campaign.readiness.ReadinessReasonCodes;
import com.company.campaign.readiness.ReadinessRequirement;
import com.company.campaign.readiness.ReadinessStatus;
import com.company.campaign.readiness.ReadinessStorageMode;
import com.company.campaign.readiness.ReadinessStorageProbe;

import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Builds the bounded local health answer from the configured storage probe.
 *
 * <p>It is pure and deterministic: the same probe state always produces byte-identical output, no clock or
 * identifier is read, and the status is derived from the blockers rather than asserted. The status is
 * {@code READY} for exactly one state — PostgreSQL configured <em>and</em> actually verified — and
 * {@code BLOCKED} everywhere else, so an {@code IN_MEMORY} process, a merely configured process and a process
 * whose probe failed can never report an optimistic readiness claim.
 *
 * <p>The blocker mapping is deliberately narrow and truthful:
 *
 * <ul>
 *   <li>in-memory storage proves neither real PostgreSQL nor an applied migration set, so both codes are
 *       reported;</li>
 *   <li>PostgreSQL that was not verified, or whose verification failed, reports only "PostgreSQL not
 *       exercised" — the process cannot claim migrations applied, and does not claim they did not;</li>
 *   <li>a verified PostgreSQL run reports no blocker, so the endpoint reports {@code READY} for the two
 *       requirements it speaks for.</li>
 * </ul>
 */
public final class ReadinessLocalHealthService {

    /** The exact requirements this endpoint adjudicates; every other requirement stays owned by the evaluator. */
    public static final List<String> EVALUATED_REQUIREMENTS = List.of(
            ReadinessRequirement.LOCAL_POSTGRESQL_INTEGRATION.name(),
            ReadinessRequirement.LOCAL_ORDERED_MIGRATIONS.name());

    private final ReadinessStorageProbe probe;

    public ReadinessLocalHealthService(ReadinessStorageProbe probe) {
        if (probe == null) {
            throw new IllegalArgumentException("A local health service requires a storage probe.");
        }
        this.probe = probe;
    }

    /** The bounded health payload for the current process. */
    public LocalHealthData snapshot() {
        ReadinessStorageMode storageMode = probe.storageMode();
        ReadinessPostgresVerification verification = probe.postgresqlVerification();
        List<String> blockers = blockersFor(storageMode, verification);
        String status = blockers.isEmpty() ? ReadinessStatus.READY.name() : ReadinessStatus.BLOCKED.name();
        return new LocalHealthData(
                LocalHealthData.PROCESS_UP,
                storageMode,
                verification,
                new LocalReadiness(
                        ReadinessLevel.LOCAL_READY.name(), status, EVALUATED_REQUIREMENTS, blockers));
    }

    /** The deterministic, ordered blocker codes for one probe state. */
    static List<String> blockersFor(
            ReadinessStorageMode storageMode, ReadinessPostgresVerification verification) {
        if (storageMode == ReadinessStorageMode.POSTGRESQL
                && verification == ReadinessPostgresVerification.PASSED) {
            return List.of();
        }
        SortedSet<String> codes = new TreeSet<>();
        codes.add(ReadinessReasonCodes.POSTGRESQL_NOT_EXERCISED);
        if (storageMode == ReadinessStorageMode.IN_MEMORY) {
            codes.add(ReadinessReasonCodes.MIGRATIONS_NOT_APPLIED);
        }
        return List.copyOf(codes);
    }
}