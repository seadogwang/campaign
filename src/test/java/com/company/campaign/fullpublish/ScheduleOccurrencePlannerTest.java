package com.company.campaign.fullpublish;

import com.company.campaign.builder.capability.schedule.ScheduleBasicVersions;
import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** Focused T21 section-4 tests: the pure, bounded, deterministic occurrence planner. */
class ScheduleOccurrencePlannerTest {

    private static final String PUBLISHED_ID = "full-definition-0001";
    private static final long REVISION = 1L;
    private static final ContentHash DEFINITION_HASH = hash("definition");
    private static final Instant PUBLISHED_AT = Instant.parse("2026-11-01T00:00:00Z");

    private final ScheduleOccurrencePlanner planner = new ScheduleOccurrencePlanner();

    // ------------------------------------------------------------------ helpers

    private static ContentHash hash(String seed) {
        return ContentHash.sha256(seed.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> payload(Object... pairs) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            fields.put((String) pairs[index], pairs[index + 1]);
        }
        return fields;
    }

    private static CompiledSchedule schedule(Map<String, Object> fields) {
        return new ScheduleCompiler().compile(new ScheduleCompileRequest(
                "schedule-0001", ScheduleVersions.CAPABILITY_CODE, ScheduleVersions.CONTRACT_VERSION,
                CapabilityPayload.of(fields), hash("manifest"), hash("journey"),
                ScheduleVersions.SCHEDULE_FORMAT_VERSION, ScheduleVersions.SCHEDULE_COMPILER_VERSION,
                ScheduleVersions.RUNTIME_SCHEMA_VERSION));
    }

    private static CompiledSchedule immediate(String zone) {
        return schedule(payload(
                ScheduleBasicVersions.FIELD_FORM, "IMMEDIATE",
                ScheduleBasicVersions.FIELD_ZONE_ID, zone));
    }

    private static CompiledSchedule once(String zone, String runAt) {
        return schedule(payload(
                ScheduleBasicVersions.FIELD_FORM, "ONCE",
                ScheduleBasicVersions.FIELD_ZONE_ID, zone,
                ScheduleBasicVersions.FIELD_RUN_AT, runAt));
    }

    private static CompiledSchedule daily(String zone, String localTime, String start, String end) {
        return schedule(payload(
                ScheduleBasicVersions.FIELD_FORM, "DAILY",
                ScheduleBasicVersions.FIELD_ZONE_ID, zone,
                ScheduleBasicVersions.FIELD_LOCAL_TIME, localTime,
                ScheduleBasicVersions.FIELD_START_DATE, start,
                ScheduleBasicVersions.FIELD_END_DATE, end));
    }

    private static OccurrencePlanningRequest request(
            CompiledSchedule schedule, Instant asOf, List<String> emitted) {
        return new OccurrencePlanningRequest(
                PUBLISHED_ID, REVISION, DEFINITION_HASH, schedule, PUBLISHED_AT, asOf, emitted);
    }

    // ------------------------------------------------------------------ IMMEDIATE

    @Test
    void immediateIsDueAtThePublicationInstantAndCatchesUpOnce() {
        CompiledSchedule schedule = immediate("Asia/Shanghai");

        OccurrencePlan onTime = planner.plan(request(schedule, PUBLISHED_AT, List.of()));
        assertThat(onTime.occurrences()).hasSize(1);
        assertThat(onTime.due()).hasSize(1);
        assertThat(onTime.occurrences().get(0).nominalInstant()).isEqualTo(PUBLISHED_AT);
        assertThat(onTime.occurrences().get(0).reasonCode()).isEqualTo(ScheduleVersions.REASON_DUE);
        assertThat(onTime.occurrences().get(0).localDate()).isNull();

        OccurrencePlan caughtUp =
                planner.plan(request(schedule, PUBLISHED_AT.plusSeconds(3600), List.of()));
        assertThat(caughtUp.due()).hasSize(1);
        assertThat(caughtUp.occurrences().get(0).nominalInstant()).isEqualTo(PUBLISHED_AT);
        assertThat(caughtUp.occurrences().get(0).reasonCode())
                .isEqualTo(ScheduleVersions.REASON_DUE_CATCH_UP);

        // An already-emitted immediate occurrence is never recreated.
        String key = caughtUp.occurrences().get(0).executionKey();
        assertThat(planner.plan(request(schedule, PUBLISHED_AT.plusSeconds(7200), List.of(key)))
                .occurrences()).isEmpty();
    }

