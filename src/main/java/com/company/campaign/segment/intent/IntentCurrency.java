package com.company.campaign.segment.intent;

import com.company.campaign.shared.canonical.ContentHash;

/**
 * Closed immutable union of the currency slot.
 *
 * <p>Money never resolves on a guessed currency: a clause either states an explicit ISO-4217 code or
 * requests the reporting currency through an exact registered default, which the injected registry must
 * confirm by id, version and content hash. A missing currency slot on a money clause is a resolution
 * ambiguity, never a silent default.
 */
public sealed interface IntentCurrency
        permits IntentCurrency.Explicit, IntentCurrency.RegisteredDefault {

    /** An explicit ISO-4217 currency code. */
    record Explicit(String code) implements IntentCurrency {

        public Explicit {
            code = IntentTexts.requireCurrencyCode(code, "currency.code");
        }
    }

    /** A request for the reporting currency, named by an exact registered-default revision. */
    record RegisteredDefault(String defaultId, String version, ContentHash contentHash)
            implements IntentCurrency {

        public RegisteredDefault {
            defaultId = IntentTexts.requireReferenceId(defaultId, "currency.defaultId");
            version = IntentTexts.requireText(version, "currency.version");
            if (version.isEmpty() || contentHash == null) {
                throw IntentException.invalid(
                        IntentErrorCodes.DEFAULT_REFERENCE_INVALID,
                        "A registered currency reference requires an exact version and content hash");
            }
        }
    }
}