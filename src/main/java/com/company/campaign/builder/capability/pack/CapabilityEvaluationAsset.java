package com.company.campaign.builder.capability.pack;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.ImmutableValues;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The versioned evaluation asset a capability pack pins: its identity, its exact version, the canonical
 * content hash of the asset and its positive and negative fixtures, kept strictly apart.
 *
 * <p>The two fixture lists are the distinction the pack contract requires. A positive fixture pins a payload
 * the pack must accept; a negative fixture pins one it must refuse. The asset refuses a positive fixture in
 * the negative list, a negative fixture in the positive list, a fixture id repeated across either list, an
 * unbounded list or a missing pin, so the two sets can never blur into one undifferentiated corpus. The
 * asset declares at least one fixture of each polarity: an asset that cannot tell a valid payload from an
 * invalid one is not an evaluation asset.
 *
 * <p>The asset is capability-agnostic and deterministic: fixture ids are frozen into one order, the content
 * hash is the one the host computed over the canonical asset, and nothing here holds a payload body, a
 * template text, a reward secret, a credential, a member identifier or a provider response.
 */
public record CapabilityEvaluationAsset(
        String assetId,
        String assetVersion,
        ContentHash contentHash,
        List<CapabilityEvaluationFixture> positiveFixtures,
        List<CapabilityEvaluationFixture> negativeFixtures) {

    /** Largest permitted number of fixtures of one polarity. */
    public static final int MAX_FIXTURES = 256;

    private static final Pattern ASSET_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);
    private static final Pattern VERSION = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/+-]{0,63}$");

    public CapabilityEvaluationAsset {
        if (assetId == null || !ASSET_ID.matcher(assetId).matches()) {
            throw CapabilityPackSafety.invalid("capabilityPackEvaluationAssetId");
        }
        CapabilityPackSafety.requireSafe(assetId, "capabilityPackEvaluationAssetId");
        if (assetVersion == null || !VERSION.matcher(assetVersion).matches()) {
            throw CapabilityPackSafety.invalid("capabilityPackEvaluationAssetVersion");
        }
        if (contentHash == null) {
            throw CapabilityPackSafety.invalid("capabilityPackEvaluationAssetHash");
        }
        positiveFixtures = fixtures(positiveFixtures, CapabilityEvaluationFixture.Expectation.ACCEPTED,
                "capabilityPackEvaluationPositiveFixtures");
        negativeFixtures = fixtures(negativeFixtures, CapabilityEvaluationFixture.Expectation.REJECTED,
                "capabilityPackEvaluationNegativeFixtures");
        if (positiveFixtures.isEmpty() || negativeFixtures.isEmpty()) {
            throw CapabilityPackSafety.invalid("capabilityPackEvaluationFixturePolarity");
        }
        Set<String> ids = new HashSet<>(positiveFixtures.size() + negativeFixtures.size());
        for (CapabilityEvaluationFixture fixture : positiveFixtures) {
            if (!ids.add(fixture.fixtureId())) {
                throw CapabilityPackSafety.invalid("capabilityPackEvaluationFixtureDuplicate");
            }
        }
        for (CapabilityEvaluationFixture fixture : negativeFixtures) {
            if (!ids.add(fixture.fixtureId())) {
                throw CapabilityPackSafety.invalid("capabilityPackEvaluationFixtureDuplicate");
            }
        }
    }

    /** Every fixture, positive then negative, in one deterministic fixture-id order within each polarity. */
    public List<CapabilityEvaluationFixture> allFixtures() {
        List<CapabilityEvaluationFixture> all = new ArrayList<>(positiveFixtures.size() + negativeFixtures.size());
        all.addAll(positiveFixtures);
        all.addAll(negativeFixtures);
        return List.copyOf(all);
    }

    /**
     * Proves one fixture list is bounded and non-null, holds only fixtures of the expected polarity, and
     * freezes it into one deterministic fixture-id order.
     */
    private static List<CapabilityEvaluationFixture> fixtures(
            List<CapabilityEvaluationFixture> fixtures,
            CapabilityEvaluationFixture.Expectation expected,
            String invariant) {
        if (fixtures == null || fixtures.size() > MAX_FIXTURES) {
            throw CapabilityPackSafety.invalid(invariant);
        }
        List<CapabilityEvaluationFixture> copy = new ArrayList<>(fixtures.size());
        for (CapabilityEvaluationFixture fixture : fixtures) {
            if (fixture == null) {
                throw CapabilityPackSafety.invalid(invariant);
            }
            if (fixture.expectation() != expected) {
                throw CapabilityPackSafety.invalid("capabilityPackEvaluationFixturePolarity");
            }
            copy.add(fixture);
        }
        copy.sort(Comparator.comparing(CapabilityEvaluationFixture::fixtureId));
        return ImmutableValues.freezeList(copy);
    }

    @Override
    public String toString() {
        return "CapabilityEvaluationAsset[id=" + assetId
                + ", version=" + assetVersion
                + ", positive=" + positiveFixtures.size()
                + ", negative=" + negativeFixtures.size()
                + ", hash=" + contentHash + ']';
    }
}