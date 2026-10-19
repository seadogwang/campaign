package com.company.campaign.fullpublish;

import com.company.campaign.builder.capability.schedule.ScheduleBasicVersions;
import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** Focused T21 section-3 tests: the deterministic, pure, fail-closed {@code schedule.basic/1.0} compiler. */
class ScheduleCompilerTest {

    private static final String ZONE = "Asia/Shanghai";
    private static final String COMPONENT = "schedule-0001";

    private final ScheduleCompiler compiler = new ScheduleCompiler();

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> payload(Object... pairs) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            fields.put((String) pairs[index], pairs[index + 1]);
        }
        return fields;
    }

    private static ScheduleCompileRequest request(Map<String, Object> fields) {
        return new ScheduleCompileRequest(
                COMPONENT, ScheduleVersions.CAPABILITY_CODE, ScheduleVersions.CONTRACT_VERSION,
                CapabilityPayload.of(fields), hash("manifest"), hash("journey"),
                ScheduleVersions.SCHEDULE_FORMAT_VERSION, ScheduleVersions.SCHEDULE_COMPILER_VERSION,
                ScheduleVersions.RUNTIME_SCHEMA_VERSION);
    }

    private static ContentHash hash(String seed) {
        return ContentHash.sha256(seed.getBytes(StandardCharsets.UTF_8));
    }

    private CompiledSchedule compile(Map<String, Object> fields) {
        return compiler.compile(request(fields));
    }

    private static String codeOf(Runnable runnable) {
        return catchThrowableOfType(runnable::run, ScheduleException.class).code();
    }

    // ------------------------------------------------------------------ the three accepted forms

    @Test
    void anImmediateScheduleCarriesOnlyItsCanonicalZone() {
        CompiledSchedule schedule = compile(payload(
                ScheduleBasicVersions.FIELD_FORM, "IMMEDIATE",
                ScheduleBasicVersions.FIELD_ZONE_ID, ZONE));

        assertThat(schedule.form()).isEqualTo(com.company.campaign.builder.capability.schedule.ScheduleBasicForm.IMMEDIATE);
        assertThat(schedule.zoneId()).isEqualTo(ZONE);
        assertThat(schedule.runAt()).isNull();
        assertThat(schedule.localTime()).isNull();
        assertThat(schedule.startDate()).isNull();
        assertThat(schedule.endDate()).isNull();
        assertThat(schedule.scheduleFormatVersion()).isEqualTo(ScheduleVersions.SCHEDULE_FORMAT_VERSION);
        assertThat(schedule.compilerVersion()).isEqualTo(ScheduleVersions.SCHEDULE_COMPILER_VERSION);
        assertThat(schedule.runtimeSchemaVersion()).isEqualTo(ScheduleVersions.RUNTIME_SCHEMA_VERSION);
        assertThat(schedule.sourceComponentLogicalId()).isEqualTo(COMPONENT);
        assertThat(schedule.sourceManifestHash()).isEqualTo(hash("manifest"));
        assertThat(schedule.journeyHash()).isEqualTo(hash("journey"));
        assertThat(schedule.scheduleHash()).isNotNull();
    }

    @Test
    void anOnceScheduleFreezesItsCanonicalOffsetInstant() {
        CompiledSchedule schedule = compile(payload(
                ScheduleBasicVersions.FIELD_FORM, "ONCE",
                ScheduleBasicVersions.FIELD_ZONE_ID, ZONE,
                ScheduleBasicVersions.FIELD_RUN_AT, "2026-11-01T09:00:00+08:00"));

        assertThat(schedule.runAt()).isEqualTo("2026-11-01T09:00:00+08:00");
        assertThat(schedule.runAtInstant()).isEqualTo(java.time.Instant.parse("2026-11-01T01:00:00Z"));
        assertThat(schedule.localTime()).isNull();
    }

    @Test
    void aDailyScheduleFreezesItsInclusiveWindowAndLocalTime() {
        CompiledSchedule schedule = compile(payload(
                ScheduleBasicVersions.FIELD_FORM, "DAILY",
                ScheduleBasicVersions.FIELD_ZONE_ID, ZONE,
                ScheduleBasicVersions.FIELD_LOCAL_TIME, "09:00",
                ScheduleBasicVersions.FIELD_START_DATE, "2026-11-01",
                ScheduleBasicVersions.FIELD_END_DATE, "2026-11-30"));

        assertThat(schedule.localTime()).isEqualTo("09:00");
        assertThat(schedule.startDate()).isEqualTo("2026-11-01");
        assertThat(schedule.endDate()).isEqualTo("2026-11-30");
        assertThat(schedule.startDateValue()).isEqualTo(java.time.LocalDate.parse("2026-11-01"));
        assertThat(schedule.runAt()).isNull();
    }

    // ------------------------------------------------------------------ determinism and hash binding

    @Test
    void identicalPayloadsProduceByteIdenticalMaterialAndHash() {
        Map<String, Object> fields = payload(
                ScheduleBasicVersions.FIELD_FORM, "DAILY",
                ScheduleBasicVersions.FIELD_ZONE_ID, ZONE,
                ScheduleBasicVersions.FIELD_LOCAL_TIME, "09:00",
                ScheduleBasicVersions.FIELD_START_DATE, "2026-11-01",
                ScheduleBasicVersions.FIELD_END_DATE, "2026-11-30");

        CompiledSchedule first = compile(fields);
        for (int attempt = 0; attempt < 50; attempt++) {
            CompiledSchedule candidate = compile(fields);
            assertThat(candidate.scheduleHash()).isEqualTo(first.scheduleHash());
            assertThat(candidate.canonicalBytes()).isEqualTo(first.canonicalBytes());
        }
    }

    @Test
    void everyMaterialChangeChangesTheScheduleHash() {
        CompiledSchedule base = compile(payload(
                ScheduleBasicVersions.FIELD_FORM, "DAILY",
                ScheduleBasicVersions.FIELD_ZONE_ID, ZONE,
                ScheduleBasicVersions.FIELD_LOCAL_TIME, "09:00",
                ScheduleBasicVersions.FIELD_START_DATE, "2026-11-01",
                ScheduleBasicVersions.FIELD_END_DATE, "2026-11-30"));

        assertThat(compile(payload(
                ScheduleBasicVersions.FIELD_FORM, "DAILY",
                ScheduleBasicVersions.FIELD_ZONE_ID, ZONE,
                ScheduleBasicVersions.FIELD_LOCAL_TIME, "10:00",
                ScheduleBasicVersions.FIELD_START_DATE, "2026-11-01",
                ScheduleBasicVersions.FIELD_END_DATE, "2026-11-30")).scheduleHash())
                .isNotEqualTo(base.scheduleHash());
        assertThat(compile(payload(
                ScheduleBasicVersions.FIELD_FORM, "DAILY",
                ScheduleBasicVersions.FIELD_ZONE_ID, "Asia/Tokyo",
                ScheduleBasicVersions.FIELD_LOCAL_TIME, "09:00",
                ScheduleBasicVersions.FIELD_START_DATE, "2026-11-01",
                ScheduleBasicVersions.FIELD_END_DATE, "2026-11-30")).scheduleHash())
                .isNotEqualTo(base.scheduleHash());
        assertThat(compile(payload(
                ScheduleBasicVersions.FIELD_FORM, "DAILY",
                ScheduleBasicVersions.FIELD_ZONE_ID, ZONE,
                ScheduleBasicVersions.FIELD_LOCAL_TIME, "09:00",
                ScheduleBasicVersions.FIELD_START_DATE, "2026-11-02",
                ScheduleBasicVersions.FIELD_END_DATE, "2026-11-30")).scheduleHash())
                .isNotEqualTo(base.scheduleHash());
    }

    @Test
    void aSourceIdentityChangeChangesTheScheduleHash() {
        Map<String, Object> fields = payload(
                ScheduleBasicVersions.FIELD_FORM, "IMMEDIATE",
                ScheduleBasicVersions.FIELD_ZONE_ID, ZONE);
        ScheduleCompileRequest first = request(fields);
        ScheduleCompileRequest other = new ScheduleCompileRequest(
                "schedule-0002", ScheduleVersions.CAPABILITY_CODE, ScheduleVersions.CONTRACT_VERSION,
                CapabilityPayload.of(fields), hash("manifest"), hash("journey"),
                ScheduleVersions.SCHEDULE_FORMAT_VERSION, ScheduleVersions.SCHEDULE_COMPILER_VERSION,
                ScheduleVersions.RUNTIME_SCHEMA_VERSION);

        assertThat(compiler.compile(other).scheduleHash())
                .isNotEqualTo(compiler.compile(first).scheduleHash());
    }

    // ------------------------------------------------------------------ fail-closed rejections

    @Test
    void anUnknownFieldIsRefused() {
        assertThat(codeOf(() -> compile(payload(
                ScheduleBasicVersions.FIELD_FORM, "IMMEDIATE",
                ScheduleBasicVersions.FIELD_ZONE_ID, ZONE,
                "unexpectedSetting", "x"))))
                .isEqualTo(ScheduleErrorCodes.SCHEDULE_UNKNOWN_FIELD);
    }

    @Test
    void anUnknownOrMissingFormIsRefused() {
        assertThat(codeOf(() -> compile(payload(
                ScheduleBasicVersions.FIELD_FORM, "WEEKLY",
                ScheduleBasicVersions.FIELD_ZONE_ID, ZONE))))
                .isEqualTo(ScheduleErrorCodes.SCHEDULE_FORM_UNSUPPORTED);
        assertThat(codeOf(() -> compile(payload(
                ScheduleBasicVersions.FIELD_ZONE_ID, ZONE))))
                .isEqualTo(ScheduleErrorCodes.SCHEDULE_FIELD_INVALID);
    }

    @Test
    void aNonCanonicalZoneIsRefused() {
        assertThat(codeOf(() -> compile(payload(
                ScheduleBasicVersions.FIELD_FORM, "IMMEDIATE",
                ScheduleBasicVersions.FIELD_ZONE_ID, "+08:00"))))
                .isEqualTo(ScheduleErrorCodes.SCHEDULE_ZONE_INVALID);
        assertThat(codeOf(() -> compile(payload(
                ScheduleBasicVersions.FIELD_FORM, "IMMEDIATE",
                ScheduleBasicVersions.FIELD_ZONE_ID, "Not/AZone"))))
                .isEqualTo(ScheduleErrorCodes.SCHEDULE_ZONE_INVALID);
        assertThat(codeOf(() -> compile(payload(
                ScheduleBasicVersions.FIELD_FORM, "IMMEDIATE",
                ScheduleBasicVersions.FIELD_ZONE_ID, ""))))
                .isEqualTo(ScheduleErrorCodes.SCHEDULE_FIELD_INVALID);
    }

    @Test
    void anOffsetThatContradictsItsZoneIsRefused() {
        assertThat(codeOf(() -> compile(payload(
                ScheduleBasicVersions.FIELD_FORM, "ONCE",
                ScheduleBasicVersions.FIELD_ZONE_ID, ZONE,
                ScheduleBasicVersions.FIELD_RUN_AT, "2026-11-01T09:00:00+09:00"))))
                .isEqualTo(ScheduleErrorCodes.SCHEDULE_WINDOW_INVALID);
    }

    @Test
    void aMalformedInstantOrLocalTimeIsRefused() {
        assertThat(codeOf(() -> compile(payload(
                ScheduleBasicVersions.FIELD_FORM, "ONCE",
                ScheduleBasicVersions.FIELD_ZONE_ID, ZONE,
                ScheduleBasicVersions.FIELD_RUN_AT, "not-an-instant"))))
                .isEqualTo(ScheduleErrorCodes.SCHEDULE_FIELD_INVALID);
        assertThat(codeOf(() -> compile(payload(
                ScheduleBasicVersions.FIELD_FORM, "DAILY",
                ScheduleBasicVersions.FIELD_ZONE_ID, ZONE,
                ScheduleBasicVersions.FIELD_LOCAL_TIME, "25:00",
                ScheduleBasicVersions.FIELD_START_DATE, "2026-11-01",
                ScheduleBasicVersions.FIELD_END_DATE, "2026-11-30"))))
                .isEqualTo(ScheduleErrorCodes.SCHEDULE_FIELD_INVALID);
    }

    @Test
    void anInvertedOrOverLongWindowIsRefused() {
        assertThat(codeOf(() -> compile(payload(
                ScheduleBasicVersions.FIELD_FORM, "DAILY",
                ScheduleBasicVersions.FIELD_ZONE_ID, ZONE,
                ScheduleBasicVersions.FIELD_LOCAL_TIME, "09:00",
                ScheduleBasicVersions.FIELD_START_DATE, "2026-11-30",
                ScheduleBasicVersions.FIELD_END_DATE, "2026-11-01"))))
                .isEqualTo(ScheduleErrorCodes.SCHEDULE_WINDOW_INVALID);
        // Ninety-one inclusive calendar days is outside the accepted T19 frozen bound.
        assertThat(codeOf(() -> compile(payload(
                ScheduleBasicVersions.FIELD_FORM, "DAILY",
                ScheduleBasicVersions.FIELD_ZONE_ID, ZONE,
                ScheduleBasicVersions.FIELD_LOCAL_TIME, "09:00",
                ScheduleBasicVersions.FIELD_START_DATE, "2026-01-01",
                ScheduleBasicVersions.FIELD_END_DATE, "2026-04-01"))))
                .isEqualTo(ScheduleErrorCodes.SCHEDULE_WINDOW_INVALID);
    }

    @Test
    void exactlyNinetyInclusiveDaysIsAccepted() {
        CompiledSchedule schedule = compile(payload(
                ScheduleBasicVersions.FIELD_FORM, "DAILY",
                ScheduleBasicVersions.FIELD_ZONE_ID, ZONE,
                ScheduleBasicVersions.FIELD_LOCAL_TIME, "09:00",
                ScheduleBasicVersions.FIELD_START_DATE, "2026-01-01",
                ScheduleBasicVersions.FIELD_END_DATE, "2026-03-31"));

        assertThat(schedule.endDateValue()).isEqualTo(java.time.LocalDate.parse("2026-03-31"));
    }

    @Test
    void aFieldTheFormDoesNotDefineIsRefused() {
        assertThat(codeOf(() -> compile(payload(
                ScheduleBasicVersions.FIELD_FORM, "IMMEDIATE",
                ScheduleBasicVersions.FIELD_ZONE_ID, ZONE,
                ScheduleBasicVersions.FIELD_LOCAL_TIME, "09:00"))))
                .isEqualTo(ScheduleErrorCodes.SCHEDULE_FIELD_INVALID);
        assertThat(codeOf(() -> compile(payload(
                ScheduleBasicVersions.FIELD_FORM, "ONCE",
                ScheduleBasicVersions.FIELD_ZONE_ID, ZONE,
                ScheduleBasicVersions.FIELD_RUN_AT, "2026-11-01T09:00:00+08:00",
                ScheduleBasicVersions.FIELD_START_DATE, "2026-11-01"))))
                .isEqualTo(ScheduleErrorCodes.SCHEDULE_FIELD_INVALID);
        assertThat(codeOf(() -> compile(payload(
                ScheduleBasicVersions.FIELD_FORM, "DAILY",
                ScheduleBasicVersions.FIELD_ZONE_ID, ZONE,
                ScheduleBasicVersions.FIELD_LOCAL_TIME, "09:00",
                ScheduleBasicVersions.FIELD_START_DATE, "2026-11-01",
                ScheduleBasicVersions.FIELD_END_DATE, "2026-11-30",
                ScheduleBasicVersions.FIELD_RUN_AT, "2026-11-01T09:00:00+08:00"))))
                .isEqualTo(ScheduleErrorCodes.SCHEDULE_FIELD_INVALID);
    }

    @Test
    void aWronglyTypedFieldIsRefused() {
        assertThat(codeOf(() -> compile(payload(
                ScheduleBasicVersions.FIELD_FORM, 1,
                ScheduleBasicVersions.FIELD_ZONE_ID, ZONE))))
                .isEqualTo(ScheduleErrorCodes.SCHEDULE_FIELD_INVALID);
        assertThat(codeOf(() -> compile(payload(
                ScheduleBasicVersions.FIELD_FORM, "IMMEDIATE",
                ScheduleBasicVersions.FIELD_ZONE_ID, 42))))
                .isEqualTo(ScheduleErrorCodes.SCHEDULE_FIELD_INVALID);
    }

    @Test
    void anUnsupportedRequestVersionOrCapabilityIsRefused() {
        Map<String, Object> fields = payload(
                ScheduleBasicVersions.FIELD_FORM, "IMMEDIATE",
                ScheduleBasicVersions.FIELD_ZONE_ID, ZONE);

        assertThatThrownBy(() -> new ScheduleCompileRequest(
                COMPONENT, "schedule.other", ScheduleVersions.CONTRACT_VERSION,
                CapabilityPayload.of(fields), hash("manifest"), hash("journey"),
                ScheduleVersions.SCHEDULE_FORMAT_VERSION, ScheduleVersions.SCHEDULE_COMPILER_VERSION,
                ScheduleVersions.RUNTIME_SCHEMA_VERSION))
                .isInstanceOf(ScheduleException.class)
                .hasFieldOrPropertyWithValue("code", ScheduleErrorCodes.SCHEDULE_CAPABILITY_UNSUPPORTED);
        assertThatThrownBy(() -> new ScheduleCompileRequest(
                COMPONENT, ScheduleVersions.CAPABILITY_CODE, "2.0",
                CapabilityPayload.of(fields), hash("manifest"), hash("journey"),
                ScheduleVersions.SCHEDULE_FORMAT_VERSION, ScheduleVersions.SCHEDULE_COMPILER_VERSION,
                ScheduleVersions.RUNTIME_SCHEMA_VERSION))
                .isInstanceOf(ScheduleException.class)
                .hasFieldOrPropertyWithValue("code", ScheduleErrorCodes.SCHEDULE_CAPABILITY_UNSUPPORTED);
        assertThatThrownBy(() -> new ScheduleCompileRequest(
                COMPONENT, ScheduleVersions.CAPABILITY_CODE, ScheduleVersions.CONTRACT_VERSION,
                CapabilityPayload.of(fields), hash("manifest"), hash("journey"),
                "campaign-schedule/9.9", ScheduleVersions.SCHEDULE_COMPILER_VERSION,
                ScheduleVersions.RUNTIME_SCHEMA_VERSION))
                .isInstanceOf(ScheduleException.class)
                .hasFieldOrPropertyWithValue("code", ScheduleErrorCodes.SCHEDULE_VERSION_UNSUPPORTED);
        assertThatThrownBy(() -> new ScheduleCompileRequest(
                COMPONENT, ScheduleVersions.CAPABILITY_CODE, ScheduleVersions.CONTRACT_VERSION,
                CapabilityPayload.of(fields), hash("manifest"), hash("journey"),
                ScheduleVersions.SCHEDULE_FORMAT_VERSION, "schedule-compiler/9.9",
                ScheduleVersions.RUNTIME_SCHEMA_VERSION))
                .isInstanceOf(ScheduleException.class)
                .hasFieldOrPropertyWithValue(
                        "code", ScheduleErrorCodes.SCHEDULE_COMPILER_VERSION_UNSUPPORTED);
        assertThatThrownBy(() -> new ScheduleCompileRequest(
                COMPONENT, ScheduleVersions.CAPABILITY_CODE, ScheduleVersions.CONTRACT_VERSION,
                CapabilityPayload.of(fields), hash("manifest"), hash("journey"),
                ScheduleVersions.SCHEDULE_FORMAT_VERSION, ScheduleVersions.SCHEDULE_COMPILER_VERSION,
                "campaign-runtime/9.9"))
                .isInstanceOf(ScheduleException.class)
                .hasFieldOrPropertyWithValue("code", ScheduleErrorCodes.SCHEDULE_VERSION_UNSUPPORTED);
    }

    @Test
    void aNullRequestIsRefused() {
        assertThat(codeOf(() -> compiler.compile(null)))
                .isEqualTo(ScheduleErrorCodes.SCHEDULE_INPUT_INVALID);
    }

    @Test
    void theCompiledRecordReProvesItsOwnHash() {
        CompiledSchedule schedule = compile(payload(
                ScheduleBasicVersions.FIELD_FORM, "IMMEDIATE",
                ScheduleBasicVersions.FIELD_ZONE_ID, ZONE));

        assertThatThrownBy(() -> new CompiledSchedule(
                schedule.scheduleFormatVersion(), schedule.compilerVersion(), schedule.runtimeSchemaVersion(),
                schedule.form(), schedule.zoneId(), null, null, null, null,
                schedule.sourceComponentLogicalId(), schedule.sourcePayloadHash(),
                schedule.sourceManifestHash(), schedule.journeyHash(), hash("tampered")))
                .isInstanceOf(ScheduleException.class)
                .hasFieldOrPropertyWithValue("code", ScheduleErrorCodes.SCHEDULE_INPUT_INVALID);
    }
}