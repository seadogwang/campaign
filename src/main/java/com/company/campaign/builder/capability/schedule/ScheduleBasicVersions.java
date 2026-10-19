package com.company.campaign.builder.capability.schedule;

import com.company.campaign.builder.compiler.CompositionSlot;
import com.company.campaign.builder.compiler.RuntimeCompatibility;
import com.company.campaign.contracts.SchemaVersion;

/**
 * Every version discriminator, closed code and frozen bound owned by the {@code schedule.basic} capability
 * pack at contract version 1.0.
 *
 * <p>Each value here is part of a frozen catalog pin, a canonical metadata hash or a preview-card material, so
 * changing any of them is a new contract version rather than a silent reinterpretation of an already-produced
 * Manifest or Plan. The bounds are the frozen policy bounds of this contract version: a change to a bound is a
 * new version, never a mutable setting.
 */
public final class ScheduleBasicVersions {

    /** The one capability code this pack implements. */
    public static final String CAPABILITY_CODE = "schedule.basic";

    /** The exact contract version of this capability pack. */
    public static final String CONTRACT_VERSION = "1.0";

    /** The pack version stamped into the frozen capability pack metadata. */
    public static final String PACK_VERSION = "t19-v1";

    /** The schema version of the pack metadata document. */
    public static final SchemaVersion SCHEMA_VERSION = SchemaVersion.parse("1.0");

    /** The singleton, unordered Campaign schedule composition slot this capability occupies. */
    public static final CompositionSlot SLOT = new CompositionSlot("campaign.schedule", false, true);

    /** The exact runtime schema version this pack declares compatibility with. */
    public static final String RUNTIME_SCHEMA_VERSION = "campaign-runtime/1.0";

    /**
     * The frozen preview/runtime compatibility of this contract version.
     *
     * <p>{@code previewSupported} is true because the generic safe preview card path exists. {@code publishable}
     * is false, honestly: no production Runtime, publication or scheduling work exists in T19, and development
     * preview evidence is never relabelled as production-ready.
     */
    public static final RuntimeCompatibility RUNTIME_COMPATIBILITY =
            new RuntimeCompatibility(RUNTIME_SCHEMA_VERSION, true, false);

    // ------------------------------------------------------------------ payload field names

    /** The declared form field. */
    public static final String FIELD_FORM = "form";

    /** The declared canonical IANA timezone identifier field. */
    public static final String FIELD_ZONE_ID = "zoneId";

    /** The declared offset instant field, used only by {@code ONCE}. */
    public static final String FIELD_RUN_AT = "runAt";

    /** The declared local time field, used only by {@code DAILY}. */
    public static final String FIELD_LOCAL_TIME = "localTime";

    /** The declared inclusive start date field, used only by {@code DAILY}. */
    public static final String FIELD_START_DATE = "startDate";

    /** The declared inclusive end date field, used only by {@code DAILY}. */
    public static final String FIELD_END_DATE = "endDate";

    // ------------------------------------------------------------------ frozen bounds

    /** Largest accepted canonical timezone identifier length. */
    public static final int MAX_ZONE_ID_LENGTH = 64;

    /** Largest accepted offset instant text length. */
    public static final int MAX_INSTANT_LENGTH = 64;

    /** Largest accepted local time text length. */
    public static final int MAX_LOCAL_TIME_LENGTH = 16;

    /** Largest accepted {@code DAILY} window, counted inclusively in calendar days. */
    public static final long MAX_CALENDAR_DAYS = 90;

    // ------------------------------------------------------------------ evaluation asset

    /** The versioned evaluation asset identity this pack pins. */
    public static final String EVALUATION_ASSET_ID = "t19-schedule-basic-evaluation-v1";

    /** The exact evaluation asset version this pack pins. */
    public static final String EVALUATION_ASSET_VERSION = "1.0";

    // ------------------------------------------------------------------ bounded card and issue codes

    /** The bounded summary code every resolved schedule carries alongside its form code. */
    public static final String SUMMARY_CONTRACT_ONLY = "SCHEDULE_CONTRACT_ONLY";

    /** The bounded risk code stating that this resolution is preview-only. */
    public static final String RISK_PREVIEW_ONLY = "RUNTIME_PREVIEW_ONLY";

    /** The bounded risk code stating that occurrence generation is deferred to a later task. */
    public static final String RISK_OCCURRENCES_DEFERRED = "SCHEDULE_OCCURRENCES_DEFERRED";

    /** A required host input was absent. */
    public static final String ISSUE_INPUT = "CAMPAIGN_SCHEDULE_BASIC_INPUT_MISSING";

    /** The payload declared a field this contract version does not declare. */
    public static final String ISSUE_UNKNOWN_FIELD = "CAMPAIGN_SCHEDULE_BASIC_UNKNOWN_FIELD";

    /** A declared field is absent, of the wrong type or of the wrong shape. */
    public static final String ISSUE_FIELD = "CAMPAIGN_SCHEDULE_BASIC_FIELD_INVALID";

    /** The declared form is not one this contract version accepts. */
    public static final String ISSUE_FORM = "CAMPAIGN_SCHEDULE_BASIC_FORM_UNSUPPORTED";

    /** The declared timezone is not a canonical IANA region identifier. */
    public static final String ISSUE_ZONE = "CAMPAIGN_SCHEDULE_BASIC_ZONE_INVALID";

    /** The declared instant or window is inconsistent, or the window exceeds the frozen bound. */
    public static final String ISSUE_WINDOW = "CAMPAIGN_SCHEDULE_BASIC_WINDOW_INVALID";

    private ScheduleBasicVersions() {
    }
}