package com.company.campaign.readiness.postgres;

import com.company.campaign.readiness.ReadinessEnvironment;
import com.company.campaign.shared.canonical.CanonicalJson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The bounded, hash-pinned report one real-PostgreSQL verification run writes.
 *
 * <p>It records only bounded machine values: the report version, the engine, a bounded server identity, the
 * dedicated schema, the environment, the applied migration versions and the boolean outcome of each proof.
 * It carries no credential, no URL, no tenant, no row and no free prose, so the document is safe to keep in
 * the repository's ignored runtime directory.
 *
 * <p>{@code reportHash} is the canonical SHA-256 of the document without that field, so the report is
 * tamper-evident. {@code productionGateEvidence} is {@code true} only when an external operator explicitly
 * supplied a production environment; otherwise the report is development evidence and cannot satisfy a
 * production-only readiness requirement.
 */
record ReadinessPostgresVerificationReport(
        String reportVersion,
        String engine,
        String serverVersion,
        String schema,
        String environment,
        List<Integer> migrationVersions,
        Map<String, Boolean> proofs,
        String observedAt,
        boolean productionGateEvidence,
        String reportHash) {

    static final String REPORT_VERSION = "campaign-readiness-postgres-verification/1.0";
    static final String ENGINE = "POSTGRESQL";

    /** The closed proof set every successful run must report. */
    static final Set<String> REQUIRED_PROOFS = Set.of(
            "append",
            "idempotentReplay",
            "conflictRefusal",
            "staleRevisionRollback",
            "assessmentPersistence",
            "auditAtomicity",
            "restartDurability");

    private static final CanonicalJson JSON = new CanonicalJson();
    private static final int MAX_SERVER_VERSION_LENGTH = 64;
    private static final int MAX_SCHEMA_LENGTH = 41;

    /** Builds a report whose {@code reportHash} pins every other field. */
    static ReadinessPostgresVerificationReport of(
            String serverVersion,
            String schema,
            ReadinessEnvironment environment,
            List<Integer> migrationVersions,
            Map<String, Boolean> proofs,
            Instant observedAt) {
        String boundedServerVersion = bounded(serverVersion, MAX_SERVER_VERSION_LENGTH);
        String boundedSchema = bounded(schema, MAX_SCHEMA_LENGTH);
        List<Integer> versions = List.copyOf(migrationVersions);
        Map<String, Boolean> outcomes = Map.copyOf(proofs);
        String hash = JSON.hash(material(
                boundedServerVersion, boundedSchema, environment, versions, outcomes, observedAt)).value();
        return new ReadinessPostgresVerificationReport(
                REPORT_VERSION,
                ENGINE,
                boundedServerVersion,
                boundedSchema,
                environment.name(),
                versions,
                outcomes,
                observedAt.toString(),
                environment.production(),
                hash);
    }

    /** True when every required proof was recorded as passing. */
    boolean everythingProved() {
        return proofs.entrySet().stream()
                .filter(entry -> REQUIRED_PROOFS.contains(entry.getKey()))
                .allMatch(entry -> Boolean.TRUE.equals(entry.getValue()))
                && proofs.keySet().containsAll(REQUIRED_PROOFS);
    }

    /** Writes the exact canonical bytes, so a verifier can re-hash the file as it stands. */
    void write(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(path, JSON.write(this));
    }

    /** The report text, for diagnostics. */
    String canonicalText() {
        return new String(JSON.write(this), StandardCharsets.UTF_8);
    }

    private static Map<String, Object> material(
            String serverVersion,
            String schema,
            ReadinessEnvironment environment,
            List<Integer> migrationVersions,
            Map<String, Boolean> proofs,
            Instant observedAt) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("reportVersion", REPORT_VERSION);
        material.put("engine", ENGINE);
        material.put("serverVersion", serverVersion);
        material.put("schema", schema);
        material.put("environment", environment.name());
        material.put("migrationVersions", new ArrayList<>(migrationVersions));
        material.put("proofs", new LinkedHashMap<>(proofs));
        material.put("observedAt", observedAt.toString());
        material.put("productionGateEvidence", environment.production());
        return material;
    }

    private static String bounded(String value, int maximum) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String trimmed = value.strip().replaceAll("[\\r\\n]+", " ");
        return trimmed.length() <= maximum ? trimmed : trimmed.substring(0, maximum);
    }
}