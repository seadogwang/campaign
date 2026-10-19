package com.company.campaign.builder.capability.flow;

import com.company.campaign.builder.capability.pack.CapabilityEvaluationFixture.Expectation;

import java.util.Map;
import java.util.Objects;

/**
 * One in-code evaluation fixture of a Flow node pack: a bounded identifier, its polarity and the exact payload
 * document it exercises.
 *
 * <p>The payload document is kept only so the fixture can be driven through the pack it belongs to and hashed
 * into the pack's pinned evaluation asset. It is a deterministic contract fixture — never a template body, a
 * reward secret, a credential, a member identifier or a provider response — and it is frozen at construction,
 * so a fixture cannot be edited after the asset that pins it has been hashed.
 */
record FlowEvaluationFixture(String fixtureId, Expectation expectation, Map<String, Object> payload) {

    FlowEvaluationFixture {
        Objects.requireNonNull(fixtureId, "fixtureId");
        Objects.requireNonNull(expectation, "expectation");
        Objects.requireNonNull(payload, "payload");
        payload = Map.copyOf(payload);
    }

    /** True when this fixture pins a payload the pack must accept. */
    boolean isAccepted() {
        return expectation == Expectation.ACCEPTED;
    }

    @Override
    public String toString() {
        return "FlowEvaluationFixture[id=" + fixtureId + ", expectation=" + expectation + ']';
    }
}