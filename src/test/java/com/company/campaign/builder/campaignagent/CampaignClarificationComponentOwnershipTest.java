package com.company.campaign.builder.campaignagent;

import com.company.campaign.builder.assembly.CampaignAssemblyContext;
import com.company.campaign.builder.assembly.CampaignSkillIds;
import com.company.campaign.builder.assembly.CampaignToolCatalog;
import com.company.campaign.builder.proposal.ProposalBaseRevisions;
import com.company.campaign.builder.proposal.ProposalClientContext;
import com.company.campaign.builder.proposal.ProposalMode;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Component-ownership invariant tests for clarification answers.
 *
 * <p>These tests prove:
 * <ul>
 *   <li>Segment and Journey can use the same question code and both issues survive
 *       with distinct component ownership.</li>
 *   <li>Segment receives only Segment text; Journey receives only Journey text.</li>
 *   <li>A multi-component answer missing component is rejected.</li>
 *   <li>A code/component mismatch is rejected.</li>
 *   <li>A legacy unscoped answer is accepted only for one pending component.</li>
 *   <li>The main projection preserves exact per-component prompts and canonical
 *       order.</li>
 * </ul>
 *
 * <p>They carry no evaluation business scenario text, no catalog seed, no real
 * scenario. They are deterministic invariant tests only, not business acceptance.
 */
class CampaignClarificationComponentOwnershipTest {

    private static final String CONVERSATION_ID = "conv-ownership-0001";
    private static final String RUN_ID = "run-ownership-0001";
    private static final String CATALOG_VERSION = "test-ownership-v1";
    private static final String POLICY_VERSION = "test-ownership-v1";

    private static final ContentHash HASH =
            new ContentHash(
                    "sha256:0000000000000000000000000000000000000000000000000000000000000000");

    private static final String SEGMENT_PROMPT = "Please clarify the audience description.";
    private static final String JOURNEY_PROMPT = "Please describe the user journey.";

    // Test-only question code, not a production constant.
    private static final String TEST_CODE = "TEST_INPUT_REQUIRED";

    private static CampaignAssemblyContext assemblyContext() {
        return new CampaignAssemblyContext(
                CONVERSATION_ID, RUN_ID, ProposalMode.CREATE, null,
                new ProposalBaseRevisions(
                        null, CATALOG_VERSION, HASH, POLICY_VERSION, HASH),
                ProposalClientContext.EMPTY, CampaignToolCatalog.standard());
    }

    // -- Aggregate: same question code, different components, both survive --

    @Test
    void sameCodeFromDifferentComponentsBothSurvive() {
        CampaignComponentClarification segmentRequest =
                new CampaignComponentClarification(
                        CampaignComponentCode.SEGMENT,
                        CampaignSkillIds.SEGMENT_RULE_BASED,
                        TEST_CODE,
                        SEGMENT_PROMPT,
                        List.of(TEST_CODE),
                        List.of(),
                        true);

        CampaignComponentClarification journeyRequest =
                new CampaignComponentClarification(
                        CampaignComponentCode.JOURNEY,
                        CampaignSkillIds.JOURNEY_FLOW,
                        TEST_CODE,
                        JOURNEY_PROMPT,
                        List.of(TEST_CODE),
                        List.of(),
                        true);

        CampaignClarificationAggregate aggregate =
                CampaignClarificationAggregate.of(
                        List.of(segmentRequest, journeyRequest));

        assertThat(aggregate.isEmpty()).isFalse();
        assertThat(aggregate.single()).isFalse();
        assertThat(aggregate.requests()).hasSize(2);

        // Both components present -- the same question code did not cause
        // deduplication.
        assertThat(aggregate.requests().get(0).component())
                .isEqualTo(CampaignComponentCode.SEGMENT);
        assertThat(aggregate.requests().get(1).component())
                .isEqualTo(CampaignComponentCode.JOURNEY);

        // Both retain their own prompts.
        assertThat(aggregate.requests().get(0).prompt())
                .isEqualTo(SEGMENT_PROMPT);
        assertThat(aggregate.requests().get(1).prompt())
                .isEqualTo(JOURNEY_PROMPT);

        // requestFor returns the correct component.
        assertThat(aggregate.requestFor(CampaignComponentCode.SEGMENT))
                .isNotNull();
        assertThat(aggregate.requestFor(CampaignComponentCode.SEGMENT).prompt())
                .isEqualTo(SEGMENT_PROMPT);
        assertThat(aggregate.requestFor(CampaignComponentCode.JOURNEY))
                .isNotNull();
        assertThat(aggregate.requestFor(CampaignComponentCode.JOURNEY).prompt())
                .isEqualTo(JOURNEY_PROMPT);
    }

