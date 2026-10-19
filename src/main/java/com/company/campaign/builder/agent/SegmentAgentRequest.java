package com.company.campaign.builder.agent;

import com.company.campaign.segment.resolution.ClarificationSelectionReceipt;

import java.util.ArrayList;
import java.util.List;

/**
 * The bounded, model-controllable part of one formal Agent request.
 *
 * <p>It carries only the user utterance and an optional list of the exact opaque T10 clarification-selection
 * receipts. It has no tenant, program, user, role, permission, DataScope, Metadata/Skill/version override,
 * model/provider setting, trusted time, backend choice, SQL, Save/Publish flag or precomputed
 * resolution/DSL/plan/preview artifact, so a caller cannot inject a trusted fact or a pipeline artifact by
 * shaping the request. Trusted context, run/conversation identity and every time pin are host inputs outside
 * this payload.
 *
 * <p>This version deliberately carries <em>no</em> conversation presentation turns. The first implementation
 * accepted them and then ignored them, which meant a caller could pass semantically inert input that silently
 * changed nothing: the bounded model request and the result/audit fingerprints never saw it. Rather than keep a
 * field whose only effect is to look meaningful, the formal request of this Skill version contains the
 * utterance alone; a versioned history contract with its own explicit fingerprint would be a separate design.
 */
public record SegmentAgentRequest(
        String utterance,
        List<ClarificationSelectionReceipt> continuation
) {

    public SegmentAgentRequest {
        if (utterance == null) {
            throw SegmentAgentException.invalid(
                    SegmentAgentErrorCodes.REQUEST_INVALID, "An Agent request requires an utterance");
        }
        if (utterance.length() > SegmentAgentLimits.MAX_UTTERANCE_LENGTH) {
            throw SegmentAgentException.invalid(
                    SegmentAgentErrorCodes.REQUEST_INVALID, "An Agent utterance exceeds its bound");
        }
        continuation = continuation == null ? List.of() : List.copyOf(new ArrayList<>(continuation));
        if (continuation.size() > SegmentAgentLimits.MAX_CONTINUATION_RECEIPTS) {
            throw SegmentAgentException.invalid(
                    SegmentAgentErrorCodes.REQUEST_INVALID, "An Agent continuation exceeds its bound");
        }
    }

    /** A request with no continuation. */
    public SegmentAgentRequest(String utterance) {
        this(utterance, List.of());
    }
}