package com.company.campaign.builder.authoring;

import com.company.campaign.builder.authoring.testsupport.SegmentAuthoringFixtures;
import com.company.campaign.builder.compiler.CapabilityReadiness;
import com.company.campaign.evaluation.segment.gate.CapabilityState;
import com.company.campaign.evaluation.segment.gate.SegmentReadinessProjection;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T16 requirement: the accepted T14 {@code SegmentReadinessProjection} is consumed as a frozen compile input.
 *
 * <p>The projection is the Gate's own evidence and the compiler package deliberately reaches no Gate package,
 * so the neutral {@link CapabilityReadiness} the compile needs is derived here, in the one adapter, rather
 * than by teaching the generic compiler about a concrete capability.
 */
class SegmentAuthoringReadinessTest {

    @Test
    void anActiveProjectionBecomesAnExactActiveCompileInput() {
        CapabilityReadiness readiness = SegmentAuthoringReadiness.compileInput(
                projection(CapabilityState.ACTIVE, true, SegmentAuthoringFixtures.APPROVED_DECISION_HASH));

        assertThat(readiness.capabilityCode()).isEqualTo(SegmentAuthoringVersions.CAPABILITY_CODE);
        assertThat(readiness.state()).isEqualTo(CapabilityReadiness.ACTIVE);
        assertThat(readiness.activeReady()).isTrue();
        assertThat(readiness.active()).isTrue();
        assertThat(readiness.hasApprovedDecision()).isTrue();
        assertThat(readiness.approvedDecisionHash())
                .isEqualTo(SegmentAuthoringFixtures.APPROVED_DECISION_HASH);
        assertThat(readiness.currentInputsHash()).isEqualTo(SegmentAuthoringFixtures.GATE_INPUTS_HASH);
    }

    @Test
    void aNonActiveProjectionIsRepresentedHonestlyWithoutAnApprovedDecision() {
        CapabilityReadiness experimental = SegmentAuthoringReadiness.compileInput(
                projection(CapabilityState.EXPERIMENTAL, false, null));
        assertThat(experimental.state()).isEqualTo(CapabilityReadiness.EXPERIMENTAL);
        assertThat(experimental.active()).isFalse();
        assertThat(experimental.hasApprovedDecision()).isFalse();

        CapabilityReadiness stale = SegmentAuthoringReadiness.compileInput(
                projection(CapabilityState.EVALUATION_REQUIRED, false,
                        SegmentAuthoringFixtures.APPROVED_DECISION_HASH));
        assertThat(stale.state()).isEqualTo(CapabilityReadiness.EVALUATION_REQUIRED);
        assertThat(stale.active()).isFalse();
        assertThat(stale.hasApprovedDecision()).isTrue();
    }

    @Test
    void aProjectionForAnotherCapabilityIsNeverRelabelledAsThisOne() {
        SegmentReadinessProjection foreign = SegmentReadinessProjection.of(
                "message.template", CapabilityState.EXPERIMENTAL, false, List.of("CAPABILITY_NOT_GATED"),
                null, ContentHash.sha256("other".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> SegmentAuthoringReadiness.compileInput(foreign))
                .isInstanceOf(SegmentAuthoringException.class)
                .satisfies(failure -> assertThat(((SegmentAuthoringException) failure).code())
                        .isEqualTo(SegmentAuthoringErrorCodes.READINESS_INVALID));
        assertThatThrownBy(() -> SegmentAuthoringReadiness.compileInput(null))
                .isInstanceOf(SegmentAuthoringException.class);
    }

    private static SegmentReadinessProjection projection(
            CapabilityState state, boolean aiReady, ContentHash decision) {
        return SegmentReadinessProjection.of(
                SegmentAuthoringVersions.CAPABILITY_CODE, state, aiReady, List.of(),
                decision, SegmentAuthoringFixtures.GATE_INPUTS_HASH);
    }
}