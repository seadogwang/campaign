package com.company.campaign.builder.campaignagent;

import com.company.campaign.builder.assembly.CampaignComponentState;
import com.company.campaign.builder.assembly.CampaignSkillIds;
import com.company.campaign.builder.assembly.CampaignSkillState;
import com.company.campaign.model.CampaignRequirementExtraction;
import com.company.campaign.model.StructuredCampaignRequirementExtractor;
import com.company.campaign.model.StructuredModelPort;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The trusted-turn-time grounding and deterministic schedule validation of one Campaign brief.
 *
 * <p>It proves a model-extracted one-time execution instant in the past is rejected, a daily window whose end
 * precedes its start or whose complete window is already in the past is rejected, the per-turn prompt document
 * carries the trusted turn time and timezone, a later natural-language correction that supplies a valid value
 * clears the block, and the finalization gate (every component TODO COMPLETE) is therefore closed for an invalid
 * accepted schedule and open for a valid one.
 */
class CampaignScheduleGroundingTest {

    /** The pinned trusted turn time every accepted schedule is grounded and validated against in these tests. */
    private static final Clock TRUSTED_CLOCK =
            Clock.fixed(Instant.parse("2026-10-10T00:00:00Z"), ZoneId.of("UTC"));

    private static final Instant TRUSTED_NOW = TRUSTED_CLOCK.instant();
    private static final ZoneId TRUSTED_ZONE = TRUSTED_CLOCK.getZone();

    /** A complete one-time schedule evidence object carrying the supplied offset instant. */
    private static CampaignExecutionSchedule onceSchedule(String runAt) {
        return new CampaignExecutionSchedule("Asia/Shanghai", runAt, null, null, null);
    }

    /** A complete daily schedule evidence object carrying the supplied inclusive window. */
    private static CampaignExecutionSchedule dailySchedule(String startDate, String endDate) {
        return new CampaignExecutionSchedule("Asia/Shanghai", null, "09:30", startDate, endDate);
    }

    /** A complete Campaign brief for a single execution under the supplied schedule evidence. */
    private static CampaignRequirementBrief onceBrief(CampaignExecutionSchedule schedule) {
        return new CampaignRequirementBrief(
                CampaignType.AD_HOC, "an audience", OfferDecision.NONE, null,
                List.of(CampaignMessageInputs.Channel.SMS), "a message",
                BranchDecision.NONE, null, CampaignCadence.ONE_TIME, null, schedule);
    }

    /** A complete Campaign brief for a repeated execution under the supplied schedule evidence. */
    private static CampaignRequirementBrief dailyBrief(CampaignExecutionSchedule schedule) {
        return new CampaignRequirementBrief(
                CampaignType.LIFECYCLE, "an audience", OfferDecision.NONE, null,
                List.of(CampaignMessageInputs.Channel.SMS), "a message",
                BranchDecision.NONE, null, CampaignCadence.DAILY, null, schedule);
    }

    /** Every accepted Skill is observed READY, so the only thing that can block a component is the brief itself. */
    private static List<CampaignSkillState> allReady() {
        List<CampaignSkillState> skills = new ArrayList<>();
        for (String skillId : CampaignSkillIds.ordered()) {
            skills.add(new CampaignSkillState(skillId, CampaignComponentState.READY, null, List.of()));
        }
        return List.copyOf(skills);
    }

    private static CampaignComponentTodo executionScheduleTodo(CampaignRequirementBrief brief) {
        return CampaignComponentTodo.from(brief, allReady(), TRUSTED_NOW, TRUSTED_ZONE).stream()
                .filter(todo -> todo.component() == CampaignComponentCode.EXECUTION_SCHEDULE)
                .findFirst().orElseThrow();
    }

    /** The exact readyToFinalize predicate: every component TODO is COMPLETE. */
    private static boolean finalizable(CampaignRequirementBrief brief) {
        return CampaignComponentTodo.from(brief, allReady(), TRUSTED_NOW, TRUSTED_ZONE).stream()
                .allMatch(todo -> todo.status() == CampaignTodoStatus.COMPLETE);
    }

    // ------------------------------------------------------------------ past one-time rejection

    @Test
    void aOneTimeRunAtNotAfterTheTrustedTurnTimeIsRejectedAsBlocked() {
        CampaignExecutionSchedule past = onceSchedule("2026-01-01T09:30:00+08:00");

        assertThat(past.invalid(CampaignCadence.ONE_TIME, TRUSTED_NOW, TRUSTED_ZONE))
                .containsExactly(CampaignExecutionSchedule.CODE_RUN_AT_NOT_FUTURE);

        CampaignComponentTodo todo = executionScheduleTodo(onceBrief(past));
        assertThat(todo.status()).isEqualTo(CampaignTodoStatus.BLOCKED);
        assertThat(todo.validationCodes())
                .containsExactly(CampaignExecutionSchedule.CODE_RUN_AT_NOT_FUTURE);
    }

    // ------------------------------------------------------------------ corrected future acceptance

