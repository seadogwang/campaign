package com.company.campaign.evaluation.capabilities.t19;

import com.company.campaign.contracts.SchemaVersion;

import java.util.List;

/**
 * The version discriminators and frozen references of the T19 capability evaluation corpus.
 *
 * <p>Every value here is part of a frozen asset identity or a canonical metadata hash, so a change to any of
 * them is a new corpus version rather than a silent reinterpretation of an already-produced asset.
 */
public final class T19EvaluationVersions {

    /** The repository-relative evaluation root of the T19 capability corpus. */
    public static final String EVALUATION_ROOT_REFERENCE = "evaluation/capabilities/t19-v1";

    /** The committed manifest reference inside the evaluation root. */
    public static final String MANIFEST_REFERENCE = EVALUATION_ROOT_REFERENCE + "/manifest.json";

    /** The corpus identity and version. */
    public static final String ASSET_ID = "t19-capability-evaluation-v1";
    public static final String ASSET_VERSION = "1.0";

    /** The one supported schema version of a T19 evaluation asset. */
    public static final SchemaVersion SCHEMA_VERSION = SchemaVersion.parse("1.0");

    /** The one runtime schema version every T19 pack must declare to be compatible with this corpus. */
    public static final String RUNTIME_SCHEMA_VERSION = "campaign-runtime/1.0";

    /** The exact accepted initial T19 capability codes the corpus must cover, in declaration order. */
    public static final List<String> CAPABILITY_CODES = List.of(
            "message.template",
            "promotion.reference",
            "flow.start",
            "flow.message",
            "flow.wait",
            "flow.condition",
            "flow.reward",
            "flow.end",
            "schedule.basic",
            "governance.limit");

    private T19EvaluationVersions() {
    }
}