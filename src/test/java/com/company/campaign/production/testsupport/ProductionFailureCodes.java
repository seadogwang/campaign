package com.company.campaign.production.testsupport;

import com.company.campaign.shared.error.ContractException;

/**
 * Reads the first bounded contract code out of a Spring startup failure chain.
 *
 * <p>Spring wraps a bean-creation failure in one or more framework exceptions, so a test that asserts a bounded
 * fail-closed code must walk the cause chain rather than match the framework's own type.
 */
public final class ProductionFailureCodes {

    private ProductionFailureCodes() {
    }

    /** The first {@link ContractException} code in the cause chain, or null when there is none. */
    public static String codeOf(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ContractException contractException) {
                return contractException.code();
            }
            Throwable next = current.getCause();
            current = next == current ? null : next;
        }
        return null;
    }
}