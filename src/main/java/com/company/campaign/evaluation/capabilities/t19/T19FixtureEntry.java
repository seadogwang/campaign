package com.company.campaign.evaluation.capabilities.t19;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One evaluation fixture: a bounded id, its polarity and the content hash of the canonical payload it
 * exercises.
 *
 * <p>It carries no payload body, no template text, no reward secret, no credential, no member identifier and
 * no provider response — only an id and a hash — so a fixture can be published and reviewed without exposing
 * anything a pack must not expose.
 */
public record T19FixtureEntry(String fixtureId, String expectation, ContentHash payloadHash) {

    /** The accepted polarity of a fixture. */
    public static final String ACCEPTED = "ACCEPTED";

    /** The rejected polarity of a fixture. */
    public static final String REJECTED = "REJECTED";

    private static final Set<String> POLARITIES = Set.of(ACCEPTED, REJECTED);
    private static final Pattern ID = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public T19FixtureEntry {
        if (fixtureId == null || !ID.matcher(fixtureId).matches()) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.ASSET_NON_CANONICAL,
                    "A T19 evaluation fixture requires a bounded fixture id.");
        }
        if (expectation == null || !POLARITIES.contains(expectation)) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.FIXTURE_POLARITY,
                    "A T19 evaluation fixture requires a accepted or rejected polarity.");
        }
        if (payloadHash == null) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.ASSET_NON_CANONICAL,
                    "A T19 evaluation fixture requires the hash of the payload it exercises.");
        }
    }

    /** True when this fixture pins a payload the pack must accept. */
    public boolean accepted() {
        return ACCEPTED.equals(expectation);
    }

    /** A bounded diagnostic: only the id, polarity and hash, never a payload. */
    @Override
    public String toString() {
        return "T19FixtureEntry[fixtureId=" + fixtureId
                + ", expectation=" + expectation.toUpperCase(Locale.ROOT)
                + ", payloadHash=" + payloadHash + ']';
    }
}