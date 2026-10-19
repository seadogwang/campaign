package com.company.campaign.builder.proposal;

import com.company.campaign.shared.error.ContractException;

import java.util.Map;

/**
 * Raised when the strict T15 CampaignProposalPatch boundary rejects a wire request.
 *
 * <p>Every failure is a stable bounded code plus fixed prose. The message and details are identical for every
 * occurrence of a code and never contain a payload value, a field name, a logical identifier or a thrown
 * cause: an attacker-controlled name must not become observable output, and a rejected request must not be
 * usable as a probe.
 */
public final class ProposalException extends ContractException {

    private static final long serialVersionUID = 1L;

    public ProposalException(String code, String message) {
        super(code, message, Map.of());
    }

    /** The one fixed failure for a code; the message is part of the stable contract, not diagnostics. */
    public static ProposalException of(String code) {
        return new ProposalException(code, messageFor(code));
    }

    private static String messageFor(String code) {
        return switch (code) {
            case ProposalErrorCodes.PROPOSAL_MISSING -> "Campaign proposal request is missing";
            case ProposalErrorCodes.PROPOSAL_LIMIT_EXCEEDED -> "Campaign proposal request exceeds a bound";
            case ProposalErrorCodes.PROPOSAL_ENCODING_INVALID -> "Campaign proposal request is not valid UTF-8";
            case ProposalErrorCodes.PROPOSAL_JSON_MALFORMED -> "Campaign proposal request is not valid JSON";
            case ProposalErrorCodes.PROPOSAL_DUPLICATE_KEY -> "Campaign proposal request repeats an object key";
            case ProposalErrorCodes.PROPOSAL_SHAPE_INVALID -> "Campaign proposal request has an invalid shape";
            case ProposalErrorCodes.PROPOSAL_MISSING_FIELD -> "Campaign proposal request is missing a field";
            case ProposalErrorCodes.PROPOSAL_NULL_FIELD -> "Campaign proposal request has a null field";
            case ProposalErrorCodes.PROPOSAL_SCHEMA_VERSION_UNSUPPORTED ->
                    "Campaign proposal schema version is not supported";
            case ProposalErrorCodes.PROPOSAL_MODE_INVALID -> "Campaign proposal mode is not supported";
            case ProposalErrorCodes.PROPOSAL_CAMPAIGN_ID_INVALID -> "Campaign proposal campaign identifier is invalid";
            case ProposalErrorCodes.PROPOSAL_BASE_REVISIONS_INVALID -> "Campaign proposal base revisions are invalid";
            case ProposalErrorCodes.PROPOSAL_OPERATIONS_INVALID -> "Campaign proposal operations are invalid";
            case ProposalErrorCodes.PROPOSAL_IDENTITY_INVALID -> "Campaign proposal identity is invalid";
            case ProposalErrorCodes.PROPOSAL_ANCHOR_INVALID -> "Campaign proposal move anchor is invalid";
            case ProposalErrorCodes.PROPOSAL_CLIENT_REF_CONFLICT -> "Campaign proposal client reference conflicts";
            case ProposalErrorCodes.PROPOSAL_ORDER_CYCLE -> "Campaign proposal ordering is contradictory";
            case ProposalErrorCodes.PROPOSAL_RESERVED_FIELD -> "Campaign proposal carries a reserved field";
            case ProposalErrorCodes.PROPOSAL_PAYLOAD_INVALID -> "Campaign proposal payload is invalid";
            case ProposalErrorCodes.PROPOSAL_CLIENT_CONTEXT_INVALID ->
                    "Campaign proposal client context is invalid";
            case ProposalErrorCodes.PROPOSAL_TOPOLOGY_SCHEMA_VERSION_UNSUPPORTED ->
                    "Campaign proposal schema version does not carry a Journey topology";
            default -> "Campaign proposal request is invalid";
        };
    }
}