package com.company.campaign.segment.intent;

import com.company.campaign.shared.canonical.ContentHash;

/**
 * Confirms a registered-default citation against the injected registry.
 *
 * <p>A model can write any default id, version or hash into its output, so the citation is accepted only
 * when the registry returns a revision whose id, version and content hash all match exactly. A missing
 * registry, an unknown id and a revision mismatch each fail closed without disclosing whether a similarly
 * named default exists.
 */
public final class RegisteredDefaultRevisionVerifier {

    private RegisteredDefaultRevisionVerifier() {
    }

    public static void verify(
            RegisteredDefaultSource source, String defaultId, String version, ContentHash hash) {
        if (source == null) {
            throw IntentException.invalid(
                    IntentErrorCodes.DEFAULT_UNKNOWN,
                    "A registered-default citation requires an injected registry");
        }
        RegisteredDefaultSource.RegisteredDefaultRevision revision = source.find(defaultId).orElseThrow(
                () -> IntentException.invalid(
                        IntentErrorCodes.DEFAULT_UNKNOWN, "No registered default matches the cited id"));
        if (!revision.version().equals(version) || !revision.contentHash().equals(hash)) {
            throw IntentException.invalid(
                    IntentErrorCodes.DEFAULT_REVISION_MISMATCH,
                    "A registered-default citation does not match the registered revision");
        }
    }

    /** Resolves a registered default id to its exact registered currency code, or throws when unknown. */
    public static String resolveCurrency(RegisteredDefaultSource source, String defaultId) {
        if (source == null) {
            throw IntentException.invalid(
                    IntentErrorCodes.DEFAULT_UNKNOWN,
                    "A registered-default currency requires an injected registry");
        }
        return source.find(defaultId)
                .map(RegisteredDefaultSource.RegisteredDefaultRevision::value)
                .orElseThrow(() -> IntentException.invalid(
                        IntentErrorCodes.DEFAULT_UNKNOWN, "No registered default matches the cited id"));
    }
}