package com.company.campaign.builder.campaignagent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T40B1: the immutable requirement brief, its coherent delta and its deterministic completeness are the only
 * authority on what a Campaign still needs. Concrete copy below is test data only.
 */
class CampaignRequirementBriefTest {

    private static final String AUDIENCE = "the audience of this test Campaign";
    private static final String OFFER = "the Offer of this test Campaign";
    private static final String BRANCH = "the branch of this test Campaign";
    private static final String MESSAGE = "the message copy of this test Campaign";
    private static final CampaignBranchRule BRANCH_RULE = new CampaignBranchRule(
            "the explicit condition", "the true action", "the false action");

    private static CampaignRequirementBrief complete(CampaignType type) {
        if (type == CampaignType.EVENT) {
            return new CampaignRequirementBrief(
                    type, AUDIENCE, OfferDecision.NONE, null,
                    List.of(CampaignMessageInputs.Channel.EMAIL), MESSAGE,
                    BranchDecision.NONE, null, null, null, CampaignExecutionSchedule.empty())
                    .withTriggerEventCode("PURCHASE_COMPLETED");
        }
        CampaignCadence cadence = type == CampaignType.LIFECYCLE
                ? CampaignCadence.DAILY : CampaignCadence.ONE_TIME;
        return new CampaignRequirementBrief(
                type, AUDIENCE, OfferDecision.NONE, null,
                List.of(CampaignMessageInputs.Channel.EMAIL), MESSAGE,
                BranchDecision.NONE, null, cadence, null, scheduleFor(cadence));
    }

    /** The complete schedule evidence a cadence needs, per the accepted {@code schedule.basic/1.0} contract. */
    private static CampaignExecutionSchedule scheduleFor(CampaignCadence cadence) {
        return cadence == CampaignCadence.ONE_TIME
                ? new CampaignExecutionSchedule("Asia/Shanghai", "2026-01-01T09:30:00+08:00", null, null, null)
                : new CampaignExecutionSchedule("Asia/Shanghai", null, "09:30", "2026-01-01", "2026-03-31");
    }

    // ------------------------------------------------------------------ closed enums

    @Test
    void everyClosedEnumAcceptsOnlyItsExactServerOwnedWireName() {
        assertThat(OfferDecision.fromWireName("UNKNOWN")).isEqualTo(OfferDecision.UNKNOWN);
        assertThat(OfferDecision.fromWireName("NONE")).isEqualTo(OfferDecision.NONE);
        assertThat(OfferDecision.fromWireName("DEFINED")).isEqualTo(OfferDecision.DEFINED);
        assertThat(BranchDecision.fromWireName("UNKNOWN")).isEqualTo(BranchDecision.UNKNOWN);
        assertThat(BranchDecision.fromWireName("NONE")).isEqualTo(BranchDecision.NONE);
        assertThat(BranchDecision.fromWireName("REQUIRED")).isEqualTo(BranchDecision.REQUIRED);
        assertThat(CampaignCadence.fromWireName("ONE_TIME")).isEqualTo(CampaignCadence.ONE_TIME);
        assertThat(CampaignCadence.fromWireName("DAILY")).isEqualTo(CampaignCadence.DAILY);

        assertThat(OfferDecision.fromWireName("none")).isNull();
        assertThat(OfferDecision.fromWireName("YES")).isNull();
        assertThat(BranchDecision.fromWireName("REQUIRE")).isNull();
        assertThat(CampaignCadence.fromWireName("daily")).isNull();
        assertThat(OfferDecision.fromWireName(null)).isNull();

        assertThat(OfferDecision.UNKNOWN.decided()).isFalse();
        assertThat(OfferDecision.NONE.decided()).isTrue();
        assertThat(BranchDecision.UNKNOWN.decided()).isFalse();
        assertThat(BranchDecision.REQUIRED.decided()).isTrue();
    }

