package com.company.campaign.evaluation.segment.contract;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One immutable fixture entry in the evaluation fixture catalog.
 *
 * <p>A fixture owns the evaluation instant it was built for: {@code asOf}, the timezone those instants are
 * read in, and the {@code dataWatermark} the source had observed. All three are declared, never derived
 * from a clock, so a replay compares against exactly the boundaries that were reviewed. A watermark later
 * than {@code asOf} is contradictory and is rejected here.
 *
 * <p>{@code memberUniverse} is the declared set of member identifiers the fixture may contain. It is
 * declared rather than extracted from the payload because the payload's event-table shape belongs to T09;
 * the universe is what lets the validator prove an expected member set is even representable.
 *
 * <p>{@code payloadPath} and {@code payloadHash} pin the fixture bytes. For
 * {@link FixtureRole#EXECUTION_CONTEXT}, those bytes are a trusted execution context; for other roles they
 * are an opaque dataset in T03.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record FixtureDescriptor(
        String fixtureId,
        String fixtureVersion,
        FixtureRole role,
        String payloadPath,
        ContentHash payloadHash,
        Instant asOf,
        ZoneId timezone,
        Instant dataWatermark,
        MemberIdSet memberUniverse
) {
    private static final Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);
    private static final Pattern RELATIVE_JSON = Pattern.compile(EvaluationAssetPatterns.REPOSITORY_RELATIVE_JSON);

    public FixtureDescriptor {
        if (fixtureId == null || !REFERENCE_ID.matcher(fixtureId).matches()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ID_INVALID,
                    "Fixture id '" + fixtureId + "' is not a valid reference identifier");
        }
        if (fixtureVersion == null || !REFERENCE_ID.matcher(fixtureVersion).matches()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ID_INVALID,
                    "Fixture version '" + fixtureVersion + "' is not a valid reference identifier");
        }
        if (role == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID, "Fixture '" + fixtureId + "' must declare its role");
        }
        if (payloadPath == null || !RELATIVE_JSON.matcher(payloadPath).matches()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.PATH_MALFORMED,
                    "Fixture payload path '" + payloadPath + "' is not a repository-relative JSON asset path",
                    Map.of("path", String.valueOf(payloadPath)));
        }
        if (payloadHash == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID,
                    "Fixture '" + fixtureId + "' must declare its payload content hash");
        }
        if (asOf == null || timezone == null || dataWatermark == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.FIXTURE_TIME_CONTEXT_INVALID,
                    "Fixture '" + fixtureId + "' must declare a fixed asOf, timezone and dataWatermark");
        }
        if (dataWatermark.isAfter(asOf)) {
            throw new EvaluationException(
                    EvaluationErrorCodes.FIXTURE_TIME_CONTEXT_INVALID,
                    "Fixture '" + fixtureId + "' declares dataWatermark " + dataWatermark
                            + " after asOf " + asOf);
        }
        if (memberUniverse == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID,
                    "Fixture '" + fixtureId + "' must declare its member universe, even when it is empty");
        }
    }
}