    @Test
    void aLaterCorrectionThatSuppliesAFutureRunAtClearsTheBlock() {
        CampaignRequirementBrief blocked = onceBrief(onceSchedule("2026-01-01T09:30:00+08:00"));
        assertThat(executionScheduleTodo(blocked).status()).isEqualTo(CampaignTodoStatus.BLOCKED);

        // A later natural-language correction overlays exactly the corrected field, so only the bad instant is
        // replaced and the rest of the accepted schedule is preserved.
        CampaignRequirementBrief corrected = blocked.merge(new CampaignRequirementDelta(
                null, null, null, List.of(), null, null, null, null,
                null, null, onceSchedule("2026-11-01T09:00:00+00:00")));

        assertThat(corrected.schedule().runAt()).isEqualTo("2026-11-01T09:00:00+00:00");
        assertThat(corrected.schedule().invalid(CampaignCadence.ONE_TIME, TRUSTED_NOW, TRUSTED_ZONE)).isEmpty();
        CampaignComponentTodo todo = executionScheduleTodo(corrected);
        assertThat(todo.status()).isEqualTo(CampaignTodoStatus.COMPLETE);
        assertThat(todo.validationCodes()).isEmpty();
    }

    // ------------------------------------------------------------------ invalid daily range and past window

    @Test
    void aDailyWindowWhoseEndPrecedesStartIsRejected() {
        CampaignExecutionSchedule reversed = dailySchedule("2026-11-30", "2026-11-01");

        assertThat(reversed.invalid(CampaignCadence.DAILY, TRUSTED_NOW, TRUSTED_ZONE))
                .containsExactly(CampaignExecutionSchedule.CODE_WINDOW_RANGE_INVALID);

        CampaignComponentTodo todo = executionScheduleTodo(dailyBrief(reversed));
        assertThat(todo.status()).isEqualTo(CampaignTodoStatus.BLOCKED);
        assertThat(todo.validationCodes())
                .containsExactly(CampaignExecutionSchedule.CODE_WINDOW_RANGE_INVALID);
    }

    @Test
    void aDailyWindowAlreadyInThePastIsRejected() {
        CampaignExecutionSchedule pastWindow = dailySchedule("2026-01-01", "2026-03-31");

        assertThat(pastWindow.invalid(CampaignCadence.DAILY, TRUSTED_NOW, TRUSTED_ZONE))
                .containsExactly(CampaignExecutionSchedule.CODE_WINDOW_IN_PAST);

        CampaignComponentTodo todo = executionScheduleTodo(dailyBrief(pastWindow));
        assertThat(todo.status()).isEqualTo(CampaignTodoStatus.BLOCKED);
        assertThat(todo.validationCodes())
                .containsExactly(CampaignExecutionSchedule.CODE_WINDOW_IN_PAST);
    }

    // ------------------------------------------------------------------ prompt time grounding

    @Test
    void thePerTurnDocumentCarriesTheTrustedTurnTimeAndTimezone() {
        StringBuilder document = new StringBuilder();
        String empty = "{\"audience\":null,\"offerDecision\":null,\"offerDetail\":null,"
                + "\"communicationDecision\":null,\"channels\":[],"
                + "\"channelOperation\":null,\"message\":null,\"branchDecision\":null,"
                + "\"branchDetail\":null,\"branchRule\":null,\"cadence\":null,"
                + "\"objectiveEvidence\":null,\"schedule\":null}";
        StructuredCampaignRequirementExtractor extractor =
                new StructuredCampaignRequirementExtractor(
                        request -> {
                            document.append(request.user());
                            return empty.getBytes(StandardCharsets.UTF_8);
                        },
                        TRUSTED_CLOCK);
        CampaignRequirementExtraction outcome = extractor.extract(
                "run it once tomorrow", CampaignRequirementBrief.empty());

        assertThat(outcome.accepted()).isTrue();
        assertThat(document.toString())
                .contains("Trusted absolute turn instant: " + TRUSTED_NOW.toString())
                .contains("Trusted business timezone for local time: " + TRUSTED_ZONE.getId());
    }

    // ------------------------------------------------------------------ finalization gating

    @Test
    void anInvalidScheduleClosesFinalizationAndAValidOneOpensIt() {
        CampaignRequirementBrief past = onceBrief(onceSchedule("2026-01-01T09:30:00+08:00"));
        assertThat(finalizable(past)).isFalse();

        CampaignRequirementBrief future = onceBrief(onceSchedule("2026-11-01T09:00:00+00:00"));
        assertThat(future.schedule().invalid(CampaignCadence.ONE_TIME, TRUSTED_NOW, TRUSTED_ZONE)).isEmpty();
        assertThat(finalizable(future)).isTrue();

        // A daily window whose end is on or after the trusted turn date is not past-blocked and finalizes.
        CampaignRequirementBrief daily = dailyBrief(dailySchedule("2026-11-01", "2026-11-30"));
        assertThat(finalizable(daily)).isTrue();
    }
}