    // ------------------------------------------------------------------ ONCE

    @Test
    void onceRemainsPendingUntilItsInstantAndThenCatchesUp() {
        CompiledSchedule schedule = once("Asia/Shanghai", "2026-11-02T09:00:00+08:00");
        Instant runAt = Instant.parse("2026-11-02T01:00:00Z");

        assertThat(planner.plan(request(schedule, PUBLISHED_AT, List.of())).occurrences()).isEmpty();

        OccurrencePlan due = planner.plan(request(schedule, runAt, List.of()));
        assertThat(due.due()).hasSize(1);
        assertThat(due.occurrences().get(0).nominalInstant()).isEqualTo(runAt);
        assertThat(due.occurrences().get(0).reasonCode()).isEqualTo(ScheduleVersions.REASON_DUE);

        OccurrencePlan caughtUp = planner.plan(request(schedule, runAt.plusSeconds(600), List.of()));
        assertThat(caughtUp.due()).hasSize(1);
        assertThat(caughtUp.occurrences().get(0).reasonCode())
                .isEqualTo(ScheduleVersions.REASON_DUE_CATCH_UP);
    }

    // ------------------------------------------------------------------ DAILY

    @Test
    void dailyIsInclusiveAndOnePerLocalDate() {
        CompiledSchedule schedule = daily("Asia/Shanghai", "09:00", "2026-11-01", "2026-11-05");

        // 2026-11-05 09:00 Asia/Shanghai is 2026-11-05T01:00Z, so every one of the five dates is due.
        OccurrencePlan plan = planner.plan(request(schedule, Instant.parse("2026-11-05T01:00:00Z"), List.of()));

        assertThat(plan.occurrences()).hasSize(5);
        assertThat(plan.due()).hasSize(1);
        assertThat(plan.occurrences().stream()
                .map(ScheduledOccurrence::localDate).distinct().toList()).hasSize(5);
        assertThat(plan.due().get(0).localDate()).isEqualTo("2026-11-05");
        assertThat(plan.occurrences().stream()
                .filter(occurrence -> !occurrence.decision().due())
                .allMatch(occurrence -> occurrence.reasonCode().equals(ScheduleVersions.REASON_SKIPPED)))
                .isTrue();
    }

    @Test
    void dailyFutureDatesStayPendingAndCreateNoWork() {
        CompiledSchedule schedule = daily("Asia/Shanghai", "09:00", "2026-11-01", "2026-11-05");

        assertThat(planner.plan(request(schedule, Instant.parse("2026-10-31T23:59:59Z"), List.of()))
                .occurrences()).isEmpty();

        OccurrencePlan first = planner.plan(request(schedule, Instant.parse("2026-11-01T01:00:00Z"), List.of()));
        assertThat(first.occurrences()).hasSize(1);
        assertThat(first.due()).hasSize(1);
        assertThat(first.due().get(0).localDate()).isEqualTo("2026-11-01");
    }

