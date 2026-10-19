package com.company.campaign.evaluation.segment.reference;

/**
 * The closed roles a reference-corpus fixture payload may declare.
 *
 * <p>A role is what a fixture <em>is</em>, so a case can never attach a payload of one kind to the slot of
 * another: the validator compares the role a case expects with the role the catalog declares and reports the
 * mismatch. The set is deliberately small and each role maps to exactly one host port, which keeps the
 * evaluation's trusted inputs enumerable.
 */
public enum ReferenceFixtureRole {

    /** The exact T04 Metadata snapshot a reference-bearing editable document binds. */
    METADATA_SNAPSHOT,

    /** A host-owned exact-version catalog: immutable version snapshots and their availability. */
    VERSION_CATALOG,

    /** A host-owned DataScope port: scope identities and the members each contains. */
    DATA_SCOPE,

    /** A host program-relation policy: which caller program may reach which referenced program. */
    PROGRAM_POLICY,

    /** The bounded reference policy a document is judged against. */
    REFERENCE_POLICY,

    /** A host-owned read-only discovery directory of audiences and their published versions. */
    DISCOVERY_DIRECTORY,

    /** Local fixture membership bound to exact pins. */
    MEMBERSHIP_DATASET,

    /** The host retention policy a retention case exercises. */
    RETENTION_POLICY,

    /** A trusted execution context, reused verbatim from the base evaluation fixture shape. */
    EXECUTION_CONTEXT;

    /** True when this role names a trusted port the reference core reads facts from. */
    public boolean isTrustedPort() {
        return this != EXECUTION_CONTEXT && this != METADATA_SNAPSHOT;
    }
}