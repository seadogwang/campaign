package com.company.campaign.builder.agent;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.List;

/**
 * The immutable, deterministic grounded explanation of one Agent run.
 *
 * <p>It is a closed, ordered collection of typed {@link ExplanationClaim claims} derived only from accepted
 * resolution, validation, plan and Preview evidence. It is never produced by a second unconstrained model call,
 * and it carries no free prose, SQL, physical name, hidden Metadata, member identifier or unsupported claim.
 * Its content hash covers every claim and is stable for identical evidence.
 */
public record GroundedExplanation(ContentHash explanationHash, List<ExplanationClaim> claims) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public GroundedExplanation {
        if (claims == null) {
            throw SegmentAgentException.invalid(
                    SegmentAgentErrorCodes.EXPLANATION_FAILED, "A grounded explanation requires its claims");
        }
        claims = List.copyOf(new ArrayList<>(claims));
        ContentHash expected = CANONICAL_JSON.hash(new Material(claims));
        if (explanationHash == null || !explanationHash.equals(expected)) {
            throw SegmentAgentException.invalid(
                    SegmentAgentErrorCodes.EXPLANATION_FAILED,
                    "An explanation hash must be the canonical hash of its claims");
        }
    }

    /** Publishes an explanation, computing its canonical hash from the exact claim list. */
    public static GroundedExplanation publish(List<ExplanationClaim> claims) {
        List<ExplanationClaim> copy = claims == null ? null : List.copyOf(new ArrayList<>(claims));
        ContentHash hash = CANONICAL_JSON.hash(new Material(copy));
        return new GroundedExplanation(hash, copy == null ? List.of() : copy);
    }

    /** The bounded stable references every claim cites, in deterministic order and without repetition. */
    public List<String> references() {
        List<String> references = new ArrayList<>();
        for (ExplanationClaim claim : claims) {
            for (String reference : claim.nodeIds()) {
                if (!references.contains(reference)) {
                    references.add(reference);
                }
            }
            for (String reference : claim.definitionIds()) {
                if (!references.contains(reference)) {
                    references.add(reference);
                }
            }
            for (String reference : claim.clauseIds()) {
                if (!references.contains(reference)) {
                    references.add(reference);
                }
            }
        }
        return List.copyOf(references);
    }

    /** The exact material the explanation hash covers. */
    private record Material(List<ExplanationClaim> claims) {

        private Material {
            claims = claims == null ? null : List.copyOf(claims);
        }
    }
}