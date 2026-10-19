package com.company.campaign.evaluation.segment.reference;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.evaluation.segment.contract.EvaluationErrorCodes;
import com.company.campaign.evaluation.segment.contract.EvaluationException;
import com.company.campaign.shared.ImmutableValues;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The immutable catalog of trusted fixtures a reference corpus may use, one entry per fixture identity.
 *
 * <p>Nothing is discovered by directory listing: a fixture a case wants must be declared here with its role
 * and its exact payload hash, so adding a payload file to the corpus without reviewing the catalog cannot
 * change what an evaluation reads. Duplicate identities are refused rather than silently merged, because two
 * declarations of one fixture id would make "which role did this case use" ambiguous.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ReferenceFixtureCatalog(
        SchemaVersion schemaVersion,
        String catalogVersion,
        List<ReferenceFixtureDescriptor> fixtures
) {

    private static final Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public ReferenceFixtureCatalog {
        if (schemaVersion == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID,
                    "A reference fixture catalog must declare a schema version");
        }
        if (catalogVersion == null || !REFERENCE_ID.matcher(catalogVersion).matches()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ID_INVALID,
                    "Reference fixture catalog version '" + catalogVersion
                            + "' is not a valid reference identifier");
        }
        if (fixtures == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID,
                    "A reference fixture catalog must declare its fixtures");
        }
        Map<String, ReferenceFixtureDescriptor> byId = new LinkedHashMap<>();
        for (ReferenceFixtureDescriptor fixture : fixtures) {
            if (fixture == null) {
                throw new EvaluationException(
                        EvaluationErrorCodes.ASSET_INVALID,
                        "A reference fixture catalog must not carry a null fixture");
            }
            if (byId.putIfAbsent(fixture.fixtureId(), fixture) != null) {
                throw new EvaluationException(
                        EvaluationErrorCodes.DUPLICATE_ID,
                        "Reference fixture id '" + fixture.fixtureId() + "' is declared more than once",
                        Map.of("fixtureId", fixture.fixtureId()));
            }
        }
        fixtures = ImmutableValues.freezeList(fixtures);
    }

    /** The declared fixture with the given identity, or {@code null} when the catalog does not declare it. */
    public ReferenceFixtureDescriptor find(String fixtureId) {
        for (ReferenceFixtureDescriptor fixture : fixtures) {
            if (fixture.fixtureId().equals(fixtureId)) {
                return fixture;
            }
        }
        return null;
    }
}