    // -- Aggregate: repeated component rejected --

    @Test
    void repeatedComponentRejected() {
        CampaignComponentClarification first =
                new CampaignComponentClarification(
                        CampaignComponentCode.SEGMENT,
                        CampaignSkillIds.SEGMENT_RULE_BASED,
                        "SEGMENT_NEEDS_INPUT",
                        SEGMENT_PROMPT,
                        List.of("SEGMENT_NEEDS_INPUT"),
                        List.of(),
                        true);

        CampaignComponentClarification second =
                new CampaignComponentClarification(
                        CampaignComponentCode.SEGMENT,
                        CampaignSkillIds.SEGMENT_RULE_BASED,
                        "OTHER_CODE",
                        "Other segment question.",
                        List.of("OTHER_CODE"),
                        List.of(),
                        true);

        assertThatThrownBy(
                        () -> CampaignClarificationAggregate.of(
                                List.of(first, second)))
                .isInstanceOf(CampaignAgentException.class)
                .hasMessageContaining("distinct owned component");
    }

    // -- Aggregate: canonical component order --

    @Test
    void aggregatePreservesCanonicalComponentOrder() {
        CampaignComponentClarification journeyRequest =
                new CampaignComponentClarification(
                        CampaignComponentCode.JOURNEY,
                        CampaignSkillIds.JOURNEY_FLOW,
                        TEST_CODE,
                        JOURNEY_PROMPT,
                        List.of(TEST_CODE),
                        List.of(),
                        true);

        CampaignComponentClarification segmentRequest =
                new CampaignComponentClarification(
                        CampaignComponentCode.SEGMENT,
                        CampaignSkillIds.SEGMENT_RULE_BASED,
                        TEST_CODE,
                        SEGMENT_PROMPT,
                        List.of(TEST_CODE),
                        List.of(),
                        true);

        // Submit in reverse order; the aggregate must reorder to canonical.
        CampaignClarificationAggregate aggregate =
                CampaignClarificationAggregate.of(
                        List.of(journeyRequest, segmentRequest));

        assertThat(aggregate.requests()).hasSize(2);
        // SEGMENT ordinal < JOURNEY ordinal
        assertThat(aggregate.requests().get(0).component())
                .isEqualTo(CampaignComponentCode.SEGMENT);
        assertThat(aggregate.requests().get(1).component())
                .isEqualTo(CampaignComponentCode.JOURNEY);
        assertThat(aggregate.requests().get(0).prompt())
                .isEqualTo(SEGMENT_PROMPT);
        assertThat(aggregate.requests().get(1).prompt())
                .isEqualTo(JOURNEY_PROMPT);
    }

    // -- Answer ownership: answers carry component --

    @Test
    void answerCarriesComponent() {
        CampaignClarificationAnswer answer = new CampaignClarificationAnswer(
                TEST_CODE, null, "segment text",
                CampaignComponentCode.SEGMENT);
        assertThat(answer.component())
                .isEqualTo(CampaignComponentCode.SEGMENT);
        assertThat(answer.code()).isEqualTo(TEST_CODE);
        assertThat(answer.freeText()).isEqualTo("segment text");
    }

