package com.company.campaign.fullpublish;

import com.company.campaign.builder.capability.schedule.ScheduleBasicForm;
import com.company.campaign.builder.capability.schedule.ScheduleBasicVersions;

import java.util.Map;
import java.util.Optional;

/**
 * The deterministic, pure T21 schedule compiler.
 *
 * <p>It is a pure function of one explicit {@link ScheduleCompileRequest}: the same request always yields
 * byte-identical canonical material and schedule hash, and any form, zone, declared field, source payload,
 * source Manifest or Journey change changes the schedule hash. It reads no repository, clock, system
 * timezone, random source, ambient thread-local state, model, network or provider, and it holds no state
 * between compiles.
 *
 * <p>It accepts exactly the three accepted T19 forms — {@code IMMEDIATE} with a canonical IANA zone,
 * {@code ONCE} with a canonical offset instant that matches that zone, and {@code DAILY} with a canonical
 * local time, zone and an inclusive, bounded calendar-date window — and nothing else. Unknown fields, unknown
 * forms, non-canonical zones, mismatched offsets, inverted or over-long windows and wrongly typed values all
 * fail closed with one ordered, bounded machine code that never echoes the rejected value.
 *
 * <p>It generates no occurrence, resolves no daylight-saving transition, creates no scheduling work, computes
 * no execution key and models no pause or replacement behaviour: those belong to the occurrence planner and to
 * the durable scheduling layer, and a compile here claims none of them.
 */
public final class ScheduleCompiler {

    /** Compiles one schedule from its explicit frozen inputs, or fails closed. */
    public CompiledSchedule compile(ScheduleCompileRequest request) {
        if (request == null) {
            throw ScheduleSupport.invalid(ScheduleErrorCodes.SCHEDULE_INPUT_INVALID, "request");
        }
        Map<String, Object> fields = request.payload().fields();
        requireKnownFields(fields);

        ScheduleBasicForm form = requireForm(fields);
        String zoneId = ScheduleSupport.canonicalZone(text(fields, ScheduleBasicVersions.FIELD_ZONE_ID));

        String runAt = null;
        String localTime = null;
        String startDate = null;
        String endDate = null;
        switch (form) {
            case IMMEDIATE -> requireAbsent(fields, ScheduleBasicVersions.FIELD_RUN_AT,
                    ScheduleBasicVersions.FIELD_LOCAL_TIME, ScheduleBasicVersions.FIELD_START_DATE,
                    ScheduleBasicVersions.FIELD_END_DATE);
            case ONCE -> {
                runAt = text(fields, ScheduleBasicVersions.FIELD_RUN_AT);
                requireAbsent(fields, ScheduleBasicVersions.FIELD_LOCAL_TIME,
                        ScheduleBasicVersions.FIELD_START_DATE, ScheduleBasicVersions.FIELD_END_DATE);
            }
            case DAILY -> {
                localTime = text(fields, ScheduleBasicVersions.FIELD_LOCAL_TIME);
                startDate = text(fields, ScheduleBasicVersions.FIELD_START_DATE);
                endDate = text(fields, ScheduleBasicVersions.FIELD_END_DATE);
                requireAbsent(fields, ScheduleBasicVersions.FIELD_RUN_AT);
            }
            default -> throw ScheduleSupport.invalid(
                    ScheduleErrorCodes.SCHEDULE_FORM_UNSUPPORTED, "form");
        }

        return CompiledSchedule.of(
                form, zoneId, runAt, localTime, startDate, endDate,
                request.componentLogicalId(), request.payload().contentHash(),
                request.sourceManifestHash(), request.journeyHash());
    }

    private static void requireKnownFields(Map<String, Object> fields) {
        for (String name : fields.keySet()) {
            if (!ScheduleCompileRequest.declaredFields().contains(name)) {
                // An undeclared field fails closed: it is neither silently ignored nor carried into a schedule.
                throw ScheduleSupport.invalid(ScheduleErrorCodes.SCHEDULE_UNKNOWN_FIELD, "payload");
            }
        }
    }

    private static ScheduleBasicForm requireForm(Map<String, Object> fields) {
        String declared = text(fields, ScheduleBasicVersions.FIELD_FORM);
        if (declared == null || declared.isEmpty() || declared.length() > 32) {
            throw ScheduleSupport.invalid(ScheduleErrorCodes.SCHEDULE_FIELD_INVALID, "form");
        }
        Optional<ScheduleBasicForm> form = ScheduleBasicForm.parse(declared);
        if (form.isEmpty()) {
            throw ScheduleSupport.invalid(ScheduleErrorCodes.SCHEDULE_FORM_UNSUPPORTED, "form");
        }
        return form.get();
    }

    /** The declared text of one field, or {@code null} when it is absent or not of the exact string type. */
    private static String text(Map<String, Object> fields, String name) {
        Object value = fields.get(name);
        return value instanceof String text ? text : null;
    }

    private static void requireAbsent(Map<String, Object> fields, String... names) {
        for (String name : names) {
            if (fields.containsKey(name)) {
                // A field the declared form does not define is a contradictory payload, not an extra.
                throw ScheduleSupport.invalid(ScheduleErrorCodes.SCHEDULE_FIELD_INVALID, name);
            }
        }
    }

    @Override
    public String toString() {
        return "ScheduleCompiler[versions=" + ScheduleVersions.SCHEDULE_FORMAT_VERSION
                + ", compiler=" + ScheduleVersions.SCHEDULE_COMPILER_VERSION + ']';
    }
}