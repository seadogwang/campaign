package com.company.campaign.fullpublish;

import com.company.campaign.builder.capability.schedule.ScheduleBasicForm;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * The complete, immutable, hash-bound compiled form of one {@code schedule.basic/1.0} schedule.
 *
 * <p>It freezes the schedule format, compiler and runtime schema versions, the accepted T19 form, the
 * canonical zone and exactly the fields that form defines, the source component's stable logical identity and
 * normalized payload hash, the source Manifest and Journey hashes the schedule was compiled against, and a
 * complete canonical schedule hash. It is a derived artifact bound to its source hashes; it is not a second
 * editable Campaign truth and it carries no occurrence, no Execution, no worker and no provider fact.
 *
 * <p>Construction re-proves its version discriminators, its form/field coherence, its canonical zone and
 * instant and its own schedule hash, so a schedule whose bytes do not match its own hash is not
 * representable. The optional form fields are omitted from the canonical material entirely when absent, so a
 * compiled {@code IMMEDIATE} schedule hashes to exactly its own bytes rather than to material carrying
 * explicit nulls.
 *
 * <p>{@link #toString()} prints no tenant, program, user, campaign or component value.
 */
public record CompiledSchedule(
        String scheduleFormatVersion,
        String compilerVersion,
        String runtimeSchemaVersion,
        ScheduleBasicForm form,
        String zoneId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String runAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) String localTime,
        @JsonInclude(JsonInclude.Include.NON_NULL) String startDate,
        @JsonInclude(JsonInclude.Include.NON_NULL) String endDate,
        String sourceComponentLogicalId,
        ContentHash sourcePayloadHash,
        ContentHash sourceManifestHash,
        ContentHash journeyHash,
        ContentHash scheduleHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public CompiledSchedule {
        if (!ScheduleVersions.SCHEDULE_FORMAT_VERSION.equals(scheduleFormatVersion)) {
            throw ScheduleSupport.invalid(
                    ScheduleErrorCodes.SCHEDULE_VERSION_UNSUPPORTED, "scheduleFormatVersion");
        }
        if (!ScheduleVersions.SCHEDULE_COMPILER_VERSION.equals(compilerVersion)) {
            throw ScheduleSupport.invalid(
                    ScheduleErrorCodes.SCHEDULE_COMPILER_VERSION_UNSUPPORTED, "compilerVersion");
        }
        if (!ScheduleVersions.RUNTIME_SCHEMA_VERSION.equals(runtimeSchemaVersion)) {
            throw ScheduleSupport.invalid(
                    ScheduleErrorCodes.SCHEDULE_VERSION_UNSUPPORTED, "runtimeSchemaVersion");
        }
        Objects.requireNonNull(form, "form");
        ScheduleSupport.requireIdentifier(sourceComponentLogicalId, "sourceComponentLogicalId");
        Objects.requireNonNull(sourcePayloadHash, "sourcePayloadHash");
        Objects.requireNonNull(sourceManifestHash, "sourceManifestHash");
        Objects.requireNonNull(journeyHash, "journeyHash");
        Objects.requireNonNull(scheduleHash, "scheduleHash");

        // Re-prove every field against the declared form, so a record that carries a field its form does not
        // define — or omits one it does — is not representable.
        zoneId = ScheduleSupport.canonicalZone(zoneId);
        switch (form) {
            case IMMEDIATE -> {
                requireAbsent(runAt, "runAt");
                requireAbsent(localTime, "localTime");
                requireAbsent(startDate, "startDate");
                requireAbsent(endDate, "endDate");
            }
            case ONCE -> {
                runAt = ScheduleSupport.canonicalRunAt(runAt, zoneId);
                requireAbsent(localTime, "localTime");
                requireAbsent(startDate, "startDate");
                requireAbsent(endDate, "endDate");
            }
            case DAILY -> {
                requireAbsent(runAt, "runAt");
                String[] window = ScheduleSupport.canonicalWindow(startDate, endDate);
                localTime = ScheduleSupport.canonicalLocalTime(localTime);
                startDate = window[0];
                endDate = window[1];
            }
            default -> throw ScheduleSupport.invalid(
                    ScheduleErrorCodes.SCHEDULE_FORM_UNSUPPORTED, "form");
        }

        if (!scheduleHash.equals(CANONICAL_JSON.hash(material(
                scheduleFormatVersion, compilerVersion, runtimeSchemaVersion, form, zoneId, runAt, localTime,
                startDate, endDate, sourceComponentLogicalId, sourcePayloadHash, sourceManifestHash,
                journeyHash)))) {
            throw ScheduleSupport.invalid(ScheduleErrorCodes.SCHEDULE_INPUT_INVALID, "scheduleHash");
        }
    }

    /**
     * Builds a compiled schedule from its raw declared fields and stamps it with its own canonical hash.
     *
     * <p>The declared fields are canonicalized before they are hashed, so the factory and the record's own
     * re-proof of its bytes agree exactly: a caller never has to know which spelling of a local time or a
     * window bound the compiler considers canonical.
     */
    public static CompiledSchedule of(
            ScheduleBasicForm form,
            String zoneId,
            String runAt,
            String localTime,
            String startDate,
            String endDate,
            String sourceComponentLogicalId,
            ContentHash sourcePayloadHash,
            ContentHash sourceManifestHash,
            ContentHash journeyHash) {
        Objects.requireNonNull(form, "form");
        String canonicalZone = ScheduleSupport.canonicalZone(zoneId);
        String canonicalRunAt = null;
        String canonicalLocalTime = null;
        String canonicalStart = null;
        String canonicalEnd = null;
        switch (form) {
            case IMMEDIATE -> {
                // No form-specific field: the form is the whole of the schedule.
            }
            case ONCE -> canonicalRunAt = ScheduleSupport.canonicalRunAt(runAt, canonicalZone);
            case DAILY -> {
                String[] window = ScheduleSupport.canonicalWindow(startDate, endDate);
                canonicalLocalTime = ScheduleSupport.canonicalLocalTime(localTime);
                canonicalStart = window[0];
                canonicalEnd = window[1];
            }
            default -> throw ScheduleSupport.invalid(
                    ScheduleErrorCodes.SCHEDULE_FORM_UNSUPPORTED, "form");
        }
        return new CompiledSchedule(
                ScheduleVersions.SCHEDULE_FORMAT_VERSION,
                ScheduleVersions.SCHEDULE_COMPILER_VERSION,
                ScheduleVersions.RUNTIME_SCHEMA_VERSION,
                form, canonicalZone, canonicalRunAt, canonicalLocalTime, canonicalStart, canonicalEnd,
                sourceComponentLogicalId, sourcePayloadHash, sourceManifestHash, journeyHash,
                CANONICAL_JSON.hash(material(
                        ScheduleVersions.SCHEDULE_FORMAT_VERSION,
                        ScheduleVersions.SCHEDULE_COMPILER_VERSION,
                        ScheduleVersions.RUNTIME_SCHEMA_VERSION,
                        form, canonicalZone, canonicalRunAt, canonicalLocalTime, canonicalStart, canonicalEnd,
                        sourceComponentLogicalId, sourcePayloadHash, sourceManifestHash, journeyHash)));
    }

    /** The canonical zone rules the schedule is defined in. */
    public java.time.ZoneId zone() {
        return java.time.ZoneId.of(zoneId);
    }

    /** The one nominal instant of an {@code ONCE} schedule, or {@code null} for another form. */
    public Instant runAtInstant() {
        return runAt == null ? null : OffsetDateTime.parse(runAt).toInstant();
    }

    /** The local time of a {@code DAILY} schedule, or {@code null} for another form. */
    public LocalTime localTimeValue() {
        return localTime == null ? null : LocalTime.parse(localTime);
    }

    /** The inclusive first local date of a {@code DAILY} schedule, or {@code null} for another form. */
    public LocalDate startDateValue() {
        return startDate == null ? null : LocalDate.parse(startDate);
    }

    /** The inclusive last local date of a {@code DAILY} schedule, or {@code null} for another form. */
    public LocalDate endDateValue() {
        return endDate == null ? null : LocalDate.parse(endDate);
    }

    /** The canonical UTF-8 bytes every schedule semantic is covered by. */
    public byte[] canonicalBytes() {
        return CANONICAL_JSON.write(material(
                scheduleFormatVersion, compilerVersion, runtimeSchemaVersion, form, zoneId, runAt, localTime,
                startDate, endDate, sourceComponentLogicalId, sourcePayloadHash, sourceManifestHash,
                journeyHash));
    }

    private static void requireAbsent(String value, String invariant) {
        if (value != null) {
            // A field the declared form does not define is a contradictory schedule, not an optional extra.
            throw ScheduleSupport.invalid(ScheduleErrorCodes.SCHEDULE_FIELD_INVALID, invariant);
        }
    }

    /**
     * The hash material without the hash itself.
     *
     * <p>It is a static form because a record's compact constructor runs before its fields are assigned: the
     * constructor must hash the canonicalized parameters it was given, not the fields.
     */
    private static Material material(
            String scheduleFormatVersion,
            String compilerVersion,
            String runtimeSchemaVersion,
            ScheduleBasicForm form,
            String zoneId,
            String runAt,
            String localTime,
            String startDate,
            String endDate,
            String sourceComponentLogicalId,
            ContentHash sourcePayloadHash,
            ContentHash sourceManifestHash,
            ContentHash journeyHash) {
        return new Material(scheduleFormatVersion, compilerVersion, runtimeSchemaVersion, form, zoneId, runAt,
                localTime, startDate, endDate, sourceComponentLogicalId, sourcePayloadHash, sourceManifestHash,
                journeyHash);
    }

    /** Redacted diagnostics: only already-safe machine facts are printed. */
    @Override
    public String toString() {
        return "CompiledSchedule[form=" + form
                + ", zone=" + zoneId
                + ", compilerVersion=" + compilerVersion
                + ", scheduleHash=" + scheduleHash + ']';
    }

    /** Package-private hash material; the optional form fields are omitted when absent. */
    record Material(
            String scheduleFormatVersion,
            String compilerVersion,
            String runtimeSchemaVersion,
            ScheduleBasicForm form,
            String zoneId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String runAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) String localTime,
            @JsonInclude(JsonInclude.Include.NON_NULL) String startDate,
            @JsonInclude(JsonInclude.Include.NON_NULL) String endDate,
            String sourceComponentLogicalId,
            ContentHash sourcePayloadHash,
            ContentHash sourceManifestHash,
            ContentHash journeyHash) {
    }
}