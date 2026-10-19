package com.company.campaign.readiness;

/**
 * Every format version, closed discriminator and frozen marker owned by the T25 readiness slice.
 *
 * <p>Each version here participates in a canonical hash, so changing one is a new version rather than a
 * silent reinterpretation of bytes a previous version wrote.
 */
public final class ReadinessVersions {

    /** The evidence record format version. */
    public static final String EVIDENCE_FORMAT_VERSION = "campaign-readiness-evidence/1.0";

    /** The assessment record format version. */
    public static final String ASSESSMENT_FORMAT_VERSION = "campaign-readiness-assessment/1.0";

    /** The audit record format version. */
    public static final String AUDIT_FORMAT_VERSION = "campaign-readiness-audit/1.0";

    /** The observability snapshot format version. */
    public static final String OBSERVABILITY_FORMAT_VERSION = "campaign-readiness-observability/1.0";

    /** The closed requirement catalog version. */
    public static final String CATALOG_VERSION = "campaign-readiness-catalog/1.0";

    /** The ordered migration asset set version. */
    public static final String MIGRATION_SET_VERSION = "campaign-readiness-migrations/1.0";

    /**
     * True when a readiness record kind is frozen development evidence rather than a production claim.
     *
     * <p>It mirrors {@code ActionVersions.PRODUCTION_GATE_EVIDENCE} and
     * {@code RuntimeVersions.PRODUCTION_GATE_EVIDENCE}: the readiness slice validates evidence, so its own
     * records are never the production Gate evidence they adjudicate.
     */
    public static final boolean PRODUCTION_GATE_EVIDENCE = false;

    private ReadinessVersions() {
    }
}