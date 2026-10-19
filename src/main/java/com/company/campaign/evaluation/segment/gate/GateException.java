package com.company.campaign.evaluation.segment.gate;

import com.company.campaign.shared.error.ContractException;

import java.util.Map;

/**
 * A Gate failure detected while constructing a Gate contract, evaluating thresholds or approving a decision.
 *
 * <p>The code is always one of {@link GateErrorCodes}, and it is the only machine interface: the message is
 * human-readable and must never be parsed. No instance carries an utterance, a model response, a credential, a
 * stack trace, SQL, a storage identifier, a tenant/user/DataScope fact, unrestricted Metadata or a member
 * identifier.
 */
public final class GateException extends ContractException {

    private static final long serialVersionUID = 1L;

    public GateException(String code, String message) {
        this(code, message, Map.of());
    }

    public GateException(String code, String message, Map<String, Object> details) {
        super(code, message, details);
    }

    /** Builds a bounded Gate failure with the given stable code. */
    public static GateException of(String code, String message) {
        return new GateException(code, message);
    }
}