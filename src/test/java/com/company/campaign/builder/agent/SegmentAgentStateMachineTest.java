package com.company.campaign.builder.agent;

import com.company.campaign.builder.skill.SegmentSkillLimits;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Requirement 8: the closed state machine rejects skipped, repeated, backward and post-terminal transitions
 * deterministically, records explicit transition evidence, and exposes no Save/Apply/Publish/Runtime success
 * state.
 */
class SegmentAgentStateMachineTest {

    private static SegmentAgentStateMachine started() {
        SegmentAgentStateMachine machine = new SegmentAgentStateMachine();
        machine.start();
        return machine;
    }

    private static void assertRefused(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOf(SegmentAgentException.class)
                .extracting(failure -> ((SegmentAgentException) failure).code())
                .isEqualTo(code);
    }

    @Test
    void theHappyPathRecordsOneExplicitTransitionPerState() {
        SegmentAgentStateMachine machine = started();
        machine.transition(SegmentAgentState.DISCOVERING, "DISCOVER", "metadata.discover");
        machine.transition(SegmentAgentState.RESOLVING, "RESOLVE", "intent.resolve");
        machine.transition(SegmentAgentState.GENERATING_DSL, "GENERATE", null);
        machine.transition(SegmentAgentState.VALIDATING, "VALIDATE", "dsl.validate");
        machine.transition(SegmentAgentState.PLANNING, "PLAN", "plan.build");
        machine.transition(SegmentAgentState.PREVIEWING, "PREVIEW", "preview.safe");
        machine.transition(SegmentAgentState.READY_FOR_PREVIEW, "READY", null);

        assertThat(machine.trace()).extracting(AgentTransition::toState).containsExactly(
                SegmentAgentState.UNDERSTANDING, SegmentAgentState.DISCOVERING, SegmentAgentState.RESOLVING,
                SegmentAgentState.GENERATING_DSL, SegmentAgentState.VALIDATING, SegmentAgentState.PLANNING,
                SegmentAgentState.PREVIEWING, SegmentAgentState.READY_FOR_PREVIEW);
        assertThat(machine.trace()).extracting(AgentTransition::sequence).containsExactly(0, 1, 2, 3, 4, 5, 6, 7);
        assertThat(machine.trace().get(0).fromState()).isNull();
        assertThat(machine.trace().get(1).fromState()).isEqualTo(SegmentAgentState.UNDERSTANDING);
        assertThat(machine.isTerminal()).isTrue();
        assertThatThrownBy(() -> machine.trace().add(null)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aRunCannotStartTwiceOrTransitionBeforeItStarts() {
        SegmentAgentStateMachine fresh = new SegmentAgentStateMachine();
        assertThat(fresh.notStarted()).isTrue();
        assertRefused(() -> fresh.transition(SegmentAgentState.DISCOVERING, "X", null),
                SegmentAgentErrorCodes.ILLEGAL_TRANSITION);

        SegmentAgentStateMachine machine = started();
        assertRefused(machine::start, SegmentAgentErrorCodes.ILLEGAL_TRANSITION);
    }

    @Test
    void aSkippedOrRepeatedTransitionIsRefused() {
        assertRefused(() -> started().transition(SegmentAgentState.VALIDATING, "SKIP", null),
                SegmentAgentErrorCodes.ILLEGAL_TRANSITION);
        assertRefused(() -> started().transition(SegmentAgentState.UNDERSTANDING, "REPEAT", null),
                SegmentAgentErrorCodes.ILLEGAL_TRANSITION);
        assertRefused(() -> started().transition(SegmentAgentState.READY_FOR_PREVIEW, "SKIP", null),
                SegmentAgentErrorCodes.ILLEGAL_TRANSITION);
    }

    @Test
    void aBackwardTransitionIsRefused() {
        SegmentAgentStateMachine machine = started();
        machine.transition(SegmentAgentState.DISCOVERING, "DISCOVER", null);
        machine.transition(SegmentAgentState.RESOLVING, "RESOLVE", null);
        machine.transition(SegmentAgentState.GENERATING_DSL, "GENERATE", null);
        assertRefused(() -> machine.transition(SegmentAgentState.RESOLVING, "BACK", null),
                SegmentAgentErrorCodes.ILLEGAL_TRANSITION);
    }

    @Test
    void everyTerminalStateRefusesFurtherTransitions() {
        SegmentAgentStateMachine unsupported = started();
        unsupported.transition(SegmentAgentState.UNSUPPORTED, "TERMINAL", null);

        SegmentAgentStateMachine invalid = started();
        invalid.transition(SegmentAgentState.INVALID, "TERMINAL", null);

        SegmentAgentStateMachine denied = started();
        denied.transition(SegmentAgentState.DISCOVERING, "DISCOVER", null);
        denied.transition(SegmentAgentState.DENIED, "TERMINAL", null);

        SegmentAgentStateMachine needsInput = started();
        needsInput.transition(SegmentAgentState.DISCOVERING, "DISCOVER", null);
        needsInput.transition(SegmentAgentState.RESOLVING, "RESOLVE", null);
        needsInput.transition(SegmentAgentState.NEEDS_INPUT, "TERMINAL", null);

        for (SegmentAgentStateMachine machine : List.of(unsupported, invalid, denied, needsInput)) {
            assertThat(machine.isTerminal()).isTrue();
            assertRefused(() -> machine.transition(SegmentAgentState.DISCOVERING, "AFTER", null),
                    SegmentAgentErrorCodes.ILLEGAL_TRANSITION);
        }
    }

    @Test
    void aRunCannotExceedItsSkillDeclaredStepBound() {
        SegmentAgentStateMachine bounded = new SegmentAgentStateMachine(2);
        assertThat(bounded.maxSteps()).isEqualTo(2);
        bounded.start();
        bounded.transition(SegmentAgentState.DISCOVERING, "DISCOVER", null);

        assertRefused(() -> bounded.transition(SegmentAgentState.RESOLVING, "RESOLVE", null),
                SegmentAgentErrorCodes.ILLEGAL_TRANSITION);
        assertThat(bounded.trace()).hasSize(2);
        assertRefused(() -> new SegmentAgentStateMachine(0), SegmentAgentErrorCodes.ILLEGAL_TRANSITION);
        assertRefused(() -> new SegmentAgentStateMachine(SegmentSkillLimits.MAX_STEPS + 1),
                SegmentAgentErrorCodes.ILLEGAL_TRANSITION);
    }

    @Test
    void theTerminalVocabularyHasNoSaveApplyPublishOrRuntimeSuccessState() {
        List<String> names = Arrays.stream(SegmentAgentState.values()).map(Enum::name).toList();
        assertThat(names).containsExactlyInAnyOrder(
                "UNDERSTANDING", "DISCOVERING", "RESOLVING", "GENERATING_DSL", "VALIDATING", "PLANNING",
                "PREVIEWING", "READY_FOR_PREVIEW", "NEEDS_INPUT", "UNSUPPORTED", "DENIED", "INVALID");
        assertThat(names).noneMatch(name -> name.contains("SAVE") || name.contains("APPLY")
                || name.contains("PUBLISH") || name.contains("ACTIVE") || name.contains("AI_READY")
                || name.contains("LIVE_READY") || name.contains("WAITING"));
    }
}