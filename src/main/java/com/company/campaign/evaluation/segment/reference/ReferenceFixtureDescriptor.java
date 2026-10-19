package com.company.campaign.evaluation.segment.reference;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.evaluation.segment.contract.EvaluationAssetPatterns;
import com.company.campaign.evaluation.segment.contract.EvaluationErrorCodes;
import com.company.campaign.evaluation.segment.contract.EvaluationException;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * One host-owned trusted fixture the reference corpus declares: its identity, its role, its revision and the
 * exact bytes it must have.
 *
 * <p>The payload hash is what makes a fixture a trusted input rather than a file: a case reads a port built
 * from the payload the manifest pinned, so editing a fixture payload without re-reviewing the catalog is a
 * hash mismatch rather than a silently different evaluation.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReferenceFixtureDescriptor(
        String fixtureId,
        ReferenceFixtureRole role,
        String fixtureVersion,
        ContentHash payloadHash,
        String payloadPath
) {

    private static final Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);
    private static final Pattern RELATIVE_JSON = Pattern.compile(EvaluationAssetPatterns.REPOSITORY_RELATIVE_JSON);

    public ReferenceFixtureDescriptor {
        if (fixtureId == null || !REFERENCE_ID.matcher(fixtureId).matches()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ID_INVALID,
                    "Reference fixture id '" + fixtureId + "' is not a valid reference identifier");
        }
        if (role == null) {
            throw new ReferenceCorpusException(
                    ReferenceCorpusErrorCodes.FIXTURE_ROLE_MISMATCH,
                    "Reference fixture '" + fixtureId + "' must declare the role it fills");
        }
        if (fixtureVersion == null || !REFERENCE_ID.matcher(fixtureVersion).matches()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ID_INVALID,
                    "Reference fixture version '" + fixtureVersion + "' is not a valid reference identifier");
        }
        if (payloadHash == null) {
            throw new ReferenceCorpusException(
                    ReferenceCorpusErrorCodes.EXPECTATION_INCOMPLETE,
                    "Reference fixture '" + fixtureId + "' must declare the content hash of its payload");
        }
        if (payloadPath == null || !RELATIVE_JSON.matcher(payloadPath).matches()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.PATH_MALFORMED,
                    "Reference fixture payload path '" + payloadPath
                            + "' is not a repository-relative JSON asset path",
                    Map.of("path", String.valueOf(payloadPath)));
        }
    }
}