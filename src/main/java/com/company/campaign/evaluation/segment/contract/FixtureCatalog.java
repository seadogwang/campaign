package com.company.campaign.evaluation.segment.contract;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.shared.ImmutableValues;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The immutable catalog of fixtures and execution-context fixtures a corpus may reference.
 *
 * <p>Duplicate fixture ids are rejected at construction for the same reason duplicate snapshot ids are:
 * resolving one id to two datasets would make the expected member set depend on iteration order.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record FixtureCatalog(
        SchemaVersion schemaVersion,
        String catalogVersion,
        List<FixtureDescriptor> fixtures
) {
    private static final Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public FixtureCatalog {
        if (schemaVersion == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID, "Fixture catalog must declare a schema version");
        }
        if (catalogVersion == null || !REFERENCE_ID.matcher(catalogVersion).matches()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ID_INVALID,
                    "Fixture catalog version '" + catalogVersion + "' is not a valid reference identifier");
        }
        if (fixtures == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID, "Fixture catalog must declare its fixture list");
        }
        Set<String> seen = new HashSet<>(fixtures.size());
        for (FixtureDescriptor fixture : fixtures) {
            if (fixture == null) {
                throw new EvaluationException(
                        EvaluationErrorCodes.ASSET_INVALID, "Fixture catalog contains a null fixture entry");
            }
            if (!seen.add(fixture.fixtureId())) {
                throw new EvaluationException(
                        EvaluationErrorCodes.DUPLICATE_ID,
                        "Fixture id '" + fixture.fixtureId() + "' is declared more than once",
                        Map.of("fixtureId", fixture.fixtureId()));
            }
        }
        fixtures = ImmutableValues.freezeList(fixtures);
    }

    /** The fixture with the given id, or {@code null} when the catalog does not contain it. */
    public FixtureDescriptor find(String fixtureId) {
        for (FixtureDescriptor fixture : fixtures) {
            if (fixture.fixtureId().equals(fixtureId)) {
                return fixture;
            }
        }
        return null;
    }
}