    @Test
    void theTypeQuestionCodeIsTheOneTheRoutingContractAlreadyOwns() {
        assertThat(CampaignRequirementCode.CAMPAIGN_TYPE_REQUIRED.code())
                .isEqualTo(CampaignRoutingVocabulary.TYPE_REQUIRED_QUESTION_CODE);
    }

    // ------------------------------------------------------------------ coherence

    @Test
    void anOfferOrBranchDescriptionWithoutItsDecisionCannotBeConstructed() {
        assertThatThrownBy(() -> new CampaignRequirementBrief(
                CampaignType.AD_HOC, AUDIENCE, OfferDecision.NONE, OFFER,
                List.of(), MESSAGE, BranchDecision.NONE, null, CampaignCadence.ONE_TIME))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CampaignRequirementBrief(
                CampaignType.AD_HOC, AUDIENCE, OfferDecision.UNKNOWN, OFFER,
                List.of(), MESSAGE, BranchDecision.NONE, null, CampaignCadence.ONE_TIME))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CampaignRequirementBrief(
                CampaignType.AD_HOC, AUDIENCE, OfferDecision.NONE, null,
                List.of(), MESSAGE, BranchDecision.NONE, BRANCH, CampaignCadence.ONE_TIME))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theChannelListIsCanonicalUniqueAndOrdered() {
        CampaignRequirementBrief brief = new CampaignRequirementBrief(
                null, null, null, null,
                List.of(CampaignMessageInputs.Channel.IN_APP, CampaignMessageInputs.Channel.SMS),
                null, null, null, null);

        assertThat(brief.channels()).containsExactly(
                CampaignMessageInputs.Channel.SMS, CampaignMessageInputs.Channel.IN_APP);
        assertThatThrownBy(() -> new CampaignRequirementBrief(
                null, null, null, null,
                List.of(CampaignMessageInputs.Channel.SMS, CampaignMessageInputs.Channel.SMS),
                null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(CampaignRequirementBrief.empty().channels()).isEmpty();
        assertThat(CampaignRequirementBrief.empty().offerDecision()).isEqualTo(OfferDecision.UNKNOWN);
        assertThat(CampaignRequirementBrief.empty().branchDecision()).isEqualTo(BranchDecision.UNKNOWN);
    }

    @Test
    void anOverBoundTextIsRefusedNeverTruncated() {
        assertThatThrownBy(() -> new CampaignRequirementBrief(
                null, "a".repeat(CampaignRequirementDelta.MAX_AUDIENCE_LENGTH + 1), null, null,
                List.of(), null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CampaignRequirementBrief(
                null, null, null, null, List.of(),
                "a".repeat(CampaignRequirementDelta.MAX_MESSAGE_LENGTH + 1), null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ conditional completeness

    @Test
    void missingRequirementsAreOrderedAndConditional() {
        // The non-event type is derived, never asked, so an empty brief needs no type.
        assertThat(CampaignRequirementBrief.empty().missingRequirements()).containsExactly(
                CampaignRequirementCode.AUDIENCE_REQUIRED,
                CampaignRequirementCode.OFFER_DECISION_REQUIRED,
                CampaignRequirementCode.CHANNEL_REQUIRED,
                CampaignRequirementCode.MESSAGE_REQUIRED,
                CampaignRequirementCode.BRANCH_DECISION_REQUIRED,
                CampaignRequirementCode.CADENCE_REQUIRED);

        CampaignRequirementBrief definedOffer = new CampaignRequirementBrief(
                CampaignType.AD_HOC, AUDIENCE, OfferDecision.DEFINED, null,
                List.of(CampaignMessageInputs.Channel.SMS), MESSAGE,
                BranchDecision.REQUIRED, null, CampaignCadence.DAILY);
        assertThat(definedOffer.missingRequirements()).containsExactly(
                CampaignRequirementCode.OFFER_DETAIL_REQUIRED,
                CampaignRequirementCode.BRANCH_RULE_REQUIRED,
                CampaignRequirementCode.EXECUTION_TIMEZONE_REQUIRED,
                CampaignRequirementCode.EXECUTION_TIME_REQUIRED,
                CampaignRequirementCode.SCHEDULE_START_DATE_REQUIRED,
                CampaignRequirementCode.SCHEDULE_END_DATE_REQUIRED);

        CampaignRequirementBrief noOfferNoBranch = new CampaignRequirementBrief(
                CampaignType.AD_HOC, AUDIENCE, OfferDecision.NONE, null,
                List.of(CampaignMessageInputs.Channel.SMS), MESSAGE,
                BranchDecision.NONE, null, CampaignCadence.DAILY, null, scheduleFor(CampaignCadence.DAILY));
        assertThat(noOfferNoBranch.missingRequirements()).isEmpty();
    }

    @Test
    void missingExecutionTimeOrTimezoneOrWindowKeepsTheScheduleIncomplete() {
        CampaignRequirementBrief oneTime = new CampaignRequirementBrief(
                null, AUDIENCE, OfferDecision.NONE, null, List.of(CampaignMessageInputs.Channel.SMS), MESSAGE,
                BranchDecision.NONE, null, CampaignCadence.ONE_TIME);
        assertThat(oneTime.missingRequirements()).contains(
                CampaignRequirementCode.EXECUTION_TIMEZONE_REQUIRED,
                CampaignRequirementCode.EXECUTION_TIME_REQUIRED);
        assertThat(oneTime.completeForAssembly()).isFalse();

        // A timezone alone is not an executable instant: the execution time stays outstanding.
        CampaignRequirementBrief noInstant = oneTime.merge(new CampaignRequirementDelta(
                null, null, null, List.of(), null, null, null, null, null, null,
                new CampaignExecutionSchedule("Asia/Shanghai", null, null, null, null)));
        assertThat(noInstant.missingRequirements())
                .containsExactly(CampaignRequirementCode.EXECUTION_TIME_REQUIRED);
        assertThat(noInstant.completeForAssembly()).isFalse();

        // A repeated schedule also needs its local time and its inclusive window.
        CampaignRequirementBrief daily = new CampaignRequirementBrief(
                null, AUDIENCE, OfferDecision.NONE, null, List.of(CampaignMessageInputs.Channel.SMS), MESSAGE,
                BranchDecision.NONE, null, CampaignCadence.DAILY);
        assertThat(daily.missingRequirements()).contains(
                CampaignRequirementCode.EXECUTION_TIMEZONE_REQUIRED,
                CampaignRequirementCode.EXECUTION_TIME_REQUIRED,
                CampaignRequirementCode.SCHEDULE_START_DATE_REQUIRED,
                CampaignRequirementCode.SCHEDULE_END_DATE_REQUIRED);
        assertThat(daily.completeForAssembly()).isFalse();
    }

    @Test
    void theNonEventTypeIsDerivedFromTheScheduleModeAndStaysExplicitForEvent() {
        CampaignRequirementBrief oneTime = CampaignRequirementBrief.empty().merge(
                new CampaignRequirementDelta(null, null, null, List.of(), null, null, null,
                        CampaignCadence.ONE_TIME));
        assertThat(oneTime.type()).isEqualTo(CampaignType.AD_HOC);

        CampaignRequirementBrief daily = oneTime.merge(
                new CampaignRequirementDelta(null, null, null, List.of(), null, null, null,
                        CampaignCadence.DAILY));
        assertThat(daily.type()).isEqualTo(CampaignType.LIFECYCLE);

        CampaignRequirementBrief event = CampaignRequirementBrief.empty().withType(CampaignType.EVENT);
        assertThat(event.type()).isEqualTo(CampaignType.EVENT);
        assertThat(event.unsupported()).isFalse();
        assertThat(event.missingRequirements()).contains(CampaignRequirementCode.EVENT_REQUIRED);
    }

    @Test
    void anExplicitChannelOperationReplacesAndRemovesRatherThanUnions() {
        CampaignRequirementBrief brief = new CampaignRequirementBrief(
                null, AUDIENCE, OfferDecision.NONE, null,
                List.of(CampaignMessageInputs.Channel.EMAIL, CampaignMessageInputs.Channel.SMS), MESSAGE,
                BranchDecision.NONE, null, CampaignCadence.ONE_TIME, null, scheduleFor(CampaignCadence.ONE_TIME));

        CampaignRequirementBrief replaced = brief.merge(new CampaignRequirementDelta(
                null, null, null, List.of(CampaignMessageInputs.Channel.WECHAT), null, null, null, null, null,
                ChannelOperation.REPLACE, null));
        assertThat(replaced.channels()).containsExactly(CampaignMessageInputs.Channel.WECHAT);

        CampaignRequirementBrief removed = brief.merge(new CampaignRequirementDelta(
                null, null, null, List.of(CampaignMessageInputs.Channel.EMAIL), null, null, null, null, null,
                ChannelOperation.REMOVE, null));
        assertThat(removed.channels()).containsExactly(CampaignMessageInputs.Channel.SMS);

        // A removal with no remaining channel is accepted and leaves the component outstanding again.
        CampaignRequirementBrief emptied = removed.merge(new CampaignRequirementDelta(
                null, null, null, List.of(CampaignMessageInputs.Channel.SMS), null, null, null, null, null,
                ChannelOperation.REMOVE, null));
        assertThat(emptied.channels()).isEmpty();
        assertThat(emptied.missingRequirements()).contains(CampaignRequirementCode.CHANNEL_REQUIRED);
    }

    @Test
    void anEventBriefRequiresARegisteredEventAndThenBecomesComplete() {
        CampaignRequirementBrief event = complete(CampaignType.EVENT);

        assertThat(event.unsupported()).isFalse();
        assertThat(event.triggerEventCode()).isEqualTo("PURCHASE_COMPLETED");
        assertThat(event.missingRequirements()).isEmpty();
        assertThat(event.completeForAssembly()).isTrue();
    }

    @Test
    void anEventBriefDoesNotRequireAnEntryAudienceButAnAdditionalRestrictionDoes() {
        // A registered trigger event already identifies the members the Campaign acts on, so a trigger-only
        // EVENT has no audience requirement of its own.
        CampaignRequirementBrief triggerOnly = CampaignRequirementBrief.empty()
                .withType(CampaignType.EVENT)
                .withTriggerEventCode("PURCHASE_COMPLETED");
        assertThat(triggerOnly.audienceApplicable()).isFalse();
        assertThat(triggerOnly.audience()).isNull();
        assertThat(triggerOnly.missingRequirements())
                .doesNotContain(CampaignRequirementCode.AUDIENCE_REQUIRED);

        // A genuine additional restriction makes the audience requirement applicable again.
        CampaignRequirementBrief restricted = triggerOnly.merge(new CampaignRequirementDelta(
                "a further test-only restriction", null, null, List.of(), null, null, null, null));
        assertThat(restricted.audienceApplicable()).isTrue();
        assertThat(restricted.audience()).isEqualTo("a further test-only restriction");

        // Every other Campaign type keeps requiring its entry audience, including an unknown type.
        assertThat(CampaignRequirementBrief.empty().audienceApplicable()).isTrue();
        assertThat(CampaignRequirementBrief.empty().missingRequirements())
                .contains(CampaignRequirementCode.AUDIENCE_REQUIRED);
        CampaignRequirementBrief adHoc = CampaignRequirementBrief.empty()
                .withType(CampaignType.AD_HOC)
                .withTriggerEventCode("PURCHASE_COMPLETED");
        assertThat(adHoc.audienceApplicable()).isTrue();
        assertThat(adHoc.missingRequirements()).contains(CampaignRequirementCode.AUDIENCE_REQUIRED);
    }

    @Test
    void completeForAssemblyIsTrueOnlyForAFullyCollectedSupportedType() {
        assertThat(complete(CampaignType.AD_HOC).completeForAssembly()).isTrue();
        assertThat(complete(CampaignType.LIFECYCLE).completeForAssembly()).isTrue();
        assertThat(complete(CampaignType.EVENT).completeForAssembly()).isTrue();
        // With a complete one-time schedule, a cleared type re-derives AD_HOC rather than blocking assembly.
        assertThat(complete(CampaignType.AD_HOC).withType(null).completeForAssembly()).isTrue();
        assertThat(complete(CampaignType.AD_HOC).merge(CampaignRequirementDelta.empty()).completeForAssembly())
                .isTrue();
        // No channel is a missing fact even when the schedule evidence is complete.
        assertThat(new CampaignRequirementBrief(
                CampaignType.AD_HOC, AUDIENCE, OfferDecision.NONE, null, List.of(), MESSAGE,
                BranchDecision.NONE, null, CampaignCadence.ONE_TIME, null, scheduleFor(CampaignCadence.ONE_TIME))
                .completeForAssembly()).isFalse();
    }

    // ------------------------------------------------------------------ deterministic merge

    @Test
    void mergeAccumulatesFactsAcrossTurnsInAnyOrderAndNeverErases() {
        CampaignRequirementBrief brief = CampaignRequirementBrief.empty()
                .withType(CampaignType.LIFECYCLE)
                .merge(new CampaignRequirementDelta(AUDIENCE, null, null, List.of(), null, null, null, null))
                .merge(new CampaignRequirementDelta(null, null, null, List.of(), null, null, null,
                        CampaignCadence.DAILY, null, null, scheduleFor(CampaignCadence.DAILY)))
                .merge(new CampaignRequirementDelta(null, OfferDecision.NONE, null,
                        List.of(CampaignMessageInputs.Channel.SMS), null, null, null, null))
                .merge(new CampaignRequirementDelta(null, null, null, List.of(), MESSAGE, null, null, null))
                .merge(new CampaignRequirementDelta(null, null, null, List.of(), null,
                        BranchDecision.NONE, null, null));

        assertThat(brief.audience()).isEqualTo(AUDIENCE);
        assertThat(brief.offerDecision()).isEqualTo(OfferDecision.NONE);
        assertThat(brief.channels()).containsExactly(CampaignMessageInputs.Channel.SMS);
        assertThat(brief.message()).isEqualTo(MESSAGE);
        assertThat(brief.branchDecision()).isEqualTo(BranchDecision.NONE);
        assertThat(brief.cadence()).isEqualTo(CampaignCadence.DAILY);
        assertThat(brief.completeForAssembly()).isTrue();

        // An empty turn changes nothing at all.
        CampaignRequirementBrief unchanged = brief.merge(CampaignRequirementDelta.empty());
        assertThat(unchanged).isEqualTo(brief);
        assertThat(CampaignRequirementDelta.empty().isEmpty()).isTrue();
    }

    @Test
    void aCoherentExplicitCorrectionReplacesThePriorValue() {
        CampaignRequirementBrief brief = complete(CampaignType.AD_HOC)
                .merge(new CampaignRequirementDelta("a corrected audience", null, null, List.of(),
                        null, null, null, null));

        assertThat(brief.audience()).isEqualTo("a corrected audience");
        assertThat(brief.message()).isEqualTo(MESSAGE);
    }

    @Test
    void aCorrectionAwayFromDefinedOrRequiredClearsTheNowIncoherentDetail() {
        CampaignRequirementBrief defined = new CampaignRequirementBrief(
                CampaignType.AD_HOC, AUDIENCE, OfferDecision.DEFINED, OFFER,
                List.of(CampaignMessageInputs.Channel.SMS), MESSAGE,
                BranchDecision.REQUIRED, BRANCH, CampaignCadence.ONE_TIME, BRANCH_RULE,
                scheduleFor(CampaignCadence.ONE_TIME));
        assertThat(defined.completeForAssembly()).isTrue();

        CampaignRequirementBrief noOffer = defined.merge(new CampaignRequirementDelta(
                null, OfferDecision.NONE, null, List.of(), null, null, null, null));
        assertThat(noOffer.offerDecision()).isEqualTo(OfferDecision.NONE);
        assertThat(noOffer.offerDetail()).isNull();
        assertThat(noOffer.branchDetail()).isEqualTo(BRANCH);
        assertThat(noOffer.branchRule()).isEqualTo(BRANCH_RULE);

        CampaignRequirementBrief noBranch = defined.merge(new CampaignRequirementDelta(
                null, null, null, List.of(), null, BranchDecision.NONE, null, null));
        assertThat(noBranch.branchDetail()).isNull();
        assertThat(noBranch.branchRule()).isNull();
        assertThat(noBranch.offerDetail()).isEqualTo(OFFER);
    }

    @Test
    void aBranchedCampaignRequiresTheConditionAndBothOutcomes() {
        CampaignRequirementBrief proseOnly = new CampaignRequirementBrief(
                CampaignType.AD_HOC, AUDIENCE, OfferDecision.NONE, null,
                List.of(CampaignMessageInputs.Channel.SMS), MESSAGE,
                BranchDecision.REQUIRED, BRANCH, CampaignCadence.ONE_TIME, null,
                scheduleFor(CampaignCadence.ONE_TIME));

        assertThat(proseOnly.completeForAssembly()).isFalse();
        assertThat(proseOnly.missingRequirements()).containsExactly(CampaignRequirementCode.BRANCH_RULE_REQUIRED);
        assertThatThrownBy(() -> new CampaignBranchRule("condition", "true action", null))
                .isInstanceOf(IllegalArgumentException.class);

        CampaignRequirementBrief confirmed = proseOnly.merge(new CampaignRequirementDelta(
                null, null, null, List.of(), null, BranchDecision.REQUIRED, null,
                null, BRANCH_RULE));
        assertThat(confirmed.completeForAssembly()).isTrue();
        assertThat(confirmed.branchRule()).isEqualTo(BRANCH_RULE);
    }

    @Test
    void theChannelUnionStaysCanonicalAcrossTurns() {
        CampaignRequirementBrief brief = CampaignRequirementBrief.empty()
                .merge(new CampaignRequirementDelta(null, null, null,
                        List.of(CampaignMessageInputs.Channel.EMAIL), null, null, null, null))
                .merge(new CampaignRequirementDelta(null, null, null,
                        List.of(CampaignMessageInputs.Channel.SMS), null, null, null, null));

        assertThat(brief.channels()).containsExactly(
                CampaignMessageInputs.Channel.SMS, CampaignMessageInputs.Channel.EMAIL);
    }

    @Test
    void aDeltaCannotCarryAnIncoherentPair() {
        assertThatThrownBy(() -> new CampaignRequirementDelta(
                null, null, OFFER, List.of(), null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CampaignRequirementDelta(
                null, OfferDecision.NONE, OFFER, List.of(), null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CampaignRequirementDelta(
                null, null, null, List.of(), null, null, BRANCH, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CampaignRequirementDelta(
                null, null, null,
                List.of(CampaignMessageInputs.Channel.EMAIL, CampaignMessageInputs.Channel.EMAIL),
                null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);

        // UNKNOWN is normalized to "not supplied" and can never overwrite an accepted decision.
        CampaignRequirementDelta unknown =
                new CampaignRequirementDelta(null, OfferDecision.UNKNOWN, null, List.of(), null,
                        BranchDecision.UNKNOWN, null, null);
        assertThat(unknown.offerDecision()).isNull();
        assertThat(unknown.branchDecision()).isNull();
        assertThat(unknown.isEmpty()).isTrue();
        assertThat(unknown.answers(CampaignRequirementCode.OFFER_DECISION_REQUIRED)).isFalse();
        assertThat(new CampaignRequirementDelta(null, OfferDecision.NONE, null, List.of(), null,
                null, null, null).answers(CampaignRequirementCode.OFFER_DECISION_REQUIRED)).isTrue();
    }

    @Test
    void withTypeFixesTheTypeAndKeepsEveryAccumulatedFact() {
        CampaignRequirementBrief untyped = CampaignRequirementBrief.empty()
                .merge(new CampaignRequirementDelta(AUDIENCE, null, null, List.of(), null, null, null, null));
        CampaignRequirementBrief typed = untyped.withType(CampaignType.LIFECYCLE);

        assertThat(typed.type()).isEqualTo(CampaignType.LIFECYCLE);
        assertThat(typed.audience()).isEqualTo(AUDIENCE);
        assertThat(typed.cadence()).isNull();
    }

    @Test
    void withAudienceReplacesOnlyAudienceAndPreservesAllOtherVerifiedFacts() {
        CampaignRequirementBrief brief = complete(CampaignType.AD_HOC);
        assertThat(brief.audience()).isEqualTo(AUDIENCE);

        String refined = "members registered within 24 hours who consented to WeChat delivery";
        CampaignRequirementBrief updated = brief.withAudience(refined);

        assertThat(updated.audience()).isEqualTo(refined);
        // Every other verified fact is unchanged.
        assertThat(updated.type()).isEqualTo(brief.type());
        assertThat(updated.offerDecision()).isEqualTo(brief.offerDecision());
        assertThat(updated.offerDetail()).isEqualTo(brief.offerDetail());
        assertThat(updated.communicationDecision()).isEqualTo(brief.communicationDecision());
        assertThat(updated.channels()).isEqualTo(brief.channels());
        assertThat(updated.message()).isEqualTo(brief.message());
        assertThat(updated.branchDecision()).isEqualTo(brief.branchDecision());
        assertThat(updated.branchDetail()).isEqualTo(brief.branchDetail());
        assertThat(updated.cadence()).isEqualTo(brief.cadence());
        assertThat(updated.branchRule()).isEqualTo(brief.branchRule());
        assertThat(updated.schedule()).isEqualTo(brief.schedule());
        assertThat(updated.triggerEventCode()).isEqualTo(brief.triggerEventCode());
        assertThat(updated.confirmedObjective()).isEqualTo(brief.confirmedObjective());
        assertThat(updated.completeForAssembly()).isTrue();
    }

    @Test
    void withAudienceRefusesOverBoundText() {
        CampaignRequirementBrief brief = complete(CampaignType.AD_HOC);
        String overBound = "x".repeat(CampaignRequirementDelta.MAX_AUDIENCE_LENGTH + 1);
        assertThatThrownBy(() -> brief.withAudience(overBound))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withNullAudienceClearsTheField() {
        CampaignRequirementBrief brief = complete(CampaignType.AD_HOC);
        assertThat(brief.audience()).isNotNull();
        CampaignRequirementBrief cleared = brief.withAudience(null);
        assertThat(cleared.audience()).isNull();
    }

    @Test
    void withAudienceFollowedByMergePreservesTheRefinementWhenDeltaHasNoAudience() {
        CampaignRequirementBrief brief = complete(CampaignType.AD_HOC);
        String refined = "members registered within 24 hours";
        CampaignRequirementBrief refinedBrief = brief.withAudience(refined);

        CampaignRequirementDelta delta = new CampaignRequirementDelta(
                null, null, null, List.of(), "new message copy", null, null, null);
        CampaignRequirementBrief merged = refinedBrief.merge(delta);

        // Audience stays refined because the delta supplied no audience.
        assertThat(merged.audience()).isEqualTo(refined);
        // Message is updated.
        assertThat(merged.message()).isEqualTo("new message copy");
    }

    @Test
    void withAudienceDoesNotAffectConfirmedObjective() {
        CampaignBusinessObjective objective = new CampaignBusinessObjective(
                "test evidence", 1L, "turn-1");
        CampaignRequirementBrief brief = complete(CampaignType.AD_HOC).withConfirmedObjective(objective);

        CampaignRequirementBrief refined = brief.withAudience("refined audience text");

        assertThat(refined.audience()).isEqualTo("refined audience text");
        assertThat(refined.confirmedObjective()).isEqualTo(objective);
    }
}
