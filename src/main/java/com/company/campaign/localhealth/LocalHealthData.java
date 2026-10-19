package com.company.campaign.localhealth;

import com.company.campaign.readiness.ReadinessPostgresVerification;
import com.company.campaign.readiness.ReadinessStorageMode;

/**
 * The bounded success payload of {@code GET /api/v1/health}.
 *
 * <p>Four facts and nothing else: the process is up, which storage mode is wired, whether real PostgreSQL was
 * actually exercised in this configured environment, and the readiness answer for the local storage boundary.
 * There is no credential, no JDBC URL, no tenant, no program, no actor, no host name, no port, no migration
 * hash, no evidence payload and no free prose, so the payload is identical in shape on every deployment and
 * cannot be used to enumerate anything.
 */
public record LocalHealthData(
        String process,
        ReadinessStorageMode storageMode,
        ReadinessPostgresVerification postgresqlVerification,
        LocalReadiness readiness) {

    /** The one value {@code process} may carry: the endpoint answers, therefore the process is up. */
    public static final String PROCESS_UP = "UP";

    public LocalHealthData {
        if (process == null || !PROCESS_UP.equals(process)) {
            throw new IllegalArgumentException("The local health process marker is always UP.");
        }
        if (storageMode == null || postgresqlVerification == null || readiness == null) {
            throw new IllegalArgumentException("A local health payload requires every bounded field.");
        }
    }
}