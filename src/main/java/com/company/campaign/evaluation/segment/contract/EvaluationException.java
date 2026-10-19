package com.company.campaign.evaluation.segment.contract;

import com.company.campaign.shared.error.ContractException;

import java.util.Map;

/**
 * A structural evaluation-asset failure detected while loading or constructing a contract.
 *
 * <p>The code is always one of {@link EvaluationErrorCodes}. Failures are reserved for conditions that
 * make an asset unusable on its own — a path, a hash, a duplicate identity, a malformed value — because
 * those must stop loading rather than be collected. Cross-asset semantic findings are reported as
 * {@code Issue} entries by the validators instead, so one report can list every problem at once.
 */
public final class EvaluationException extends ContractException {

    private static final long serialVersionUID = 1L;

    public EvaluationException(String code, String message) {
        this(code, message, Map.of());
    }

    public EvaluationException(String code, String message, Map<String, Object> details) {
        super(code, message, details);
    }

    public EvaluationException(String code, String message, Map<String, Object> details, Throwable cause) {
        super(code, message, details, cause);
    }
}