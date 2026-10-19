package com.company.campaign.production.model;

import com.company.campaign.shared.error.ContractException;

import java.util.Map;

/**
 * A fail-closed production model failure carrying exactly one stable {@link ProductionModelErrorCodes} code.
 *
 * <p>It never carries the provider payload, the endpoint, the credential, the model id, a prompt, a schema or
 * any other provider-derived text: only the stable code and a fixed caller-safe message. It is the only
 * exception type the production model boundary raises.
 */
public class ProductionModelException extends ContractException {

    private static final long serialVersionUID = 1L;

    public ProductionModelException(String code, String message) {
        super(code, message, Map.of());
    }

    public ProductionModelException(String code, String message, Throwable cause) {
        super(code, message, Map.of(), cause);
    }

    /** A bounded failure carrying one stable code and a value-free message. */
    public static ProductionModelException of(String code, String message) {
        return new ProductionModelException(code, message);
    }

    /** True when the failure is an explicitly retryable pre-acceptance transport or provider condition. */
    public boolean retryable() {
        return ProductionModelErrorCodes.MODEL_RATE_LIMITED.equals(code())
                || ProductionModelErrorCodes.MODEL_UNAVAILABLE.equals(code())
                || ProductionModelErrorCodes.MODEL_TIMEOUT.equals(code())
                || ProductionModelErrorCodes.MODEL_TRANSPORT_FAILED.equals(code());
    }
}