    @Test
    void dailyCatchUpUsesOnlyTheMostRecentUnemittedDateAndSkipsTheRest() {
        CompiledSchedule schedule = daily("Asia/Shanghai", "09:00", "2026-11-01", "2026-11-05");
        OccurrencePlan full = planner.plan(request(schedule, Instant.parse("2026-11-05T01:00:00Z"), List.of()));

        List<String> firstFour = full.occurrences().stream()
                .filter(occurrence -> !occurrence.localDate().equals("2026-11-05"))
                .map(ScheduledOccurrence::executionKey)
                .toList();

        OccurrencePlan caughtUp =
                planner.plan(request(schedule, Instant.parse("2026-11-06T01:00:00Z"), firstFour));

        assertThat(caughtUp.occurrences()).hasSize(1);
        assertThat(caughtUp.due()).hasSize(1);
        assertThat(caughtUp.due().get(0).localDate()).isEqualTo("2026-11-05");
    }

    @Test
    void dailyNeverProducesMoreThanOneDueOccurrencePerEvaluation() {
        CompiledSchedule schedule = daily("Asia/Shanghai", "09:00", "2026-11-01", "2026-11-30");
        OccurrencePlan plan = planner.plan(request(schedule, Instant.parse("2026-12-01T00:00:00Z"), List.of()));

        assertThat(plan.occurrences()).hasSize(30);
        assertThat(plan.due()).hasSize(1);
        assertThat(plan.due().get(0).localDate()).isEqualTo("2026-11-30");
    }

    // ------------------------------------------------------------------ DST

    @Test
    void aNonexistentLocalTimeMovesToTheTransitionsNextValidInstant() {
        // Europe/Paris springs forward on 2026-03-29: 02:30 local does not exist.
        Instant resolved = ScheduleInstants.resolve(
                ZoneId.of("Europe/Paris"), LocalDate.parse("2026-03-29"), LocalTime.parse("02:30"));

        assertThat(resolved).isEqualTo(Instant.parse("2026-03-29T01:00:00Z"));

        CompiledSchedule schedule = daily("Europe/Paris", "02:30", "2026-03-29", "2026-03-29");
        OccurrencePlan plan = planner.plan(request(schedule, Instant.parse("2026-03-29T01:00:00Z"), List.of()));
        assertThat(plan.due()).hasSize(1);
        assertThat(plan.due().get(0).nominalInstant()).isEqualTo(Instant.parse("2026-03-29T01:00:00Z"));
    }

    @Test
    void aRepeatedLocalTimeUsesTheEarlierValidOffset() {
        // Europe/Paris falls back on 2026-10-25: 02:30 local happens twice.
        Instant resolved = ScheduleInstants.resolve(
                ZoneId.of("Europe/Paris"), LocalDate.parse("2026-10-25"), LocalTime.parse("02:30"));

        assertThat(resolved).isEqualTo(Instant.parse("2026-10-25T00:30:00Z"));

        CompiledSchedule schedule = daily("Europe/Paris", "02:30", "2026-10-25", "2026-10-25");
        OccurrencePlan plan = planner.plan(request(schedule, Instant.parse("2026-10-25T00:30:00Z"), List.of()));
        assertThat(plan.due()).hasSize(1);
        assertThat(plan.due().get(0).nominalInstant()).isEqualTo(Instant.parse("2026-10-25T00:30:00Z"));
    }

    // ------------------------------------------------------------------ determinism and keys

    @Test
    void planningIsDeterministicInOrderAndHash() {
        CompiledSchedule schedule = daily("Asia/Shanghai", "09:00", "2026-11-01", "2026-11-05");
        OccurrencePlanningRequest request =
                request(schedule, Instant.parse("2026-11-05T01:00:00Z"), List.of());

        OccurrencePlan first = planner.plan(request);
        for (int attempt = 0; attempt < 50; attempt++) {
            OccurrencePlan candidate = planner.plan(request);
            assertThat(candidate.planHash()).isEqualTo(first.planHash());
            assertThat(candidate.occurrences()).isEqualTo(first.occurrences());
        }
    }

