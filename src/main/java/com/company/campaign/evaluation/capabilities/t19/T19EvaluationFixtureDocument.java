package com.company.campaign.evaluation.capabilities.t19;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.ImmutableValues;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One pack's bounded fixture document: its identity, contract version and its accepted and rejected fixtures
 * kept strictly apart.
 *
 * <p>A document refuses a fixture list that repeats one fixture id across either polarity, so the corpus can
 * never blur a valid payload into an invalid one. Both polarities must be present, because a document that
 * cannot tell a valid payload from an invalid one is not an evaluation asset.
 */
public record T19EvaluationFixtureDocument(
        String schemaVersion,
        String capabilityCode,
        String contractVersion,
        String assetId,
        String assetVersion,
        List<T19FixtureEntry> fixtures) {

    /** Largest permitted number of fixtures in one document. */
    public static final int MAX_FIXTURES = 256;

    private static final Pattern CAPABILITY = Pattern.compile("^[a-z][a-z0-9.-]{0,63}$");
    private static final Pattern VERSION = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/+-]{0,63}$");
    private static final Pattern ID = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public T19EvaluationFixtureDocument {
        if (schemaVersion == null || !VERSION.matcher(schemaVersion).matches()) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.ASSET_NON_CANONICAL,
                    "A T19 fixture document requires a bounded schema version.");
        }
        if (capabilityCode == null || !CAPABILITY.matcher(capabilityCode).matches()) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.ASSET_NON_CANONICAL,
                    "A T19 fixture document requires a canonical capability code.");
        }
        if (contractVersion == null || !VERSION.matcher(contractVersion).matches()) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.ASSET_NON_CANONICAL,
                    "A T19 fixture document requires an exact contract version.");
        }
        if (assetId == null || !ID.matcher(assetId).matches()
                || assetVersion == null || !VERSION.matcher(assetVersion).matches()) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.ASSET_NON_CANONICAL,
                    "A T19 fixture document requires a bounded asset identity and version.");
        }
        if (fixtures == null || fixtures.isEmpty() || fixtures.size() > MAX_FIXTURES) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.FIXTURE_POLARITY,
                    "A T19 fixture document requires a bounded, non-empty fixture list.");
        }
        List<T19FixtureEntry> copy = new ArrayList<>(fixtures.size());
        Set<String> ids = new HashSet<>(fixtures.size());
        boolean accepted = false;
        boolean rejected = false;
        for (T19FixtureEntry fixture : fixtures) {
            if (fixture == null) {
                throw new T19EvaluationException(
                        T19EvaluationErrorCodes.ASSET_NON_CANONICAL,
                        "A T19 fixture document must not carry a null fixture.");
            }
            if (!ids.add(fixture.fixtureId())) {
                throw new T19EvaluationException(
                        T19EvaluationErrorCodes.FIXTURE_DUPLICATE,
                        "A T19 fixture document must not repeat one fixture id.");
            }
            accepted |= fixture.accepted();
            rejected |= !fixture.accepted();
            copy.add(fixture);
        }
        if (!accepted || !rejected) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.FIXTURE_POLARITY,
                    "A T19 fixture document requires at least one accepted and one rejected fixture.");
        }
        fixtures = ImmutableValues.freezeList(copy);
    }

    /** Every fixture, in the declaration order of the document. */
    public List<T19FixtureEntry> allFixtures() {
        return fixtures;
    }

    /** The accepted fixtures, in declaration order. */
    public List<T19FixtureEntry> acceptedFixtures() {
        List<T19FixtureEntry> accepted = new ArrayList<>();
        for (T19FixtureEntry fixture : fixtures) {
            if (fixture.accepted()) {
                accepted.add(fixture);
            }
        }
        return List.copyOf(accepted);
    }

    /** The rejected fixtures, in declaration order. */
    public List<T19FixtureEntry> rejectedFixtures() {
        List<T19FixtureEntry> rejected = new ArrayList<>();
        for (T19FixtureEntry fixture : fixtures) {
            if (!fixture.accepted()) {
                rejected.add(fixture);
            }
        }
        return List.copyOf(rejected);
    }

    /** True when the document declares one fixture with the given id, whatever its polarity. */
    public boolean declares(String fixtureId) {
        for (T19FixtureEntry fixture : fixtures) {
            if (fixture.fixtureId().equals(fixtureId)) {
                return true;
            }
        }
        return false;
    }
}