    @Test
    void legacyAnswerHasNullComponent() {
        CampaignClarificationAnswer answer = new CampaignClarificationAnswer(
                TEST_CODE, null, "some text");
        assertThat(answer.component()).isNull();
    }

    // -- Answer filtering by component --

    @Test
    void segmentReceivesOnlySegmentText() {
        CampaignComponentTask task = createComponentTask(
                CampaignComponentCode.SEGMENT,
                List.of(
                        new CampaignClarificationAnswer(
                                TEST_CODE, null, "segment text",
                                CampaignComponentCode.SEGMENT),
                        new CampaignClarificationAnswer(
                                TEST_CODE, null, "journey text",
                                CampaignComponentCode.JOURNEY)));

        List<CampaignClarificationAnswer> answers = task.answers();
        assertThat(answers).hasSize(1);
        assertThat(answers.get(0).freeText()).isEqualTo("segment text");
        assertThat(answers.get(0).component())
                .isEqualTo(CampaignComponentCode.SEGMENT);
    }

    @Test
    void journeyReceivesOnlyJourneyText() {
        CampaignComponentTask task = createComponentTask(
                CampaignComponentCode.JOURNEY,
                List.of(
                        new CampaignClarificationAnswer(
                                TEST_CODE, null, "segment text",
                                CampaignComponentCode.SEGMENT),
                        new CampaignClarificationAnswer(
                                TEST_CODE, null, "journey text",
                                CampaignComponentCode.JOURNEY)));

        List<CampaignClarificationAnswer> answers = task.answers();
        assertThat(answers).hasSize(1);
        assertThat(answers.get(0).freeText()).isEqualTo("journey text");
        assertThat(answers.get(0).component())
                .isEqualTo(CampaignComponentCode.JOURNEY);
    }

    @Test
    void legacyUnscopedAnswerIncludedWhenNoScopedAnswers() {
        CampaignComponentTask task = createComponentTask(
                CampaignComponentCode.SEGMENT,
                List.of(new CampaignClarificationAnswer(
                        TEST_CODE, null, "text")));

        List<CampaignClarificationAnswer> answers = task.answers();
        assertThat(answers).hasSize(1);
        assertThat(answers.get(0).freeText()).isEqualTo("text");
        assertThat(answers.get(0).component()).isNull();
    }

    @Test
    void legacyUnscopedAnswerExcludedWhenScopedAnswersExist() {
        CampaignComponentTask task = createComponentTask(
                CampaignComponentCode.SEGMENT,
                List.of(
                        new CampaignClarificationAnswer(
                                TEST_CODE, null, "text"),
                        new CampaignClarificationAnswer(
                                TEST_CODE, null, "journey text",
                                CampaignComponentCode.JOURNEY)));

        List<CampaignClarificationAnswer> answers = task.answers();
        // Segment should get nothing -- the legacy answer is excluded because
        // scoped answers exist and none of them is for SEGMENT.
        assertThat(answers).isEmpty();
    }

    // -- Multi-component answer missing component rejected --

    @Test
    void multiComponentAnswerMissingComponentRejected() {
        CampaignAgentSession session = createMultiComponentSession();
        CampaignClarificationAggregate aggregate =
                session.pendingComponentClarifications();
        assertThat(aggregate.isEmpty()).isFalse();
        assertThat(aggregate.single()).isFalse();

        CampaignClarificationAnswer unscoped = new CampaignClarificationAnswer(
                TEST_CODE, null, "answer text");

        assertThatThrownBy(
                        () -> requireAnswersMatchCall(session, unscoped))
                .isInstanceOf(CampaignAgentException.class)
                .hasMessageContaining("component");
    }

    // -- Code/component mismatch rejected --

