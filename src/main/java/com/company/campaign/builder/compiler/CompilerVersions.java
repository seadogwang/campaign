package com.company.campaign.builder.compiler;

/**
 * Version discriminators stamped into every T15 compiler artifact.
 *
 * <p>Compilation is a deterministic, versioned transformation: a change to a merge, resolution or hashing
 * rule is a new version, so a Plan can never silently compare artifacts produced under different rules.
 * Every value is part of the Manifest and Plan hash material and is reported verbatim on the Plan.
 */
public final class CompilerVersions {

    /** The T15 proposal/Manifest compiler version. */
    public static final String COMPILER_VERSION = "campaign-proposal-compiler/1.0";

    /** The resolved Manifest document format version. */
    public static final String MANIFEST_FORMAT_VERSION = "1.0";

    /** The internal ChangeSet document format version. */
    public static final String CHANGESET_FORMAT_VERSION = "1.0";

    /** The Campaign Plan document format version. */
    public static final String PLAN_FORMAT_VERSION = "1.0";

    private CompilerVersions() {
    }
}