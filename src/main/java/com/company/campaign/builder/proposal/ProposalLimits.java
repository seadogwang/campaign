package com.company.campaign.builder.proposal;

import com.company.campaign.shared.canonical.CanonicalJson;

import java.util.Collection;
import java.util.Map;

/**
 * Bounded request budget for the strict CampaignProposalPatch boundary.
 *
 * <p>Bounds are values rather than constants so a host can tighten them per channel, but each one has an
 * absolute maximum a configured value may never exceed: a mistyped configuration must not turn the codec
 * into an unbounded allocation. A value outside {@code [1, bound]} is rejected rather than silently clamped,
 * because a silently weakened limit is indistinguishable from one that was never applied.
 *
 * <p>Enforcement happens before any value is bound, so no part of the codec ever observes a truncated or
 * partially validated document.
 */
public record ProposalLimits(
        int maxRequestBytes,
        int maxDepth,
        int maxCollectionSize,
        int maxOperations) {

    /** Absolute request body bound, in bytes. */
    public static final int MAX_REQUEST_BYTES = 262_144;

    /** Absolute nesting depth bound, counting the root document as depth 1. */
    public static final int MAX_DEPTH = 24;

    /** Absolute bound on any one JSON array or object width. */
    public static final int MAX_COLLECTION_SIZE = 1_024;

    /** Absolute bound on the number of operations in one proposal. */
    public static final int MAX_OPERATIONS = 256;

    /** A conservative default budget for a model- or Visual-Builder-submitted proposal. */
    public static final ProposalLimits DEFAULT =
            new ProposalLimits(MAX_REQUEST_BYTES, MAX_DEPTH, MAX_COLLECTION_SIZE, MAX_OPERATIONS);

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public ProposalLimits {
        requireWithin("maxRequestBytes", maxRequestBytes, MAX_REQUEST_BYTES);
        requireWithin("maxDepth", maxDepth, MAX_DEPTH);
        requireWithin("maxCollectionSize", maxCollectionSize, MAX_COLLECTION_SIZE);
        requireWithin("maxOperations", maxOperations, MAX_OPERATIONS);
    }

    /**
     * Enforces the byte, depth and collection bounds against a parsed JSON document.
     *
     * @throws ProposalException with {@link ProposalErrorCodes#PROPOSAL_LIMIT_EXCEEDED} when any bound is
     *         exceeded; the failure never names the offending value or its size.
     */
    public void enforce(Object value) {
        int bytes;
        try {
            bytes = CANONICAL_JSON.write(value).length;
        } catch (RuntimeException exception) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_SHAPE_INVALID);
        }
        if (bytes > maxRequestBytes) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_LIMIT_EXCEEDED);
        }
        enforceDepth(value, 1);
        enforceCollections(value);
    }

    private void enforceDepth(Object value, int depth) {
        if (depth > maxDepth) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_LIMIT_EXCEEDED);
        }
        if (value instanceof Map<?, ?> map) {
            for (Object element : map.values()) {
                enforceDepth(element, depth + 1);
            }
        } else if (value instanceof Collection<?> collection) {
            for (Object element : collection) {
                enforceDepth(element, depth + 1);
            }
        }
    }

    private void enforceCollections(Object value) {
        if (value instanceof Map<?, ?> map) {
            checkWidth(map.size());
            for (Object element : map.values()) {
                enforceCollections(element);
            }
        } else if (value instanceof Collection<?> collection) {
            checkWidth(collection.size());
            for (Object element : collection) {
                enforceCollections(element);
            }
        }
    }

    private void checkWidth(int width) {
        if (width > maxCollectionSize) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_LIMIT_EXCEEDED);
        }
    }

    private static void requireWithin(String field, int value, int bound) {
        if (value <= 0 || value > bound) {
            throw new IllegalArgumentException(field + " must be within [1, " + bound + "]");
        }
    }
}