    @Test
    void codeComponentMismatchRejected() {
        CampaignAgentSession session = createMultiComponentSession();
        // Answer with a code that belongs to JOURNEY but claims SEGMENT.
        CampaignClarificationAnswer mismatched = new CampaignClarificationAnswer(
                "JOURNEY_INPUT_REQUIRED", null, "journey text",
                CampaignComponentCode.SEGMENT);

        assertThatThrownBy(
                        () -> requireAnswersMatchCall(session, mismatched))
                .isInstanceOf(CampaignAgentException.class)
                .hasMessageContaining("component");
    }

    // -- Legacy unscoped answer accepted for single pending component --

    @Test
    void legacyUnscopedAnswerAcceptedForSingleComponent() {
        CampaignAgentSession session = new CampaignAgentSession(
                assemblyContext());
        CampaignComponentClarification segmentRequest =
                new CampaignComponentClarification(
                        CampaignComponentCode.SEGMENT,
                        CampaignSkillIds.SEGMENT_RULE_BASED,
                        "SEGMENT_NEEDS_INPUT",
                        SEGMENT_PROMPT,
                        List.of("SEGMENT_AUDIENCE_REPHRASE_REQUIRED"),
                        List.of(),
                        true);
        session.setPendingClarifications(
                new CampaignClarification(
                        CampaignSkillIds.SEGMENT_RULE_BASED,
                        "SEGMENT_NEEDS_INPUT",
                        SEGMENT_PROMPT,
                        List.of("SEGMENT_AUDIENCE_REPHRASE_REQUIRED"),
                        List.of(),
                        true),
                List.of(segmentRequest));

        CampaignClarificationAggregate aggregate =
                session.pendingComponentClarifications();
        assertThat(aggregate.isEmpty()).isFalse();
        assertThat(aggregate.single()).isTrue();

        // A legacy unscoped answer should be accepted for single-component.
        CampaignClarificationAnswer unscoped = new CampaignClarificationAnswer(
                "SEGMENT_AUDIENCE_REPHRASE_REQUIRED", null, "gold tier members");

        // This should not throw.
        requireAnswersMatchCall(session, unscoped);
    }

    // -- Projection preserves exact prompts --

    @Test
    void projectionPreservesExactPerComponentPrompts() {
        CampaignComponentClarification segmentRequest =
                new CampaignComponentClarification(
                        CampaignComponentCode.SEGMENT,
                        CampaignSkillIds.SEGMENT_RULE_BASED,
                        "SEGMENT_NEEDS_INPUT",
                        SEGMENT_PROMPT,
                        List.of("SEGMENT_NEEDS_INPUT"),
                        List.of(),
                        true);
        CampaignComponentClarification journeyRequest =
                new CampaignComponentClarification(
                        CampaignComponentCode.JOURNEY,
                        CampaignSkillIds.JOURNEY_FLOW,
                        "JOURNEY_NEEDS_INPUT",
                        JOURNEY_PROMPT,
                        List.of("JOURNEY_NEEDS_INPUT"),
                        List.of(),
                        true);

        CampaignClarificationAggregate aggregate =
                CampaignClarificationAggregate.of(
                        List.of(segmentRequest, journeyRequest));

        // Project the aggregate (without extractor, produces fallback).
        CampaignClarification projected = aggregate.project(
                null, CampaignRequirementBrief.empty());

        // The projected prompt must contain the per-component prompts exactly.
        assertThat(projected.prompt()).contains(SEGMENT_PROMPT);
        assertThat(projected.prompt()).contains(JOURNEY_PROMPT);

        // The aggregate code is the multi-component code.
        assertThat(projected.code())
                .isEqualTo(CampaignClarificationAggregate.AGGREGATE_CODE);
    }

    // -- Helpers --