    @Test
    void executionKeysAreStableAndDerivedOnlyFromFrozenIdentity() {
        CompiledSchedule schedule = immediate("Asia/Shanghai");
        String key = ScheduleOccurrencePlanner.executionKey(
                PUBLISHED_ID, REVISION, DEFINITION_HASH, schedule.scheduleHash(), PUBLISHED_AT);

        assertThat(key).startsWith("sha256:");
        assertThat(key).isEqualTo(ScheduleOccurrencePlanner.executionKey(
                PUBLISHED_ID, REVISION, DEFINITION_HASH, schedule.scheduleHash(), PUBLISHED_AT));
        // A superseding version, a moved schedule or a moved instant derives a different key.
        assertThat(key).isNotEqualTo(ScheduleOccurrencePlanner.executionKey(
                PUBLISHED_ID, REVISION + 1, DEFINITION_HASH, schedule.scheduleHash(), PUBLISHED_AT));
        assertThat(key).isNotEqualTo(ScheduleOccurrencePlanner.executionKey(
                PUBLISHED_ID, REVISION, hash("other-definition"), schedule.scheduleHash(), PUBLISHED_AT));
        assertThat(key).isNotEqualTo(ScheduleOccurrencePlanner.executionKey(
                PUBLISHED_ID, REVISION, DEFINITION_HASH, schedule.scheduleHash(), PUBLISHED_AT.plusSeconds(1)));

        OccurrencePlan plan = planner.plan(request(schedule, PUBLISHED_AT, List.of()));
        assertThat(plan.occurrences().get(0).executionKey()).isEqualTo(key);
    }

    @Test
    void alreadyEmittedKeysAreExplicitInputAndNeverTruncated() {
        CompiledSchedule schedule = immediate("Asia/Shanghai");
        List<String> tooMany = new ArrayList<>();
        for (int index = 0; index <= ScheduleVersions.MAX_EMITTED_KEYS; index++) {
            tooMany.add("emitted-key-" + index);
        }
        assertThat(codeOf(() -> new OccurrencePlanningRequest(
                PUBLISHED_ID, REVISION, DEFINITION_HASH, schedule, PUBLISHED_AT, PUBLISHED_AT, tooMany)))
                .isEqualTo(ScheduleErrorCodes.SCHEDULE_EMITTED_KEYS_INVALID);
        assertThat(codeOf(() -> new OccurrencePlanningRequest(
                PUBLISHED_ID, REVISION, DEFINITION_HASH, schedule, PUBLISHED_AT, PUBLISHED_AT,
                List.of("same-key", "same-key"))))
                .isEqualTo(ScheduleErrorCodes.SCHEDULE_EMITTED_KEYS_INVALID);
        assertThat(codeOf(() -> new OccurrencePlanningRequest(
                PUBLISHED_ID, REVISION, DEFINITION_HASH, schedule, PUBLISHED_AT, PUBLISHED_AT,
                List.of("not a bounded key"))))
                .isEqualTo(ScheduleErrorCodes.SCHEDULE_INPUT_INVALID);
    }

    @Test
    void anOccurrenceReProvesItsOwnHashAndBinding() {
        ScheduledOccurrence occurrence = planner
                .plan(request(immediate("Asia/Shanghai"), PUBLISHED_AT, List.of()))
                .occurrences().get(0);

        assertThatThrownBy(() -> ScheduledOccurrence.of(
                occurrence.executionKey(), occurrence.publishedId(), occurrence.publishedRevision(),
                occurrence.publishedDefinitionHash(), occurrence.scheduleHash(), null, occurrence.zoneId(),
                occurrence.nominalInstant(), occurrence.dueInstant(), occurrence.decision(),
                "not a machine code"))
                .isInstanceOf(ScheduleException.class)
                .hasFieldOrPropertyWithValue("code", ScheduleErrorCodes.SCHEDULE_INPUT_INVALID);
    }

    private static String codeOf(Runnable runnable) {
        return catchThrowableOfType(runnable::run, ScheduleException.class).code();
    }
}