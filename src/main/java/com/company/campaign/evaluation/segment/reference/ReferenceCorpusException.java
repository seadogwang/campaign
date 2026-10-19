package com.company.campaign.evaluation.segment.reference;

import com.company.campaign.shared.error.ContractException;

import java.util.Map;

/**
 * A structural reference-corpus failure detected while loading or constructing a contract.
 *
 * <p>Failures are reserved for conditions that make an asset unusable on its own — a malformed pin, a
 * duplicated site identity, an internally contradictory expectation — because those must stop the load
 * rather than be collected. Cross-asset findings are reported as {@code Issue} entries by
 * {@link ReferenceCorpusValidator} instead, so one report can list every problem at once.
 */
public final class ReferenceCorpusException extends ContractException {

    private static final long serialVersionUID = 1L;

    public ReferenceCorpusException(String code, String message) {
        this(code, message, Map.of());
    }

    public ReferenceCorpusException(String code, String message, Map<String, Object> details) {
        super(code, message, details);
    }
}