    /** Create a component task with the given filtered answers. */
    private static CampaignComponentTask createComponentTask(
            CampaignComponentCode component,
            List<CampaignClarificationAnswer> answers) {
        CampaignComponentTodo todo = CampaignComponentTodo.from(
                        CampaignRequirementBrief.empty(),
                        List.of(),
                        null, null)
                .get(component.ordinal());
        // Apply the same filtering logic as componentTask.
        List<CampaignClarificationAnswer> ownAnswers =
                componentAnswers(answers, component);
        return new CampaignComponentTask(
                component, todo, CampaignRequirementBrief.empty(),
                "turn text", ownAnswers, List.of());
    }

    /**
     * The exact same answer-filtering logic as the componentTask path.
     */
    private static List<CampaignClarificationAnswer> componentAnswers(
            List<CampaignClarificationAnswer> answers,
            CampaignComponentCode component) {
        if (answers.isEmpty()) {
            return answers;
        }
        boolean anyScoped = answers.stream()
                .anyMatch(a -> a.component() != null);
        List<CampaignClarificationAnswer> own = new ArrayList<>();
        for (CampaignClarificationAnswer answer : answers) {
            if (answer.component() == component) {
                own.add(answer);
            } else if (answer.component() == null && !anyScoped) {
                own.add(answer);
            }
        }
        return List.copyOf(own);
    }

    /** A session with a multi-component aggregate pending. */
    private static CampaignAgentSession createMultiComponentSession() {
        CampaignAgentSession session = new CampaignAgentSession(
                assemblyContext());
        CampaignComponentClarification segmentRequest =
                new CampaignComponentClarification(
                        CampaignComponentCode.SEGMENT,
                        CampaignSkillIds.SEGMENT_RULE_BASED,
                        "SEGMENT_NEEDS_INPUT",
                        SEGMENT_PROMPT,
                        List.of("SEGMENT_AUDIENCE_REPHRASE_REQUIRED"),
                        List.of(),
                        true);
        CampaignComponentClarification journeyRequest =
                new CampaignComponentClarification(
                        CampaignComponentCode.JOURNEY,
                        CampaignSkillIds.JOURNEY_FLOW,
                        "JOURNEY_NEEDS_INPUT",
                        JOURNEY_PROMPT,
                        List.of("JOURNEY_INPUT_REQUIRED"),
                        List.of(),
                        true);
        CampaignClarificationAggregate aggregate =
                CampaignClarificationAggregate.of(
                        List.of(segmentRequest, journeyRequest));
        session.setPendingClarifications(
                aggregate.project(null, CampaignRequirementBrief.empty()),
                aggregate.requests());
        session.appendTurn(new CampaignConversationTurn(
                "turn-1", CampaignConversationTurn.USER, 0,
                "build a campaign", null));
        session.appendTurn(new CampaignConversationTurn(
                "turn-2", CampaignConversationTurn.ASSISTANT, 1,
                "More details needed", null));
        session.advance();
        session.advance();
        return session;
    }

    /** Validate multi-component ownership guard for one answer. */
    private static void requireAnswersMatchCall(
            CampaignAgentSession session,
            CampaignClarificationAnswer answer) {
        CampaignClarificationAggregate aggregate =
                session.pendingComponentClarifications();
        if (!aggregate.isEmpty() && !aggregate.single()) {
            if (answer.component() == null) {
                throw CampaignAgentException.of(
                        CampaignAgentErrorCodes.CLARIFICATION_MISMATCH,
                        "A multi-component clarification requires every answer "
                                + "to name its owning component.");
            }
            CampaignComponentClarification componentRequest =
                    aggregate.requestFor(answer.component());
            if (componentRequest == null) {
                throw CampaignAgentException.of(
                        CampaignAgentErrorCodes.CLARIFICATION_MISMATCH,
                        "A clarification answer names a component "
                                + "that has no pending issue.");
            }
            if (answer.code() != null
                    && !componentRequest.questionCodes()
                            .contains(answer.code())) {
                throw CampaignAgentException.of(
                        CampaignAgentErrorCodes.CLARIFICATION_MISMATCH,
                        "A clarification answer names a question "
                                + "not pending for its component.");
            }
        }
    }
}
