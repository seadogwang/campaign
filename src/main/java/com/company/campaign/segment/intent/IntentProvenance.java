package com.company.campaign.segment.intent;

import com.company.campaign.contracts.SourceSpan;
import com.company.campaign.shared.canonical.ContentHash;

/**
 * The single provenance form every clause must carry.
 *
 * <p>The union has exactly two variants: a non-empty source span grounded in the original utterance, or a
 * reference to an exact registered-default revision supplied through the injected registry. There is no
 * "neither" and no "both": a clause with no provenance, or with two forms, fails closed. A registered
 * default cannot be forged because the referenced version and content hash must match the registry's
 * actual revision.
 */
public sealed interface IntentProvenance
        permits IntentProvenance.FromSourceSpan, IntentProvenance.FromRegisteredDefault {

    /** Provenance grounded in the original utterance. */
    record FromSourceSpan(SourceSpan span) implements IntentProvenance {

        public FromSourceSpan {
            if (span == null) {
                throw IntentException.invalid(
                        IntentErrorCodes.SPAN_MISSING, "A source-span clause requires a span");
            }
        }
    }

    /** Provenance naming an exact registered default revision. */
    record FromRegisteredDefault(String defaultId, String version, ContentHash contentHash)
            implements IntentProvenance {

        public FromRegisteredDefault {
            defaultId = IntentTexts.requireReferenceId(defaultId, "provenance.defaultId");
            version = IntentTexts.requireText(version, "provenance.version");
            if (version.isEmpty() || contentHash == null) {
                throw IntentException.invalid(
                        IntentErrorCodes.DEFAULT_REFERENCE_INVALID,
                        "A registered-default reference requires an exact version and content hash");
            }
        }
    }
}