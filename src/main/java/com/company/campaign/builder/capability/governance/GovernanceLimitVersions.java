package com.company.campaign.builder.capability.governance;

import com.company.campaign.builder.compiler.CompositionSlot;
import com.company.campaign.builder.compiler.RuntimeCompatibility;
import com.company.campaign.contracts.SchemaVersion;

import java.math.BigDecimal;

/**
 * Every version discriminator, closed code and frozen policy bound owned by the {@code governance.limit}
 * capability pack at contract version 1.0.
 *
 * <p>Each value here is part of a frozen catalog pin, a canonical metadata hash or a preview-card material, so
 * changing any of them is a new contract version rather than a silent reinterpretation of an already-produced
 * Manifest or Plan. The bounds are the frozen policy bounds of this contract version: a change to a bound is a
 * new version, never a mutable setting, and the pack's declared payload contract and its resolver are derived
 * from these same constants.
 */
public final class GovernanceLimitVersions {

    /** The one capability code this pack implements. */
    public static final String CAPABILITY_CODE = "governance.limit";

    /** The exact contract version of this capability pack. */
    public static final String CONTRACT_VERSION = "1.0";

    /** The pack version stamped into the frozen capability pack metadata. */
    public static final String PACK_VERSION = "t19-v1";

    /** The schema version of the pack metadata document. */
    public static final SchemaVersion SCHEMA_VERSION = SchemaVersion.parse("1.0");

    /** The singleton, unordered Campaign governance composition slot this capability occupies. */
    public static final CompositionSlot SLOT = new CompositionSlot("campaign.governance", false, true);

    /** The exact runtime schema version this pack declares compatibility with. */
    public static final String RUNTIME_SCHEMA_VERSION = "campaign-runtime/1.0";

    /**
     * The frozen preview/runtime compatibility of this contract version.
     *
     * <p>{@code previewSupported} is true because the generic safe preview card path exists. {@code publishable}
     * is false, honestly: no production Runtime or provider path exists in T19, and a declared limit is an
     * authoring declaration — never an inventory reservation, a balance or a grant result.
     */
    public static final RuntimeCompatibility RUNTIME_COMPATIBILITY =
            new RuntimeCompatibility(RUNTIME_SCHEMA_VERSION, true, false);

    // ------------------------------------------------------------------ payload field names

    /** The declared maximum-recipients field. */
    public static final String FIELD_MAX_RECIPIENTS = "maxRecipients";

    /** The declared maximum-reward-grants field. */
    public static final String FIELD_MAX_REWARD_GRANTS = "maxRewardGrants";

    /** The declared exact maximum-reward-face-value field. */
    public static final String FIELD_MAX_REWARD_FACE_VALUE = "maxRewardFaceValue";

    /** The declared ISO-4217 currency field. */
    public static final String FIELD_CURRENCY = "currency";

    // ------------------------------------------------------------------ frozen policy bounds

    /** The largest accepted {@code maxRecipients}; the smallest is one, because a limit must be positive. */
    public static final long MAX_RECIPIENTS_POLICY_BOUND = 1_000_000L;

    /** The largest accepted {@code maxRewardGrants}; the smallest is one, because a limit must be positive. */
    public static final long MAX_REWARD_GRANTS_POLICY_BOUND = 1_000_000L;

    /** The exact scale, in minor units, of a declared reward face value. */
    public static final int FACE_VALUE_SCALE = 2;

    /** The declared precision of a reward face value. */
    public static final int FACE_VALUE_PRECISION = 20;

    /** The inclusive policy upper bound of a declared reward face value. */
    public static final BigDecimal MAX_REWARD_FACE_VALUE = new BigDecimal("1000000000.00");

    /** The inclusive lower bound of a declared reward face value: exactly zero. */
    public static final BigDecimal MIN_REWARD_FACE_VALUE = new BigDecimal("0.00");

    /** Largest accepted currency text length. */
    public static final int MAX_CURRENCY_LENGTH = 3;

    // ------------------------------------------------------------------ evaluation asset

    /** The versioned evaluation asset identity this pack pins. */
    public static final String EVALUATION_ASSET_ID = "t19-governance-limit-evaluation-v1";

    /** The exact evaluation asset version this pack pins. */
    public static final String EVALUATION_ASSET_VERSION = "1.0";

    // ------------------------------------------------------------------ bounded card and issue codes

    /** The bounded summary code every resolved governance limit carries. */
    public static final String SUMMARY_LIMITS_DECLARED = "GOVERNANCE_LIMITS_DECLARED";

    /** The bounded risk code stating that this resolution is preview-only. */
    public static final String RISK_PREVIEW_ONLY = "RUNTIME_PREVIEW_ONLY";

    /** The bounded risk code stating that a declared limit is not an inventory reservation. */
    public static final String RISK_NOT_A_RESERVATION = "GOVERNANCE_NOT_A_RESERVATION";

    /** A required host input was absent. */
    public static final String ISSUE_INPUT = "CAMPAIGN_GOVERNANCE_LIMIT_INPUT_MISSING";

    /** The payload declared a field this contract version does not declare. */
    public static final String ISSUE_UNKNOWN_FIELD = "CAMPAIGN_GOVERNANCE_LIMIT_UNKNOWN_FIELD";

    /** A declared field is absent, of the wrong type or of the wrong shape. */
    public static final String ISSUE_FIELD = "CAMPAIGN_GOVERNANCE_LIMIT_FIELD_INVALID";

    /** A declared limit is not positive, or exceeds the frozen policy bound. */
    public static final String ISSUE_LIMIT_BOUND = "CAMPAIGN_GOVERNANCE_LIMIT_BOUND_INVALID";

    /** A declared reward face value is negative, inexact, or exceeds the frozen policy bound. */
    public static final String ISSUE_FACE_VALUE = "CAMPAIGN_GOVERNANCE_LIMIT_FACE_VALUE_INVALID";

    /** The declared currency is not a canonical ISO-4217 code. */
    public static final String ISSUE_CURRENCY = "CAMPAIGN_GOVERNANCE_LIMIT_CURRENCY_INVALID";

    private GovernanceLimitVersions() {
    }
}