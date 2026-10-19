package com.company.campaign.builder.capability.pack;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One versioned evaluation fixture of a capability pack, carrying its polarity.
 *
 * <p>A fixture is a bounded identifier, an explicit expectation and the content hash of the canonical
 * payload it exercises. It carries no payload body, no template text, no reward secret, no credential, no
 * member identifier and no provider response — only enough to state which canonical payload the fixture
 * pins and whether that payload must be accepted or refused. The payload itself lives with the asset under
 * {@code evaluation/capabilities/...} and is addressed by its hash.
 *
 * <p>The polarity is a fact of the fixture, not of the list it appears in, so a positive fixture can never
 * be carried in the negative list and the two sets stay distinguishable.
 */
public record CapabilityEvaluationFixture(
        String fixtureId,
        Expectation expectation,
        ContentHash payloadHash) {

    /** The closed expectation a fixture declares. */
    public enum Expectation {

        /** The pinned payload must resolve to a normalized component. */
        ACCEPTED,

        /** The pinned payload must be refused by the pack. */
        REJECTED
    }

    private static final Pattern FIXTURE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public CapabilityEvaluationFixture {
        if (fixtureId == null || !FIXTURE_ID.matcher(fixtureId).matches()) {
            throw CapabilityPackSafety.invalid("capabilityPackEvaluationFixtureId");
        }
        CapabilityPackSafety.requireSafe(fixtureId, "capabilityPackEvaluationFixtureId");
        if (expectation == null) {
            throw CapabilityPackSafety.invalid("capabilityPackEvaluationFixtureExpectation");
        }
        if (payloadHash == null) {
            throw CapabilityPackSafety.invalid("capabilityPackEvaluationFixtureHash");
        }
    }

    /** True when the fixture pins a payload that must be accepted. */
    public boolean isPositive() {
        return expectation == Expectation.ACCEPTED;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CapabilityEvaluationFixture fixture
                && Objects.equals(fixtureId, fixture.fixtureId)
                && expectation == fixture.expectation
                && Objects.equals(payloadHash, fixture.payloadHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fixtureId, expectation, payloadHash);
    }

    @Override
    public String toString() {
        return "CapabilityEvaluationFixture[id=" + fixtureId + ", expectation=" + expectation + ']';
    }
}